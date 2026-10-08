# Task runtime

A task runs when the engine in `service-core` admits it to the claim-based queue, a `service-dispatcher`
instance claims it over HTTP, and a `TaskExecutor` implementation runs the task's image on Kubernetes and
reports the results back. Only `template`, `custom`, `script`, `generic` and `ai` tasks go to a dispatcher
(`engine/TaskExecutionService.java:208-212,340`); every other type runs inside the engine. The shipped dispatcher
registers `template`, `custom`, `script` and `ai` (`flow.dispatcher.task-types`; `dispatcher/QueueService.java:72-76`),
so a `generic` task waits in the queue until a dispatcher registers that type. To give AI tasks their own network
zone, remove `ai` from the general dispatcher's list and run a second dispatcher deployment with
`flow.dispatcher.task-types=ai`.

## Dispatcher protocol

The dispatcher registers once, long-polls two queues, sends one lease heartbeat every 30 seconds, calls three lifecycle routes, and asks one reconciliation question.

| Route (`/api/v1/dispatcher`, `dispatcher/DispatcherControllerV1.java:45-180`) | Direction | Payload |
| --- | --- | --- |
| `POST /register` | dispatcher → engine | `name`, `host`, `version`, `taskTypes`; upserted on name+host, returns the dispatcher id (`DispatcherService.java:99-126`) |
| `GET /{id}/workflows` | long poll | 200 = WorkflowRuns that declare workspaces, claimed by this call for provisioning; 204 = none within the window (`DispatcherService.java:165-208`) |
| `GET /{id}/tasks?limit=&type=&task=&workflowLabel=` | long poll | 200 = TaskRuns claimed for execution or termination, within the registered types and the poll's filters; 204 = none within the window. `limit` caps the new execution claims at the dispatcher's free slots, never above a page; `0` claims none and still returns termination orders (`DispatcherService.java:260-340`) |
| `PUT /workflowrun/{id}/start` | dispatcher → engine | Called once the run's workspaces are provisioned (`QueueService.java:63-82`). A provisioning failure is only logged; the run stays claimed until the engine's watcher releases the stale claim for another attempt, failing the run after three (see `execution-model.md`) |
| `POST /workspaces/releasable` | dispatcher → engine | `workflowRunRefs`, `workflowRefs` — the owners of the volumes this dispatcher still holds (500 each at most, larger is `400`); the response echoes back the subset whose owner is finished, meaning the run is completed or gone and the workflow deleted or gone (`DispatcherService.releasable:347`) |
| `PUT /taskrun/{id}/start`, `/end` | dispatcher → engine | `start` answers with the TaskRun; the dispatcher creates nothing when it comes back `completed` (cancelled while being handed over) or the engine refuses with a 4xx, and goes ahead when the engine cannot be reached (`client/EngineClient.java:123`, `QueueService.java:105`). `end` carries `status`, `statusReason`, `statusMessage`, `results` (`QueueService.java`, `endFailed`); any executor exception ends the task `failed` with a typed `statusReason` from the closed set on `TaskRunEndRequest` (`error/TaskExecutionException.java`) and the results the task wrote before it failed. `ExceededQuota` and `StartTimeout` mean the task never started: the engine requeues it instead (see "Starting a task") |
| `PUT /{id}/heartbeat` | dispatcher → engine, every `flow.dispatcher.lease.beat-ms` (30 s) | `ids` of the task runs whose executor threads stamped `LeaseRegistry` since the last beat (`dispatcher/LeaseHeartbeat.java`); the engine renews `claim.leaseExpiresAt` for the ids this dispatcher owns (`DispatcherService.heartbeat`, `flow.dispatcher.lease-ms` 90 s) |

Each queue call is a long poll: the engine holds it up to 30 s, re-checks every 1 s, and answers once it has
claimed something, at most a page of 20 per kind; a failed claim step still hands out what that pass claimed
(`DispatcherService.java:48-50`, `:291-337`). The dispatcher polls again at once after runs or a held window,
and waits until 5 s after the start of a poll that failed or was answered empty at once, such as while
claiming is off (`client/EngineClient.java:29-36`, `:236-281`). A newly ready task is claimed within about
1 s while a dispatcher for its type has a free slot.

**Slots.** A dispatcher runs at most `flow.dispatcher.task.max-in-flight` tasks at once (25; 0 is no cap). A
slot is taken when a task to execute arrives, before the hand-off, and given back when its executor work ends,
a Pending pod included; each task poll sends `limit` = the free slots (`dispatcher/TaskSlots.java`,
`EngineClient.java:220-224,264`, `QueueService.java:149`). Termination orders take no slot, so a full
dispatcher still hears that its own tasks were cancelled. Work it does not claim stays claimable by others.

**Filters.** A poll may narrow what it claims: `type` (a subset of the registered types, else `400
QUERY_INVALID_FILTERS`), `task` (task slugs, resolved to `taskRef`s) and `workflowLabel` (`key=value[,value]`
on the workflow definition, resolved to `workflowRef`s). Values are comma-separated with `*` as the only,
anchored wildcard; filters are ANDed and values ORed. Resolutions are cached 30 s; a filter that matches
nothing answers 204 at once. Termination orders follow the same filters. An unfiltered poll can still claim
filtered work (`dispatcher/ClaimFilterService.java`, `engine/TaskRunService.java:97-119,182-191`).

Claims are compare-and-set per document, so two dispatchers never receive the same run
(`DispatcherService.java:300-325`). Releasing storage is not claimed work and carries no run state: the
dispatcher lists what it holds from the cluster and the engine answers which owners are finished, so the same
question asked twice gets the same answer. A TaskRun arriving in phase `completed` with status `cancelled` or `timedout`
is a termination order: the dispatcher cancels the runtime object and reports nothing, not even when there is
nothing left to cancel (`QueueService.java:117-128`).

**Token.** The dispatcher sends `flow.engine.dispatcher.token` as `Authorization: Bearer` on every engine
call (`config/RestConfig.java:52,120`): an ordinary global-scope Flow token with a machine actor kind, minted
through the token API (`dispatcher/DispatcherAuthFilter.java:18-22`). `DispatcherAuthFilter` guards
`/api/v1/dispatcher/**` in its own security chain (`DispatcherSecurityConfiguration.java:42-47`); a bearer not
shaped like a Flow token is rejected before any database lookup, otherwise `TokenService.validateActorToken`
decides (`DispatcherAuthFilter.java:87-104`), and `flow.security.enabled=false` permits everything (`:83-86`).
Logs flow the other way: the dispatcher serves `/api/v1/logs[/stream]` (`dispatcher/LogV1Controller.java:14-30`)
and the engine proxies them through `flow.agent.logstream.url` (`engine/LogClient.java:34`).

## Executors

`TaskExecutor` has four methods — `create`, `watch`, `cancel`, `delete`
(`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-27`). `TaskService` requires an image
(`dispatcher/TaskService.java:69-71`), falls back to `kube.task.timeout` (60 minutes, `:56-58`) whenever the
TaskRun carries no timeout or 0, runs `create` then `watch`, and deletes the runtime object per the TaskRun's
`spec.deletion` (`:52-54,76-79,98-100`). The engine always sets it from the admin "Deletion Policy"
(`task`/`deletion.policy`: `Never` default, `OnSuccess`, `Always`; `workflow/WorkflowService.java:691-694`,
`engine/DAGUtility.java:293-301`), so the fallback `kube.task.deletion` never applies. Logs are read from the pod
(`kube/KubeLogService.java:54-63`) and go with it. The delete runs off the dispatch thread, through a self proxy to
an `@Async` method, after a one-second grace (`:41,112-119`).

Whatever the deletion policy says, a finished runtime object is removed `kube.task.ttlDays` (default 7) days
after completion, so retention means the same on both executors. The Jobs executor stamps
`ttlSecondsAfterFinished` and Kubernetes collects the Job natively (`kube/KubeJobsExecutor.java:227`). Tekton
ships no TTL controller, so `dispatcher/TaskRuntimeReconciler.java` sweeps instead: on the `tekton` executor,
every `flow.dispatcher.task.reconcile-ms` (hourly default; off via `flow.dispatcher.task.reconcile.enabled=false`)
a tick lists the TaskRuns carrying this dispatcher's own product and tier labels
(`kube/KubeHelperService.java:387`), deletes those with a terminal `Succeeded` condition whose `completionTime`
is older than the TTL (`TaskRuntimeReconciler.java:96`), and logs one `held/deleted` summary; a failed tick
warns and the next tick asks again.

The timeout the TaskRun arrives with is settled by the engine, not here: it is the smallest of the platform
setting stamped as `boomerang.io/task-timeout`, the task's own declared timeout, and the run's timeout, with 0
meaning unguarded (`service-core/src/main/java/io/boomerang/engine/DAGUtility.java:196-222`). The seed ships the
platform setting (`task`/`default.timeout`) at 0, so by default a task inherits its run's timeout and the setting
is an optional operator ceiling for capping one pod inside a long-running graph; a TaskRun that reaches the
dispatcher unguarded still gets `kube.task.timeout` as a per-pod backstop.

`dispatcher.executor` picks one implementation:

| `dispatcher.executor` | Class | Runtime object | Timeout | Results channel | Cancel |
| --- | --- | --- | --- | --- | --- |
| `tekton` (default) | `kube/TektonServiceImpl.java:59` | One Tekton v1 `TaskRun` with an inline `taskSpec` and a single step named `task` (`:370,453-460`) | `spec.timeout` in minutes (`:414,452`) | `status.results` (`:523,547`); a 4096-byte overflow is detected from the pod log tail (`:567`) | Overwrite the status condition with `TaskRunCancelled` (`:609-646`) |
| `kube-jobs` | `kube/KubeJobsExecutor.java:65` | One `batch/v1` `Job` (`:199`); `restartPolicy`, `backoffLimit`, TTL from `kube.task.*` (`:162,183-184`) | `activeDeadlineSeconds = minutes × 60` (`:185`) | Termination message at `/dev/termination-log`, a JSON object or Tekton's `[{key,value}]` array (`:156-158,393-402`; `executor/TerminationMessageParser.java:15-17`) | Delete the Job and its script ConfigMap (`:424-461`) |

Both executors hold one thread per task in a reconcile loop: a label-selector watch is the fast path, and every
`kube.timeout.reconcileSeconds` (default 30) the loop re-lists the object by label, applies the same terminal
logic, stamps the lease registry, and re-opens the watch if it was closed (`KubeJobsExecutor.java`, `watch`;
`TektonServiceImpl.java`, `watchTaskRun`); it gives up at `timeout + kube.timeout.watchGraceMinutes` (default 2,
`application.properties:28`). A Job whose pod count reports a failure before its `Failed` condition exists is
held until the condition arrives, so a deadline kill is reported as `DeadlineExceeded` rather than `JobFailed`;
after `kube.timeout.failedConditionGraceSeconds` (60) without a condition it is reported as `JobFailed`
(`executor/JobWatcher.java:21`). `create` adopts an existing Job or TaskRun that already carries the task's labels
instead of creating a second one. The
Jobs executor mounts a `script` task's body from a per-task ConfigMap at `/scripts/script`, which MUST
start with a shebang (`KubeJobsExecutor.java:295-311`).

**Starting a task.** Starting is bounded apart from running, and a task that never started is handed back
rather than failed:

| Situation | Reported as | Then |
| --- | --- | --- |
| Create refused by a namespace quota (403 "exceeded quota") | `ExceededQuota` (`dispatcher/TaskService.java:89`) | the engine requeues it |
| Create refused by an admission webhook | `AdmissionDenied` (`:95`) | the task fails |
| A container held on an image it cannot pull (`ErrImagePull`, `ImagePullBackOff`, `InvalidImageName`, `ImageInspectError`) for `kube.timeout.startFailureGraceSeconds` (60) | `ImagePull` | the runtime object is deleted; the task fails |
| A container that cannot be created (`CreateContainerConfigError`, `CreateContainerError`) for the same grace | `DispatchError` | the runtime object is deleted; the task fails |
| The pod still Pending - unschedulable, held by a quota, never created - `kube.timeout.startMinutes` (15; 0 is off) after the watch began | `StartTimeout` | the runtime object is deleted; the engine requeues it |

Each reconcile tick reads the task's pod - init containers included, and Tekton's `step-task` container as well
as the Jobs executor's `task` - until the pod leaves Pending (`kube/KubeHelperService.java:480-523`,
`executor/PodStartWatch.java`, `KubeJobsExecutor.java:439`, `TektonServiceImpl.java:540`). The engine requeues
`ExceededQuota` and `StartTimeout` on the same budget as a timed-out claimant: three more attempts with backoff,
then the task fails with that reason (`engine/TaskRunService.java:1004`). Kubernetes 429, 5xx and network errors
are retried inside the Kubernetes client before any of this applies.

## What the container receives

The engine substitutes `$(params.x)`, `$(tasks.x.results.y)` and the other references into the TaskRun's own
parameter values and into `spec.script`, `spec.command`, `spec.arguments` and `spec.envs`
(`engine/ParameterManager.java:111,121-136`), then persists the resolved copy with the admission
compare-and-set (`TaskExecutionService.java:181`; `TaskRunService.java:278`). The TaskRun's params are the
task's declared params merged with the values authored on the workflow node (`engine/DAGUtility.java:183`).
A `custom` task takes its runtime from its own params — `image`, `command` and `arguments` (newline-split)
and `shellScript` — rather than from the catalogue entry, which declares no image
(`DAGUtility.java:247,302`).

The global, workspace and workflow-context values are not copied onto the run. `ParameterManager` reads them
from their stores each time it resolves (`engine/ParameterManager.java:238`, through
`workflow/ParamLayerService.java:108`): at run start for the run's own params (`engine/WorkflowExecutionService.java:71`),
and at each task's admission for the task's params and spec (`TaskExecutionService.java:205`; a for-each task's
items at fan-out, `:605`). It reads them for the run's workspace (`boomerang.io/workspace-name`), and takes the
workflow context - name, display name, id, version and workflow tokens - from the run's workflow and the
revision it runs, so a version saved mid-run does not change it. The layers and the revision are served from a
per-instance cache for 10 seconds (`core/ParamLayerCache.java`, `flow.parameters.layer-cache.ttl`; `0s` turns it
off), so the tasks admitted together share one read of each store. A global or workspace parameter edited while
a run is in progress reaches the tasks admitted after the edit: at once on the instance that took the edit, which
clears its cache, and within the TTL on the others. Each task run keeps the values it was resolved with. Runs created before this carried the values in `boomerang.io/global-params`, `workspace-params` and
`context-params`; reads strip those keys (`workflow/WorkflowRunService.java:1153`) and a child run does not inherit
them (`workflow/WorkflowService.java:708`).

Parameter references, by where the value comes from (`engine/ParameterManager.java`, `common/model/ParamLayers.java`):

| Reference | Value from |
| --- | --- |
| `$(params.x)` | The nearest layer that defines `x`: the task, then the workflow, then workspace and global |
| `$(workflow.params.x)` | The workflow's parameters |
| `$(workspace.params.x)` | The workspace's parameters. `$(team.params.x)` still resolves, is deprecated, logs a warning once per reference, and is removed in the next major version; the editor suggests only `workspace` |
| `$(global.params.x)` | Global parameters |
| `$(context.params.x)` | Run context (`workflowrun-*` keys) |
| `$(tasks.t.results.r)` | A result of task `t` |

Substitution writes into string leaves directly, so a replacement's quotes, newlines, backslashes and `$`
characters are inserted verbatim: a multi-line prompt, a JSON body, a shell script or a task result with a
trailing newline reaches the container byte for byte
(`ParameterManager.replaceStringInObject`). A reference that matches nothing is left as written. Substitution
also runs over the values it inserts, so configuration composes - a workspace value containing
`$(global.params.x)` resolves - with two exceptions, whose values are inserted as written (their `$(` escaped,
`ParameterManager.java:328,359`):

| Inserted as written | Why |
| --- | --- |
| A task result, `$(tasks.t.results.r)` | It is what a task produced, not what anyone in the workspace wrote |
| A supplied run param, read as `$(params.x)` or `$(workflow.params.x)` | On a run started by a webhook, event, GitHub event, Run workflow task or retry, every param whose value differs from its revision default (`:80`, `:294`). Such a param is also kept as it arrived at run start (`:137`). On a manual, scheduled or API-submitted run every value resolves |

`$(params.x)` reads the nearest layer, so a task that defines `x` itself resolves its own value even when the run's
`x` was supplied. A whole-value reference returns the referenced structure untouched. A replacement that is not a string and is interpolated **into** a larger string is written as JSON
(`{"k":"v"}`), matching how the dispatcher encodes a non-string param value.

An `object`-typed param resolves to the referenced structure itself only when its value is **exactly one
reference and nothing else** — `"$(params.config)"`, ignoring surrounding whitespace
(`ParameterManager.isSingleReference`). Any other `object` value keeps its shape: the engine walks the Map,
Collection or array, substitutes the string leaves and the string keys in place, and leaves numbers and
booleans untouched, so `{"url": "$(params.host)/api", "retries": 3}` resolves to
`{"url": "https://example.com/api", "retries": 3}`. References are discovered over the value's flattened
text, so a reference nested in any leaf is still found; substitution then walks the real structure. A leaf
that is itself a whole reference to an object follows the interpolation rule above and is written as JSON.

If substitution fails for any reason — a value that resolves to itself, say — the original value is passed
through unchanged and the failure is logged with the value's shape, never its content, because a
password-typed param resolves through the same path.

The dispatcher then sets these environment variables (`kube/KubeHelperService.java:111-149`):

| Variable | Value |
| --- | --- |
| `PARAM_<NAME>` | One per param; the name upper-cased with any character outside `[A-Za-z0-9_]` replaced by `_` (`ParameterUtil.java:91-95`); non-string values JSON-encoded (`service-dispatcher/README.md`) |
| `PARAM_NAMES` | The original names, comma-separated, so a library can map `PARAM_PRIVATEKEY` back to `privateKey` |
| `RESULTS_PATH` | `/tekton/results` (a directory, one file per result) on Tekton; `/dev/termination-log` (one file) on Jobs |
| `RESULTS_MAX_BYTES` | The most bytes the task may write to `RESULTS_PATH`, set by the executor that runs it: `4096` on Jobs (the termination message cap, `KubeJobsExecutor.TERMINATION_MESSAGE_MAX_BYTES`); `tekton.results.maxBytes` on Tekton (default `4096`, raised to the cluster's `max-result-size` under sidecar-logs). A task budgets against it rather than a constant of its own |
| `DEBUG`, `CI=true`, `FLOW_VERSION`, proxy vars | Debug flag, CI marker, the dispatcher's `flow.version`, and the `HTTP_PROXY` family when `proxy.enable=true` |

Explicitly declared task env vars win on a name collision (`KubeHelperService.java:145-147`). There is no
`/params` file directory and no `PARAMS` JSON variable; large inputs belong on a workspace mount, with the
param carrying the path.

An item of a for-each task is an ordinary TaskRun to the dispatcher: it is claimed by `id` and run like any
other of its type. It carries the parent's params plus `item` (the element; an object or array arrives
JSON-encoded) and `index` (its position from 0, as text), so the container sees `PARAM_ITEM` and `PARAM_INDEX`,
and `$(params.item)` resolves in the spec. Its params and spec resolve when the item is admitted, not when the
parent fans out (`engine/TaskExecutionService.java:663-701`). Every item mounts the same workspaces as the parent,
so all items of a run share the run's workspace.

## Results and payload caps

The params cap is the engine's, identical on every executor. The results limit belongs to the dispatcher that
runs the task: on Kubernetes it is the 4096-byte container termination message, enforced by the dispatcher. The
engine's results setting is only a storage guard, so a faulty dispatcher cannot grow a task run toward MongoDB's
16 MB document limit. A task reports only the result names its definition declares
(`TerminationMessageParser.java:24-27,72-73`).

| Cap | Property (`service-core/.../application.properties:176-177`) | Where checked | Effect |
| --- | --- | --- | --- |
| Params | `flow.engine.task.params.max-bytes=16384` | Before admission (`TaskExecutionService.java:205-219`) | The task is invalidated with `PARAMS_TOO_LARGE` and never becomes claimable |
| Results (storage guard) | `flow.engine.task.results.max-bytes=1048576` (1 MB) | In `TaskRunService.end` (`TaskRunService.java:909-919`) | Status becomes `failed` with `RESULTS_TOO_LARGE` and `statusReason=ResultsTooLarge`; the oversize results are not persisted |

On Kubernetes an oversize payload never reaches the engine, because Kubernetes truncates a container
termination message at 4096 bytes and the truncated prefix is broken JSON. `TerminationMessageParser` reports an
unparseable message as absent rather than as "no results", and `KubeJobsExecutor.readResults` fails the task with
`ResultsTooLarge` when the pod log carries Kubernetes' own too-large line or when the unparseable message is at
the ceiling; a short unparseable message is a task writing something that is not a results payload, so it is
logged and carries no results. On Tekton the overflow fails the TaskRun itself and is mapped the same way
(`TektonServiceImpl.java:606`).

A for-each task's results are not checked again. Each item is capped where it returns its results, like any task;
the parent's results are the per-item values collected into arrays by the engine
(`TaskExecutionService.completeParentIfItemsTerminal` `:716`), with no cap of their own. When MongoDB refuses the
combined arrays as over its 16 MB document limit, the parent fails with `statusReason = ResultsTooLarge` and no
results, and the run moves on (`TaskExecutionService.java:750-778`). A task that consumes the array still meets
the params cap at its own admission.

## Run labels on Kubernetes objects

Run labels are user metadata and reach the dispatcher unchecked, but Kubernetes rejects an entire object when one
label breaks its rules, so `KubeHelperService` coerces every user-supplied key and value into shape before it is
merged into a TaskRun, Job or volume's labels — one place all executors share.

| Part | Rule applied | Mapping |
| --- | --- | --- |
| Value, and the name half of a key | At most 63 characters of `[A-Za-z0-9._-]`, alphanumeric at both ends | Any other character becomes `_`, the string is truncated to 63, then trimmed to alphanumeric ends |
| The optional `prefix/` half of a key | A DNS subdomain: at most 253 characters of `[a-z0-9.-]`, each dot-separated part alphanumeric at both ends | Lower-cased, any other character becomes `-`, truncated to 253, empty parts dropped |
| A key with no usable name | — | Dropped; nothing can be written under it |
| A key the dispatcher already set (`boomerang.io/*`, `app.kubernetes.io/*`) | — | The dispatcher's value wins; these are the selectors every lookup, watch and delete runs on |

Every alteration is logged at debug. So `team/name=platform/flow` is written as `team/name=platform_flow`
rather than failing the volume with a 422.

## Task versions on a workflow node

A workflow node's `taskVersion` is pinned when the workflow is saved, not when it runs:
`WorkflowService.createWorkflowRevisionEntity` resolves each non-start/end node through
`TaskService.retrieveAndValidateTask` and stamps the resolved version onto the node — the version the node asked
for, or the catalogue's latest when it asked for none. At run time `DAGUtility.createTaskList` resolves the same
way and records the result on the TaskRun (`engine/DAGUtility.java:140-142`), so a stored node with no version
still resolves latest. Publishing a new Task version therefore changes nothing for workflows already saved
against an older one, which is the point: a run is reproducible from its revision. To move a workflow forward,
save it again with the node's `taskVersion` set to the target version (or omitted, to take latest); the editor
surfaces this per node as a "New version available" prompt, driven by the `upgradesAvailable` flag
`WorkflowService.areTaskUpgradesAvailable` sets
(`client-web/src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.tsx:58-64,133`).

## Parameter names

Names MUST match `^[a-zA-Z_][a-zA-Z0-9_-]*$`, and any variant of `names` is reserved because it would fold
to `PARAM_NAMES` (`lib-common/.../ParameterUtil.java:83-89`). `item` and `index` are reserved on a for-each task:
the engine adds both to each item, so a workflow whose for-each task's template declares either is refused on save
with `WORKFLOW_INVALID_TASK_FOREACH` (`workflow/WorkflowService.java:1782-1819`). Matching is case-insensitive everywhere:
`$(params.myparam)` resolves a param declared `MyParam` (`ParameterManager.java:238`), and the node-value
merge keeps the declared casing (`ParameterUtil.java:57-66`). An empty or absent value is valid and survives save unchanged — emptiness can be meaningful, and a
substitution can resolve to empty; a task that requires a value fails its own run with a message naming the
parameter (`engine/TaskExecutionService.java`, `runWorkflow`). The safety pair is rejection at save: names that
are case or separator variants of each other (`my-key`, `MY_KEY`) fail with `PARAM_NAME_COLLISION` (code 1209,
`BoomerangError.java:50`) at workflow save (`workflow/WorkflowService.java:1593-1599`) and task save
(`workflow/TaskService.java:366-374`); the dispatcher repeats the check at dispatch (`KubeHelperService.java:131-140`).

## Sensitive parameters

A param is sensitive when its spec has `type=password` (`DataAdapterUtil.java:22`); there is no separate
marker, no field on the run, and values are filtered on the way up only. A `RunParam` carries no type on
the wire, so the type comes from the definition - and two definitions can declare it:

| Declared on | Reached through | Example |
| --- | --- | --- |
| The workflow revision's param spec | `WorkflowRun.workflowRevisionRef` | a workflow param referenced as `$(params.apiKey)` |
| The catalogue task's own spec | `TaskRun.taskRef` + `TaskRun.taskVersion` | a value typed straight into a task node's `apiKey` field |

`WorkflowRunService.filterSensitiveValues` consults both (`workflow/WorkflowRunService.java:172-229`):
each blanks its own password-typed params by name, and the **union** of the values they resolve to is then
scrubbed run-wide - from the run's results and from every task's params, spec fields (script, command,
arguments, envs) and results, because substitution moves a value into any of them under another name
(`DataAdapterUtil.java:139-211`). The task-spec lookup is batched: the distinct `(taskRef, taskVersion)`
pairs on a response are resolved together by `TaskService.getSpecs`, two queries regardless of task count.
Tasks are attached only by `get(id, withTasks=true)` (`WorkflowRunService.java:552-553`), so the paged
`query` - which returns no tasks - issues no task lookup at all.

The task log stream is wrapped in `FilterValuesOutputStream`, a line-buffered scrub of the same union
(`:419-427`, `:435-452`; `lib-common/.../FilterValuesOutputStream.java:21`), taken over every task of the
owning run rather than the streamed task alone. The dispatcher ends the stream when the pod is already
finished or as soon as it finishes (`kube/KubeLogService.java:24`), and the engine permits the
asynchronous completion of a streamed response without re-running authorization on it
(`core/security/SecurityConfiguration.java:81`, `SecurityInterceptor.java:45`). Engine and dispatcher reads, and delivery into the
container, carry the real values. A resolved value shorter than four characters is blanked by name but not
value-scrubbed - replacing 1-3 character strings would mangle unrelated text (decision 0043).

The union also holds the values of the run workspace's password-typed **global and workspace** parameters
(`ParamLayerService.securedValues`, `workflow/ParamLayerService.java:161`, read uncached; `WorkflowRunService.java:184,489`),
which neither definition declares yet substitution writes into task params, scripts and results. They are read
once per workspace per response, and with their current values: a run that used a value since rotated keeps
showing it.

## Volumes and workspaces

Every task gets `/data`, a per-pod `emptyDir` (RAM-backed when `kube.task.storage.data.memory=true` and the task
param `worker.storage.data.memory` is set; `KubeJobsExecutor.java:208-227`, `TektonServiceImpl.java:301`). Shared
storage is a workflow-level opt-in with two types (`StorageType.java:12-13`), each a persistent volume claim (PVC)
bound at `/workspace/<type>` or the task's declared `mountPath` (`KubeJobsExecutor.java:245-267`;
`TektonServiceImpl.java:259,283`). What a task mounts is decided once, when `DAGUtility` materialises the TaskRun,
by a three-way rule on the node's `workspaces` (`engine/DAGUtility.java:230-249`): a declared list mounts exactly
that list, an empty list is an explicit opt-out that mounts none, and no list at all - the canvas offers no way to
declare them, so this is the ordinary case - inherits every workspace the run carries, in the run's order. Nothing
later in the run changes that list. The executor mounts by type and takes `mountPath` from the task's own entry,
falling back to `/workspace/<type>` when it is blank (`KubeJobsExecutor.java:307-310`,
`TektonServiceImpl.java:230-234`); an inherited entry gets its `mountPath` from the workflow-level
`spec.mountPath`, which is how that field is read, and a workspace whose `spec` is absent or does not convert
leaves it null for that same fallback (`engine/DAGUtility.java:573-582`). A `workflow` PVC is keyed by
`workflowRef`, created at the first run's start if absent and never deleted by a run; a `workflowrun` PVC is keyed
by the run id, created at start and deleted when the dispatcher's reconciliation finds its run completed
(`dispatcher/WorkflowService.java:41-60,88-100`). Finished task pods still hold the claim, so under `Never` it
stays `Terminating` until retention removes them. The authored spec (`size`, `accessMode`, `className`,
`mountPath`) survives save; `size` is a Kubernetes quantity (`1Gi`, `500Mi`; a bare number means Gi) checked
against the workspace quota in Gi both when the Workflow is saved and when a run that carries its own workspaces is
submitted (`workflow/WorkflowService.java:483-497,971-988`, `lib-common/.../util/StorageQuantityUtil.java:13`).
Size, class and access mode default to `kube.workspace.storage.*` (1Gi, `ReadWriteMany`); a blank class leaves
`storageClassName` unset so the cluster default applies, because an empty string disables dynamic provisioning
(`KubeServiceImpl.java:175`). After creating a claim the dispatcher waits up to `kube.timeout.waitUntil` (30 s) for
it to reach `Pending` or `Bound`, treating a momentarily absent claim as not yet settled
(`KubeServiceImpl.isClaimSettled` `:207`); any error while creating a workspace surfaces as one provisioning
failure (`dispatcher/WorkspaceService.java:86-102`).

## Isolation and placement

`dispatcher.tasks.runtimeClassName` sets the pod `runtimeClassName` (gVisor, Kata, confidential containers)
for every task the deployment runs — on the pod spec for Jobs (`KubeJobsExecutor.java:172-173`) and on the
TaskRun `podTemplate` for Tekton (`TektonServiceImpl.java:467-470`). There is no per-task isolation field: a
different tier is a second dispatcher deployment with its own name and task types. Node selector,
tolerations, host aliases and the image pull secret are likewise per deployment
(`application.properties:22-23,43-46`; `KubeJobsExecutor.java:165-166`; `TektonServiceImpl.java:471-474`).
Empty toleration or host-alias entries are dropped before dispatch, so a `[]` or `[{}]` default never reaches
the API server (`KubeHelperService.java:240`). Tasks, claims and ConfigMaps are created in `kube.namespace`,
or the kubeconfig context's namespace when it is blank; the dispatcher refuses to start when neither resolves
(`config/KubeClientConfig.java:21,40`).

## Container resources

Every task container carries the requests and limits one shared resolver reads from configuration
(`executor/TaskResourceResolver.java`), so the sizing is the same on both executors: the Jobs executor sets it on
the task container (`KubeJobsExecutor.java:200`) and the Tekton executor on the step's `computeResources`
(`TektonServiceImpl.java:389`).

| Property | Default | Applied as |
| --- | --- | --- |
| `kube.resource.request.memory` | `2Gi` | `requests.memory` |
| `kube.resource.limit.memory` | `16Gi` | `limits.memory` |
| `kube.resource.request.ephemeral-storage` | `2Gi` | `requests.ephemeral-storage` |
| `kube.resource.limit.ephemeral-storage` | `16Gi` | `limits.ephemeral-storage` |
| `kube.resource.request.cpu` | empty | `requests.cpu` |
| `kube.resource.limit.cpu` | empty | `limits.cpu` |

Each value is a Kubernetes quantity and each tolerates being blank: blank sets that request or limit not at all,
never an empty quantity and never a zero limit, so a deployment can run with memory limits and no CPU limit. With
all six blank the container carries no resources block. Both CPU values ship blank because a CPU limit throttles a
task rather than failing it, which is a worse default than no limit; memory and ephemeral-storage ship with values
because a container without them can take a node. A memory-backed `/data` is a tmpfs, so what a task writes there
counts against the memory limit rather than against ephemeral-storage — that is how a container that would breach
the ephemeral-storage limit keeps running, and a deployment that enables it sizes memory to cover the data too.

The sizing is per dispatcher deployment, not per task, the same shape as the isolation tier (decision 0042): a
workflow author cannot ask for a bigger container, and a workload that needs different sizing runs a second
dispatcher deployment with its own task types. A runtime that takes a byte or CPU count rather than a quantity
string — the planned Docker executor — reads the same configured values through the resolver (`memoryLimitBytes`,
`cpuLimitNanos`, a CPU count in nano-CPUs); Docker has no ephemeral-storage concept, so that pair is
Kubernetes-only.

## AI tasks

An `ai` task calls an OpenAI-compatible endpoint. The author never builds a container: the node references the
seeded `ai` catalogue task, the engine treats it as any other dispatched type, and the dispatcher resolves the
worker image from `flow.dispatcher.ai.image` because the catalogue entry declares none
(`service-dispatcher/.../executor/TaskImageResolver.java:35-43`). Everything the model call needs is a declared
param.

| Param | Type | Default | Meaning |
| --- | --- | --- | --- |
| `endpoint` | `text`, required | — | An OpenAI-compatible base URL: OpenRouter, LiteLLM, Azure AI Foundry, Ollama |
| `token` | `password`, required | — | The endpoint's API token |
| `model` | `text`, required | — | The model identifier the endpoint expects |
| `systemPrompt` | `texteditor::text` | empty | Sent as the system message |
| `prompt` | `texteditor::text`, required | — | Supports `$(params.x)` and `$(tasks.x.results.y)` |
| `temperature` | `slider` 0–2 step 0.1 | `0.7` | Sampling temperature |
| `maxTokens` | `number` | `1024` | Upper bound on generated tokens |
| `responseFormat` | `select` `text`\|`json` | `text` | Free text or a JSON object |
| `jsonSchema` | `texteditor::text` | empty | A JSON Schema object; with `responseFormat` `json` the reply is held to it (`response_format` `json_schema`, strict). Requires task-ai 1.1.0 |
| `seed` | `number` | empty | Sampling seed, where the endpoint honours one |
| `files` | `text` | empty | Comma-separated paths on the run workspace, read into context |
| `maxContextBytes` | `number` | `65536` | Byte budget for `files` |

`slider` is the only param type with a numeric range, so `AbstractParam` carries nullable `min`, `max` and
`step` beside `options` (`lib-common/.../model/AbstractParam.java`).

The task declares six results — `output`, `promptTokens`, `completionTokens`, `totalTokens`, `finishReason`,
`model` — as flat typed results on the TaskRun under the same 4 KB cap as every other task (decision 0041). A
long `output` therefore fails the run with `RESULTS_TOO_LARGE` rather than truncating. There is no usage field
and no meter: a platform sums `totalTokens` across task runs through the existing query API.

**Worker image.** The worker is a task image, not a product image. It is built and released from the
`boomerang-io/tasks` repository (`tasks/ai`) as `boomerangio/task-ai`, tagged from that repository's own
`task-ai@<version>` tags — the same path as every other catalogue image (see "Task catalogue"
below). The product tag builds the four service and web images and not this one, so the worker and the product
version lines move independently; `flow.dispatcher.ai.image` defaults to an exact version,
`boomerangio/task-ai:1.1.0`, and an operator moves it to another `boomerangio/task-ai:<version>`
(`service-dispatcher/src/main/resources/application.properties:99-106`). What ties the two together is the
contract, not the tag: the twelve params above reach the image as `PARAM_<NAME>` environment variables and the
six results come back through `RESULTS_PATH`, and that contract is shared between the image and the seeded `ai`
catalogue revision in this repository — a param or result added on one side has to land on the other.

**Network zone.** A dispatcher registered with `taskTypes=[ai]` receives only `ai` tasks
(`DispatcherService.java:260-340`, `TaskRunService.findClaimable`), so the AI zone is a second dispatcher
deployment — its own namespace, egress policy and `runtimeClassName` — exactly as decision 0042 frames
isolation tiers. No configuration separates zones inside one dispatcher.

**Token delivery.** `token` is password-typed, so declaring it as a workflow param and referencing it from
the node blanks and scrubs it on the workspace-scoped run reads and the log stream (decision 0043); a literal
typed into the node is not scrubbed, per "Sensitive parameters" above. Downward it is a plain `PARAM_TOKEN`
environment variable on the pod, like every other param — there are no per-task secrets yet, so anyone who
can read the pod spec or exec into the pod can read the token.

## Task catalogue

Catalogue tasks are built from the `boomerang-io/tasks` monorepo into the `boomerangio/task-flow` image
(`service-loader/.../migration/_0039__RepointWorkerFlowImages.java:21-22,54`). The loader seeds 88 tasks and
their revisions from `seed/tasks.json` and `seed/task-revisions.json` into `tasks` and `task_revisions`,
inserting only what is absent (`_0022__SeedTaskCatalogue.java:88-130`). That unit runs once per install, so a
task added to the seed afterwards needs a change unit of its own to reach an existing database — `ai` has
`_0047__SeedAiTask`, which reads the same two seed documents and inserts the task, its revision and its root
edge if absent. A `template` or `script` task without
an explicit image inherits the run's `boomerang.io/task-default-image` value (`DAGUtility.java:212-218`).
The engine-handled `run-workflow` and `run-scheduled-workflow` entries declare the params the engine reads
(`workflowRef` and the boolean `wait`; plus `futureIn`, `futurePeriod`, `timezone`, `time`), added to an existing
catalogue by `_0040__DeclareRunWorkflowParams` and `_0046__DeclareRunWorkflowWaitParam`.

## Task types handled inside the engine

`TaskType` (`lib-common/.../enums/TaskType.java:15-32`) is dispatched in `TaskExecutionService.java:320-373`.

| Type | Behaviour |
| --- | --- |
| `start`, `end` | Structural nodes of the graph; never executed |
| `template`, `custom`, `script`, `generic`, `ai` | Wait for a dispatcher |
| `decision` | Evaluates the branch and ends `succeeded` |
| `acquirelock`, `releaselock` | Take or release a row in the `task_locks` collection; acquire parks as waiting until the lock is free |
| `runworkflow` | Submit a child workflow run. Ends `succeeded` at once, or with `wait=true` parks as waiting and takes the child's terminal status (see `execution-model.md`) |
| `runscheduledworkflow` | Schedule another workflow to run later, then end |
| `setwfstatus`, `setwfproperty` | Write the run's status message or a workflow-scoped param, then end |
| `approval`, `manual` | Create an action and wait for a person |
| `eventwait` | Wait for a matching inbound event unless pre-approved |
| `sleep` | Park as waiting; the watcher completes it after the duration |
| `uploadartifact`, `downloadartifact` | Wait for a dispatcher, which runs the default worker's `artifact upload` / `artifact download` (`flow.dispatcher.artifact.image`). The engine fills the link params when it hands the task out, and verifies an upload when it ends (`data-model.md`) |

## Not built

An Azure Blob artifact store waits for the serverless-container (ACA) dispatcher. A local Docker runtime and that dispatcher are planned executors.

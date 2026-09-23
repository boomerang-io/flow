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

The dispatcher registers once, polls two queues every 5 seconds, sends one lease heartbeat every 30 seconds, calls three lifecycle routes, and asks one reconciliation question.

| Route (`/api/v1/dispatcher`, `dispatcher/DispatcherControllerV1.java:50-180`) | Direction | Payload |
| --- | --- | --- |
| `POST /register` | dispatcher → engine | `name`, `host`, `version`, `taskTypes`; upserted on name+host, returns the dispatcher id (`DispatcherService.java:88-114`) |
| `GET /{id}/workflows` | poll, 5 s (`client/EngineClient.java:25`) | 200 = WorkflowRuns that declare workspaces, claimed by this call for provisioning; 204 = none (`DispatcherService.java:154-200`) |
| `GET /{id}/tasks` | poll, 5 s | 200 = TaskRuns claimed for execution or termination, filtered by the registered types (`DispatcherService.java:212-271`) |
| `PUT /workflowrun/{id}/start` | dispatcher → engine | Called once the run's workspaces are provisioned (`QueueService.java:47-58`) |
| `POST /workspaces/releasable` | dispatcher → engine | `workflowRunRefs`, `workflowRefs` — the owners of the volumes this dispatcher still holds (500 each at most, larger is `400`); the response echoes back the subset whose owner is finished, meaning the run is completed or gone and the workflow deleted or gone (`DispatcherService.releasable:288`) |
| `PUT /taskrun/{id}/start`, `/end` | dispatcher → engine | `end` carries `status`, `statusReason`, `statusMessage`, `results` (`QueueService.java`, `endFailed`); any executor exception ends the task `failed` with a typed `statusReason` from the closed set on `TaskRunEndRequest` (`error/TaskExecutionException.java`) and the results the task wrote before it failed |
| `PUT /{id}/heartbeat` | dispatcher → engine, every `flow.dispatcher.lease.beat-ms` (30 s) | `ids` of the task runs whose executor threads stamped `LeaseRegistry` since the last beat (`dispatcher/LeaseHeartbeat.java`); the engine renews `claim.leaseExpiresAt` for the ids this dispatcher owns (`DispatcherService.heartbeat`, `flow.dispatcher.lease-ms` 90 s) |

Claims are compare-and-set per document, so two dispatchers never receive the same run
(`DispatcherService.java:176-182`). Releasing storage is not claimed work and carries no run state: the
dispatcher lists what it holds from the cluster and the engine answers which owners are finished, so the same
question asked twice gets the same answer. A TaskRun arriving in phase `completed` with status `cancelled` or `timedout`
is a termination order: the dispatcher cancels the runtime object and reports nothing (`QueueService.java:88-95`).

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
(`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-27`). `TaskService` requires an
image (`dispatcher/TaskService.java:69-71`), defaults the timeout to `kube.task.timeout` (60 minutes, `:52-54`),
runs `create` then `watch`, and deletes the runtime object per `kube.task.deletion` (`Never` default,
`OnSuccess`, `Always` — `:48-50,76-79,98-100`). The delete waits a one-second grace before calling `delete`, and
runs off the caller's thread: `TaskService` reaches its own `@Async` method through a self proxy
(`:41,112-119`), so the dispatch thread is free as soon as the Task itself has finished.
`dispatcher.executor` picks one implementation:

| `dispatcher.executor` | Class | Runtime object | Timeout | Results channel | Cancel |
| --- | --- | --- | --- | --- | --- |
| `tekton` (default) | `kube/TektonServiceImpl.java:53` | One Tekton v1 `TaskRun` with an inline `taskSpec` and a single step named `task` (`:454,492`) | `spec.timeout` in minutes (`:440`) | `status.results` (`:571`); a 4096-byte overflow is detected from the pod log tail (`:552`) | Overwrite the status condition with `TaskRunCancelled` (`:617-632`) |
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

## What the container receives

The engine substitutes `$(params.x)`, `$(tasks.x.results.y)` and the other references into the TaskRun's own
parameter values and into `spec.script`, `spec.command`, `spec.arguments` and `spec.envs`
(`engine/ParameterManager.java:111,121-136`), then persists the resolved copy with the admission
compare-and-set (`TaskExecutionService.java:181`; `TaskRunService.java:278`). The TaskRun's params are the
task's declared params merged with the values authored on the workflow node (`engine/DAGUtility.java:183`).
A `custom` task takes its runtime from its own params — `image`, `command` and `arguments` (newline-split)
and `shellScript` — rather than from the catalogue entry, which declares no image
(`DAGUtility.java:247,302`).
The dispatcher then sets these environment variables (`kube/KubeHelperService.java:111-149`):

| Variable | Value |
| --- | --- |
| `PARAM_<NAME>` | One per param; the name upper-cased with any character outside `[A-Za-z0-9_]` replaced by `_` (`ParameterUtil.java:91-95`); non-string values JSON-encoded (`service-dispatcher/README.md`) |
| `PARAM_NAMES` | The original names, comma-separated, so a library can map `PARAM_PRIVATEKEY` back to `privateKey` |
| `RESULTS_PATH` | `/tekton/results` (a directory, one file per result) on Tekton; `/dev/termination-log` (one file) on Jobs |
| `DEBUG`, `CI=true`, `FLOW_VERSION`, proxy vars | Debug flag, CI marker, the dispatcher's `flow.version`, and the `HTTP_PROXY` family when `proxy.enable=true` |

Explicitly declared task env vars win on a name collision (`KubeHelperService.java:145-147`). There is no
`/params` file directory and no `PARAMS` JSON variable; large inputs belong on a workspace mount, with the
param carrying the path.

## Results and payload caps

The engine enforces both caps so the failure is one message on every executor; a task reports only the result names its definition declares (`TerminationMessageParser.java:24-27,72-73`).

| Cap | Property (`service-core/.../application.properties:154-155`) | Where checked | Effect |
| --- | --- | --- | --- |
| Params | `flow.engine.task.params.max-bytes=16384` | Before admission (`TaskExecutionService.java:161-175`) | The task is invalidated with `PARAMS_TOO_LARGE` and never becomes claimable |
| Results | `flow.engine.task.results.max-bytes=4096` | In `TaskRunService.end` (`TaskRunService.java:765-773`) | Status becomes `failed` with `RESULTS_TOO_LARGE`; the oversize results are not persisted |

An oversize payload usually never reaches that engine check, because Kubernetes truncates a container
termination message at 4096 bytes and the truncated prefix is broken JSON. `TerminationMessageParser` reports an
unparseable message as absent rather than as "no results", and `KubeJobsExecutor.readResults` fails the task with
`ResultsTooLarge` when the pod log carries Kubernetes' own too-large line or when the unparseable message is at
the ceiling; a short unparseable message is a task writing something that is not a results payload, so it is
logged and carries no results. On Tekton the overflow fails the TaskRun itself and is mapped the same way
(`TektonServiceImpl.java:606`).

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
to `PARAM_NAMES` (`lib-common/.../ParameterUtil.java:83-89`). Matching is case-insensitive everywhere:
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

## Volumes and workspaces

Every task gets `/data`, a per-pod `emptyDir` (RAM-backed when `kube.task.storage.data.memory=true` and the
task param `worker.storage.data.memory` is set; `KubeJobsExecutor.java:208-227`, `TektonServiceImpl.java:301`).
Shared storage is a workflow-level opt-in with two types (`StorageType.java:12-13`), each a persistent
volume claim (PVC) bound at `/workspace/<type>` or the task's declared `mountPath`
(`KubeJobsExecutor.java:245-267`; `TektonServiceImpl.java:259,283`). A task mounts only the workspaces it
declares: `DAGUtility` copies the node's `workspaces` onto the TaskRun (`engine/DAGUtility.java:214`) and the
executor mounts by type. A `workflow` PVC is keyed by `workflowRef`, created at the first run's start if absent
and never deleted by a run; a `workflowrun` PVC is keyed by the run id, created at start and deleted when the
dispatcher's reconciliation finds its run completed (`dispatcher/WorkflowService.java:41-60,88-100`). The authored spec (`size`, `accessMode`, `className`,
`mountPath`) survives save; `size` is a Kubernetes quantity (`1Gi`, `500Mi`; a bare number means Gi) checked
against the workspace quota in Gi (`workflow/WorkflowService.java:448`,
`lib-common/.../util/StorageQuantityUtil.java:13`). Size, class and access mode default to
`kube.workspace.storage.*` (1Gi, `ReadWriteMany`); a blank class leaves `storageClassName` unset so the cluster
default applies, because an empty string disables dynamic provisioning (`KubeServiceImpl.java:175`).

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
`boomerangio/task-ai:1.0.0`, and an operator moves it to another `boomerangio/task-ai:<version>`
(`service-dispatcher/src/main/resources/application.properties:87-93`). What ties the two together is the
contract, not the tag: the eleven params above reach the image as `PARAM_<NAME>` environment variables and the
six results come back through `RESULTS_PATH`, and that contract is shared between the image and the seeded `ai`
catalogue revision in this repository — a param or result added on one side has to land on the other.

**Network zone.** A dispatcher registered with `taskTypes=[ai]` receives only `ai` tasks
(`DispatcherService.java:212-271`, `TaskRunService.findClaimable`), so the AI zone is a second dispatcher
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

## Not built

A pass-by-reference artefact store for payloads above the caps is designed but deferred (trigger conditions in
boomerang-io/flow#319); a local Docker runtime and a serverless-container (sandbox) dispatcher are planned executors.

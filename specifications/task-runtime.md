# Task runtime

A task runs when the engine in `service-core` admits it to the claim-based queue, a `service-dispatcher`
instance claims it over HTTP, and a `TaskExecutor` implementation runs the task's image on Kubernetes and
reports the results back. Only `template`, `custom`, `script` and `generic` tasks go to a dispatcher
(`engine/TaskExecutionService.java:189-194`); every other type runs inside the engine. The shipped dispatcher
registers `template`, `custom` and `script` (`flow.dispatcher.task-types`; `dispatcher/QueueService.java:72-76`),
so a `generic` task waits in the queue until a dispatcher registers that type.

## Dispatcher protocol

The dispatcher registers once, polls two queues every 5 seconds, sends one lease heartbeat every 30 seconds, and calls four lifecycle routes.

| Route (`/api/v1/dispatcher`, `dispatcher/DispatcherControllerV1.java:41-159`) | Direction | Payload |
| --- | --- | --- |
| `POST /register` | dispatcher → engine | `name`, `host`, `version`, `taskTypes`; upserted on name+host, returns the dispatcher id (`DispatcherService.java:70-96`) |
| `GET /{id}/workflows` | poll, 5 s (`client/EngineClient.java:25`) | 200 = WorkflowRuns that declare workspaces, claimed by this call for provisioning or teardown; 204 = none (`DispatcherService.java:110-145`) |
| `GET /{id}/tasks` | poll, 5 s | 200 = TaskRuns claimed for execution or termination, filtered by the registered types (`DispatcherService.java:207-221`) |
| `PUT /workflowrun/{id}/start`, `/finalize` | dispatcher → engine | Called after workspaces are provisioned, and after run-scoped storage is deleted (`QueueService.java:47-58`) |
| `PUT /taskrun/{id}/start`, `/end` | dispatcher → engine | `end` carries `status`, `statusReason`, `statusMessage`, `results` (`QueueService.java`, `endFailed`); any executor exception ends the task `failed` with a typed `statusReason` from the closed set on `TaskRunEndRequest` (`error/TaskExecutionException.java`) and the results the task wrote before it failed |
| `PUT /{id}/heartbeat` | dispatcher → engine, every `flow.dispatcher.lease.beat-ms` (30 s) | `ids` of the task runs whose executor threads stamped `LeaseRegistry` since the last beat (`dispatcher/LeaseHeartbeat.java`); the engine renews `claim.leaseExpiresAt` for the ids this dispatcher owns (`DispatcherService.heartbeat`, `flow.dispatcher.lease-ms` 90 s) |

Claims are compare-and-set per document, so two dispatchers never receive the same run
(`DispatcherService.java:202-210`). A TaskRun arriving in phase `completed` with status `cancelled` or `timedout`
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
image (`dispatcher/TaskService.java:60-62`), defaults the timeout to `kube.task.timeout` (60 minutes, `:43-45`),
runs `create` then `watch`, and deletes the runtime object per `kube.task.deletion` (`Never` default,
`OnSuccess`, `Always` — `:64-68,93,109`). `dispatcher.executor` picks one implementation:

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
marker, and values are filtered on the way up only. The workspace-scoped `get` and `query` reads blank
password-typed params by name and scrub their resolved values from task params, spec fields and results
(`workflow/WorkflowRunService.java:145-149,160-170,209`), and the task log stream is wrapped in
`FilterValuesOutputStream`, a line-buffered scrub of the same values (`:339-346`;
`lib-common/.../FilterValuesOutputStream.java:21`). The dispatcher ends the stream when the pod is already
finished or as soon as it finishes (`kube/KubeLogService.java:24`), and the engine permits the
asynchronous completion of a streamed response without re-running authorization on it
(`core/security/SecurityConfiguration.java:81`, `SecurityInterceptor.java:45`). Engine and dispatcher reads, and delivery into the
container, carry the real values.

## Volumes and workspaces

Every task gets `/data`, a per-pod `emptyDir` (RAM-backed when `kube.task.storage.data.memory=true` and the
task param `worker.storage.data.memory` is set; `KubeJobsExecutor.java:208-227`, `TektonServiceImpl.java:301`).
Shared storage is a workflow-level opt-in with two types (`StorageType.java:12-13`), each a persistent
volume claim (PVC) bound at `/workspace/<type>` or the task's declared `mountPath`
(`KubeJobsExecutor.java:245-267`; `TektonServiceImpl.java:259,283`). A task mounts only the workspaces it
declares: `DAGUtility` copies the node's `workspaces` onto the TaskRun (`engine/DAGUtility.java:214`) and the
executor mounts by type. A `workflow` PVC is keyed by `workflowRef`, created at the first run's start if absent
and never deleted by a run; a `workflowrun` PVC is keyed by the run id, created at start and deleted at finalize
(`dispatcher/WorkflowService.java:41-60,88-100`). The authored spec (`size`, `accessMode`, `className`,
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
(`config/KubeClientConfig.java:21,40`). Resource requests and limits are not applied by either executor.

## Task catalogue

Catalogue tasks are built from the `boomerang-io/tasks` monorepo into the `boomerangio/task-flow` image
(`service-loader/.../migration/_0039__RepointWorkerFlowImages.java:21-22,54`). The loader seeds 87 tasks and
their revisions from `seed/tasks.json` and `seed/task-revisions.json` into `tasks` and `task_revisions`,
inserting only what is absent (`_0022__SeedTaskCatalogue.java:88-130`). A `template` or `script` task without
an explicit image inherits the run's `boomerang.io/task-default-image` value (`DAGUtility.java:212-218`).
The engine-handled `run-workflow` and `run-scheduled-workflow` entries declare the params the engine reads
(`workflowRef`; plus `futureIn`, `futurePeriod`, `timezone`, `time`), added to an existing catalogue by
`_0040__DeclareRunWorkflowParams`.

## Task types handled inside the engine

`TaskType` (`lib-common/.../enums/TaskType.java:15-32`) is dispatched in `TaskExecutionService.java:320-373`.

| Type | Behaviour |
| --- | --- |
| `start`, `end` | Structural nodes of the graph; never executed |
| `template`, `custom`, `script`, `generic` | Wait for a dispatcher |
| `decision` | Evaluates the branch and ends `succeeded` |
| `acquirelock`, `releaselock` | Take or release a row in the `task_locks` collection; acquire parks as waiting until the lock is free |
| `runworkflow`, `runscheduledworkflow` | Start another workflow now or on a schedule, then end |
| `setwfstatus`, `setwfproperty` | Write the run's status message or a workflow-scoped param, then end |
| `approval`, `manual` | Create an action and wait for a person |
| `eventwait` | Wait for a matching inbound event unless pre-approved |
| `sleep` | Park as waiting; the watcher completes it after the duration |

## Not built

A pass-by-reference artefact store for payloads above the caps is designed but deferred (trigger conditions in
boomerang-io/flow#319); a local Docker runtime and a serverless-container (sandbox) dispatcher are planned executors.

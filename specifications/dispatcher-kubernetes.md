# Flow's Kubernetes dispatcher

`service-dispatcher` is Flow's own dispatcher: the dispatcher SDK's runtime (`dispatcher-contract.md`, "SDK")
with two Kubernetes handlers, which claims tasks over HTTP and runs each one's image on Kubernetes through a
`TaskExecutor` implementation. What a task is to any dispatcher — which types reach one, the spec and params it
receives, the caps, the catalogue — is in `dispatcher-contract.md`; how a parameter value gets there is in
`parameters.md`. This document is the part that is this dispatcher's own: image, command, volumes, resources,
placement. The shipped dispatcher registers `template`, `custom`, `script`, `ai`, `uploadartifact` and
`downloadartifact` (`flow.dispatcher.task-types`, `service-dispatcher/src/main/resources/application.properties:100`),
so a `generic` task waits in the queue until a dispatcher registers that type. To give AI tasks their own network
zone, remove `ai` from the general dispatcher's list and run a second dispatcher deployment with
`flow.dispatcher.task-types=ai`. Paths below are under `service-dispatcher/src/main/java/io/boomerang/` unless they
start with a module name.

## Flow's dispatcher

`TaskService` runs each task through a `TaskExecutor` (`dispatcher/TaskService.java:69-85`), and `WorkflowService`
provisions each claimed workflow run's volumes (`dispatcher/WorkflowService.java:36`), wired in
`config/DispatcherConfig.java:23-52`. SDK paths below are under `dispatcher-sdk/.../dispatcher/sdk/`.

- **Reconnect.** Each queue polls again at once after a poll that brought runs or was held for its window, and
  waits until 5 s after the start of one that failed or was answered empty at once (`QueuePoller.java:19,68-87`).
  A newly ready task is claimed within about 1 s while a dispatcher for its type has a free slot.
- **Slots.** It holds at most `flow.dispatcher.task.max-in-flight` tasks (25; 0 is no cap). A task is in flight from
  its claim - taken on the poll's thread, so the next poll counts it - until the engine has taken its end, a
  Pending pod and a retried end included; each task poll sends `limit` = the free slots (`InFlightTasks.java:28`,
  `TaskRunner.java:89-93`, `Dispatcher.java:246`). It sends no filters; termination orders take no slot.
- **Lease.** One heartbeat every `flow.dispatcher.lease.beat-ms` (30 s) lists every task in flight, so a lease is
  renewed for as long as its handler runs and its end is pending (`Dispatcher.java:176-186`).
- **Start answer.** It runs nothing when start answers `completed` or a 4xx, and goes ahead when the engine cannot
  be reached (`TaskRunner.java:157-177`). A claim for a type it did not register is skipped (`:90`).
- **Termination.** A terminate order signals the running task and calls `TaskService.cancel`, which cancels the
  runtime object, and reports nothing, not even when there is nothing left to cancel (`TaskRunner.java:208-220`).
- **End.** Any executor exception ends the task `failed` with a typed `statusReason`
  (`error/TaskExecutionException.java`, `dispatcher/TaskService.java:74-78`) and the results the task wrote before
  it failed; "Starting a task" below lists the reasons for a task that never started. The `statusMessage` is the
  Job's or TaskRun's condition first, then the task container's exit code and termination reason and the last 20
  lines of its log, capped at 4 KB, while its pod can still be read (`kube/KubeHelperService.java`
  `failureDetail`); the container's termination log is the results channel, so an error never travels there. The end is retried with
  backoff, 1 s doubling to 30 s, until the engine takes it or refuses it with a 4xx (`TaskRunner.java:180-206`).
- **Registration and stop.** Registration is retried the same way until the engine answers
  (`Dispatcher.java:189-224`). Stopping ends the polls and waits 10 s for the tasks in flight; one still running
  then reports nothing, so its lease lapses, the engine hands it out again, and `create` adopts its runtime
  object (`Dispatcher.java:119-148`).
- **Storage.** It provisions a claimed run's volumes before `workflowrun/{id}/start` (`WorkflowRunner.java`) and
  lists the volumes it holds from the cluster to ask which may go (`dispatcher/WorkspaceReconciler.java`).
- **Engine and token.** It calls `flow.engine.url` (`http://${flow.engine.service.host}`) through `RestConfig`'s
  pooled client and sends `flow.engine.dispatcher.token` as `Authorization: Bearer` on engine calls only
  (`config/DispatcherConfig.java:23-32`, `application.properties:94-95`); what the engine accepts is in the contract.
- **Logs.** It serves `/api/v1/logs[/stream]` (`dispatcher/LogV1Controller.java:14-30`) to the engine alone: the
  route accepts only the bearer generated at every start and registered as `logsToken`
  (`dispatcher/LogAuthFilter.java`, `config/DispatcherConfig.java`). `flow.dispatcher.logs.url` is the address the
  engine reads it at, registered as `logsUrl`; blank registers none, and a running task then has no log to follow.
  With `flow.dispatcher.logs.upload` (on) a finished task's pod log is read before the
  runtime object is deleted and stored with the engine, where it is served first and outlives the pod
  (`dispatcher/TaskService.java`, `storeLog`; `dispatcher-contract.md`, "Logs").

## Executors

`TaskExecutor` has four methods — `create`, `watch`, `cancel`, `delete`
(`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-27`). `TaskService` requires an image
(`dispatcher/TaskService.java:95-97`), falls back to `kube.task.timeout` (60 minutes, `:65-67`) whenever the
TaskRun carries no timeout or 0, runs `create` then `watch`, and deletes the runtime object per the TaskRun's
`spec.deletion` (`:61-63,102-105,129-131`). The engine always sets it from the admin "Deletion Policy"
(`task`/`deletion.policy`: `Never` default, `OnSuccess`, `Always`; `workflow/WorkflowService.java:691-694`,
`engine/DAGUtility.java:293-301`), so the fallback `kube.task.deletion` never applies. Logs are read from the pod
(`kube/KubeLogService.java:54-63`) and go with it. The delete runs off the dispatch thread, through a self proxy to
an `@Async` method, after a one-second grace (`:39,142-151`).

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
logic, and re-opens the watch if it was closed (`KubeJobsExecutor.java`, `watch`;
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
| Create refused by a namespace quota (403 "exceeded quota") | `ExceededQuota` (`dispatcher/TaskService.java:111`) | the engine requeues it |
| Create refused by an admission webhook | `AdmissionDenied` (`:117`) | the task fails |
| A container held on an image it cannot pull (`ErrImagePull`, `ImagePullBackOff`, `InvalidImageName`, `ImageInspectError`) for `kube.timeout.startFailureGraceSeconds` (60) | `ImagePull` | the runtime object is deleted; the task fails |
| A container that cannot be created (`CreateContainerConfigError`, `CreateContainerError`) for the same grace | `DispatchError` | the runtime object is deleted; the task fails |
| The pod still Pending - unschedulable, held by a quota, never created - `kube.timeout.startMinutes` (15; 0 is off) after the watch began | `StartTimeout` | the runtime object is deleted; the engine requeues it |

Each reconcile tick reads the task's pod - init containers included, and Tekton's `step-task` container as well
as the Jobs executor's `task` - until the pod leaves Pending (`kube/KubeHelperService.java:480-523`,
`executor/PodStartWatch.java`, `KubeJobsExecutor.java:439`, `TektonServiceImpl.java:540`). The engine requeues
`ExceededQuota` and `StartTimeout` (and `RateLimited`, which this dispatcher never sends) on the same budget as a
timed-out claimant: three more attempts with backoff, then the task fails with that reason
(`engine/TaskRunService.java:1059`). Kubernetes 429, 5xx and network errors
are retried inside the Kubernetes client before any of this applies.

## What the container receives

The engine has filled in every reference before the task reaches the dispatcher: params at admission, the spec on
the claim (`parameters.md`; `dispatcher-contract.md`, "What a poll hands out"). The dispatcher then sets these
environment variables (`kube/KubeHelperService.java:111-149`):

| Variable | Value |
| --- | --- |
| `PARAM_<NAME>` | One per param; the name upper-cased with any character outside `[A-Za-z0-9_]` replaced by `_` (`kube/KubeHelperService.java:182`); non-string values JSON-encoded (`service-dispatcher/README.md`) |
| `PARAM_NAMES` | The original names, comma-separated, so a library can map `PARAM_PRIVATEKEY` back to `privateKey` |
| `RESULTS_PATH` | `/tekton/results` (a directory, one file per result) on Tekton; `/dev/termination-log` (one file) on Jobs |
| `RESULTS_MAX_BYTES` | The most bytes the task may write to `RESULTS_PATH`, set by the executor that runs it: `4096` on Jobs (the termination message cap, `KubeJobsExecutor.TERMINATION_MESSAGE_MAX_BYTES`); `tekton.results.maxBytes` on Tekton (default `4096`, raised to the cluster's `max-result-size` under sidecar-logs). A task budgets against it rather than a constant of its own |
| `DEBUG`, `CI=true`, `FLOW_VERSION`, proxy vars | Debug flag, CI marker, the dispatcher's `flow.version`, and the `HTTP_PROXY` family when `proxy.enable=true` |

Explicitly declared task env vars win on a name collision (`KubeHelperService.java:145-147`). There is no
`/params` file directory and no `PARAMS` JSON variable; large inputs belong on a workspace mount, with the
param carrying the path. An item of a for-each task carries `item` and `index` like any other param
(`dispatcher-contract.md`, "What a poll hands out"), so the container sees `PARAM_ITEM` and `PARAM_INDEX`. A
secret-typed param is built into `PARAM_<NAME>` exactly as a string; there is no per-task Kubernetes Secret yet
(decision 0115).

## Results on Kubernetes

The results limit belongs to the dispatcher that runs the task (`dispatcher-contract.md`, "Ending"): on Kubernetes
it is the 4096-byte container termination message, enforced here. An oversize payload never reaches the engine,
because Kubernetes truncates a container termination message at 4096 bytes and the truncated prefix is broken JSON.
`TerminationMessageParser` reports an unparseable message as absent rather than as "no results", and
`KubeJobsExecutor.readResults` fails the task with `ResultsTooLarge` when the pod log carries Kubernetes' own
too-large line or when the unparseable message is at the ceiling; a short unparseable message is a task writing
something that is not a results payload, so it is logged and carries no results. On Tekton the overflow fails the
TaskRun itself and is mapped the same way (`TektonServiceImpl.java:606`). A task reports only the result names its
definition declares (`TerminationMessageParser.java:24-27,72-73`).

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

## Volumes and workspaces

Every task gets `/data`, a per-pod `emptyDir` (RAM-backed when `kube.task.storage.data.memory=true` and the task
param `worker.storage.data.memory` is set; `KubeJobsExecutor.java:254-268`, `TektonServiceImpl.java:253`). Shared
storage is one workspace per run, declared on the workflow and copied onto the run and each TaskRun with its
`mountPath` settled (`dispatcher-contract.md`, "Storage"). The dispatcher provisions one persistent volume claim
(PVC) keyed by the run id, labelled `boomerang.io/tier=workspace` and `boomerang.io/workspace-ref=<run id>`, before
the run starts (`dispatcher/WorkflowService.java:42-57`), and deletes it when its reconciliation finds the run
completed (`dispatcher/WorkspaceReconciler.java:58-65`). No volume is shared between runs: files that must survive
a run are artifacts with scope `workflow` ("Artifacts" in `data-model.md`). Both executors mount that one claim at
the TaskRun's path (`KubeJobsExecutor.java:278-299`; `TektonServiceImpl.java:212-217`, one declaration and one
binding named `workspace`; the fallback is `KubeHelperService.workspaceMountPath` `:546`). Finished task pods still
hold the claim, so under `Never` it stays `Terminating` until retention removes them.

`size` is a Kubernetes quantity (`1Gi`, `500Mi`); a bare number means Gi and is written as `<n>Gi` wherever a workspace enters the engine (`service-core/.../model/WorkflowWorkspace.java` `settle`), because a Kubernetes Quantity would read it as bytes. On save, create and update alike, a blank
size takes the `max.workspace.storage` quota value and a blank mount path becomes `/workspace`; a size above the
quota is refused with `QUOTA_EXCEEDED` when quotas are enforced, and the same check runs when a run is submitted
with its own workspace (`workflow/WorkflowService.java:499-515,956-990`,
`service-core/.../util/StorageQuantityUtil.java:13`). Class and access mode default to `kube.workspace.storage.*`
(1Gi, `ReadWriteMany`; `dispatcher/WorkspaceService.java:59-61`); a blank class leaves `storageClassName` unset so
the cluster default applies, because an empty string disables dynamic provisioning (`KubeServiceImpl.java:162`).
After creating a claim the dispatcher waits up to `kube.timeout.waitUntil` (30 s) for it to reach `Pending` or
`Bound`, treating a momentarily absent claim as not yet settled (`KubeServiceImpl.isClaimSettled` `:202`); any
error while creating it surfaces as one provisioning failure (`dispatcher/WorkspaceService.java:63-75`).

A workflow upgraded from v3 keeps its mount path, written explicitly as `/workspace/workflowrun` when v3 had none
(`migration/_0010__V3MigrateWorkflows.java:225`), so its tasks need no change. One that declared v3's Workflow
storage, the volume keyed by the workflow, carries `legacyWorkflowStorage` on its revision and is refused at submit
with `WORKFLOW_STORAGE_REMOVED` until it is saved again (`workflow/WorkflowService.java:1951`).

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

An `ai` revision declares its image and command (`dispatcher-contract.md`, "AI tasks"), and this dispatcher runs
them exactly as it does for `custom` or `template` (a revision with an image and no command runs the image's own
entrypoint; one with no image is refused as `NO_TASK_IMAGE`; `executor/TaskImageResolver.java:91-98`,
`dispatcher/TaskService.java:118-130`). There is no worker-image property: every `ai` revision declares its image.
That image MUST start with one of `flow.dispatcher.ai.image-prefixes` (comma-separated, default
`boomerangio/task-ai`, which admits every tag and digest of the Boomerang worker); the dispatcher refuses any other
`ai` task before it runs, never-started with `statusReason=ImageNotAllowed` and a message naming the image and the
property (`TaskImageResolver.allowedAiImage` `:60-66`), so a dispatcher registered for `ai` stays the egress zone
for the worker alone unless an operator adds a prefix. An `ai` task never runs a script.

**Credential defaults.** A dispatcher may carry the endpoint and token itself: `flow.dispatcher.ai.endpoint` and
`flow.dispatcher.ai.token` (both blank by default) reach every `ai` pod as `OPENAI_BASE_URL` and `OPENAI_API_KEY`,
each only when set, ahead of the task's own environment (`TaskImageResolver.java:76-88`); the task's own `endpoint`
and `token` params still arrive as `PARAM_ENDPOINT` and `PARAM_TOKEN` and win inside the image.

**Network zone.** A dispatcher registered with `taskTypes=[ai]` receives only `ai` tasks
(`DispatcherService.java:260-340`, `TaskRunService.findClaimable`), so the AI zone is a second dispatcher
deployment — its own namespace, egress policy and `runtimeClassName` — exactly as decision 0042 frames
isolation tiers. No configuration separates zones inside one dispatcher.

## Not built

An Azure Blob artifact store waits for a serverless-container dispatcher. The planned executors, and which of them
are separate processes, are in `dispatcher-contract.md` ("Topologies").

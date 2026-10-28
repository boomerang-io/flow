# Flow's Kubernetes dispatcher

`service-dispatcher` is Flow's own dispatcher: the runtime of the dispatcher software development kit (SDK;
`dispatcher-contract.md`, "SDK") with two Kubernetes handlers, which claims tasks over HTTP and runs each one's image
on Kubernetes through a `TaskExecutor`.
What a task is to any dispatcher (which types reach one, the spec and params it receives, the caps, the catalogue) is
in `dispatcher-contract.md`; how a parameter value gets there is in `parameters.md`. This document is the part that is
this dispatcher's own: image, command, volumes, resources, placement. The shipped dispatcher registers `template`,
`custom`, `script`, `ai`, `uploadartifact` and `downloadartifact` (`flow.dispatcher.task-types`,
`service-dispatcher/src/main/resources/application.properties:92`), so a `generic` task waits in the queue until a
dispatcher registers that type. To give AI tasks their own network zone, remove `ai` from the general dispatcher's
list and run a second dispatcher deployment with `flow.dispatcher.task-types=ai`. Paths below are under
`service-dispatcher/src/main/java/io/boomerang/` unless they start with a module name; `sdk/` is short for
`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/`. Properties and their defaults are in
`service-dispatcher/src/main/resources/application.properties`.

## Flow's dispatcher

`TaskService` is the task handler and runs each task through a `TaskExecutor` (`dispatcher/TaskService.java:117-173`);
`WorkflowService` is the workflow handler and provisions each claimed run's volume
(`dispatcher/WorkflowService.java:36-56`); both are wired in `config/DispatcherConfig.java:40-59`.

- **Reconnect.** Each queue polls again at once after a poll that brought runs or was held for its window, and waits
  until 5 s after the start of one that failed or was answered empty at once (`sdk/QueuePoller.java:23`,
  `:104-123`). A newly ready task is claimed within about 1 s while a dispatcher for its type has a free slot.
- **Slots.** It holds at most `flow.dispatcher.task.max-in-flight` tasks (25; 0 is no cap). A task is in flight from
  its claim, taken on the poll's thread so the next poll counts it, until the engine has taken its end, a Pending pod
  and a retried end included; each task poll sends `limit` = the free slots (`sdk/InFlightTasks.java:30-33`,
  `sdk/TaskRunner.java:94-100`, `sdk/Dispatcher.java:265`). It sends no filters; termination orders take no slot.
  While every slot is taken a `limit=0` poll keeps termination orders coming, and a slot that frees starts a work
  poll at once beside it (`sdk/Dispatcher.java:259-282`).
- **Lease.** One heartbeat every `flow.dispatcher.lease.beat-ms` (30 s) lists every task in flight, from its claim
  until its end is taken, so the lease the claim set is renewed for as long as its handler runs and its end is
  pending (`sdk/Dispatcher.java:189-200`, `:291-299`). `0` sends no heartbeat, and the engine then requeues every
  claim once its first lease lapses.
- **Start answer.** It runs nothing when start answers `completed` or a 4xx, and goes ahead when the engine cannot be
  reached (`sdk/TaskRunner.java:162-183`). A claim for a type it did not register is skipped (`:94-108`).
- **Termination.** A terminate order signals the running task and calls `TaskService.cancel`, which cancels the
  runtime object, and reports nothing, not even when there is nothing left to cancel (`sdk/TaskRunner.java:214-226`,
  `dispatcher/TaskService.java:100-104`).
- **End.** Any executor exception ends the task `failed` with a typed `statusReason` and the results the task wrote
  before it failed (`error/TaskExecutionException.java:28-33`, `dispatcher/TaskService.java:88-98`); "Starting a
  task" below lists the reasons for a task that never started. The `statusMessage` is the Job's or TaskRun's
  condition first, then the task container's exit code and termination reason and the last 20 lines of its log, at
  most 4096 bytes, while its pod can still be read (`kube/KubeHelperService.java:393-470`). The container's
  termination message is the results channel, so an error never travels there. The end is retried with backoff, 1 s
  doubling to 30 s, until the engine takes it or refuses it with a 4xx other than 408 or 429
  (`sdk/TaskRunner.java:185-212`, `sdk/Backoff.java:8`).
- **Registration and stop.** Registration is retried the same way until the engine answers
  (`sdk/Dispatcher.java:202-238`). Stopping ends the polls and waits 10 s for the tasks in flight; one still running
  then reports nothing, so its lease lapses and the engine hands it out again (`sdk/Dispatcher.java:120-154`). Both
  executors then adopt the object still running under the task's labels rather than create a second one
  (`kube/KubeJobsExecutor.java:179-196`, `kube/TektonServiceImpl.java:219-232`).
- **Storage.** It provisions a claimed run's volume before `workflowrun/{id}/start` (`sdk/WorkflowRunner.java:38-49`),
  and every `flow.dispatcher.workspace.reconcile-ms` (60 s) lists the volumes it holds from the cluster and asks which
  may go (`dispatcher/WorkspaceReconciler.java:57-72`).
- **Engine and token.** It calls `flow.engine.url` (`http://${flow.engine.service.host}`) through `RestConfig`'s
  pooled client, whose 60 s read outlasts the 30 s long poll, and sends `flow.engine.dispatcher.token` as
  `Authorization: Bearer` on engine calls only (`config/DispatcherConfig.java:22-32`;
  `service-dispatcher/src/main/resources/application.properties:86-87`, `:143`); what the engine accepts is in the
  contract.
- **Logs.** It serves `/api/v1/logs/stream` (`dispatcher/LogV1Controller.java:13-31`) to the engine alone: the route
  accepts only the bearer generated at every start and registered as `logsToken` (`dispatcher/LogAuthFilter.java:52-64`,
  `config/DispatcherConfig.java:57`). `flow.dispatcher.logs.url` is the address the engine reads it at, registered as
  `logsUrl`; blank registers none, and a running task then has no log to follow. With `dispatcher.logging.type=default`
  the route reads the task's pod; `loki` queries Loki at `dispatcher.logging.host` and `port` instead
  (`dispatcher/LogService.java:46-67`). The pod is the one the executor's runtime object owns, selected by the label
  that object stamps on it, which the executor keeps from `create` until the stored log has been read
  (`executor/TaskExecutor.java:29-40`, `dispatcher/TaskService.java:164-165`, `:185-190`); a task this instance is not running,
  or one that started before it last restarted, falls back to the first pod under the task labels
  (`kube/KubeLogService.java:58-95`, `:97-116`). A running pod's log is followed and every read is written and flushed at once
  (`kube/KubeLogService.java:122-144`, `:273-283`); the route flushes the servlet response, because Spring 7 makes the
  body stream's own flush a no-op (`dispatcher/ResponseFlushingOutputStream.java:16-38`). A Pending pod is answered
  with one "Waiting for the task's container to start" line and followed once it starts, or told it has not started
  after five minutes (`kube/KubeLogService.java:146-181`); a finished pod is read once (`:190-200`). With
  `flow.dispatcher.logs.upload` (on) a finished task's pod log is read before the runtime object is deleted and stored
  with the engine, where it is served first and outlives the pod (`dispatcher/TaskService.java:162-194`;
  `dispatcher-contract.md`, "Logs").

## Executors

`TaskExecutor` has six methods: `create`, `watch`, `cancel`, `delete`, `runtimePodSelector`, `forgetRuntimeObject` (`executor/TaskExecutor.java:12-40`).
`TaskService` requires an image (`dispatcher/TaskService.java:119-126`), falls back to `kube.task.timeout` (60
minutes) whenever the task run carries no timeout or 0 (`:84-86`), runs `create` then `watch` (`:134-138`), and
deletes the runtime object per the task run's `spec.deletion` (`:80-82`, `:165-169`). The engine always sets it from
the admin "Deletion Policy" (`task`/`deletion.policy`: `Never`, the default, `OnSuccess` or `Always`;
`service-core/src/main/java/io/boomerang/engine/DAGUtility.java:74-85`, `:360`), so the fallback `kube.task.deletion`
never applies. The delete runs off the dispatch thread, through a self proxy to an `@Async` method, after a one-second
grace (`dispatcher/TaskService.java:43`, `:63-65`, `:195-204`).

Whatever the deletion policy says, a finished runtime object is removed `kube.task.ttlDays` (7) days after
completion, so retention means the same on both executors. The Jobs executor stamps `ttlSecondsAfterFinished` and
Kubernetes collects the Job (`kube/KubeJobsExecutor.java:256`). Tekton ships no such controller, so
`dispatcher/TaskRuntimeReconciler.java` sweeps instead: on the `tekton` executor, every
`flow.dispatcher.task.reconcile-ms` (hourly; off with `flow.dispatcher.task.reconcile.enabled=false`) a tick lists
the TaskRuns carrying this dispatcher's own product and tier labels (`kube/KubeHelperService.java:333-335`), deletes
those whose first condition is terminal and whose `completionTime` is more than `kube.task.ttlDays` old
(`dispatcher/TaskRuntimeReconciler.java:96-107`), and logs one `held/deleted` summary; a failed tick warns and the
next tick asks again.

The timeout the task run arrives with is settled by the engine, not here: it is the smallest of the platform setting,
the task's own declared timeout, and the run's timeout, with 0 meaning unguarded
(`service-core/src/main/java/io/boomerang/engine/DAGUtility.java:288-309`). The seed ships the platform setting
(`task`/`default.timeout`) at 0, so by default a task inherits its run's timeout and the setting is an optional
operator ceiling for capping one pod inside a long-running graph; a task run that reaches the dispatcher unguarded
still gets `kube.task.timeout` as a per-pod backstop.

`dispatcher.executor` picks one implementation:

| `dispatcher.executor` | Class | Runtime object | Timeout | Results channel | Cancel |
| --- | --- | --- | --- | --- | --- |
| `tekton` (default) | `kube/TektonServiceImpl.java:56-58` | One Tekton v1 `TaskRun` with an inline `taskSpec` and a single step named `task` (`kube/TektonServiceImpl.java:381-398`, `:438-472`) | `spec.timeout` in minutes (`kube/TektonServiceImpl.java:427`, `:462`) | `status.results` (`kube/TektonServiceImpl.java:559-564`, `:583-593`); a 4096-byte overflow is detected from the last line of the pod log (`kube/KubeServiceImpl.java:237-261`) | Overwrite the status condition with `TaskRunCancelled` (`kube/TektonServiceImpl.java:648-689`) |
| `kube-jobs` | `kube/KubeJobsExecutor.java:71-73` | One `batch/v1` `Job` (`kube/KubeJobsExecutor.java:254-270`); `restartPolicy`, `backoffLimit` and `ttlSecondsAfterFinished` from `kube.task.*` (`kube/KubeJobsExecutor.java:209`, `:230-231`) | `activeDeadlineSeconds` = minutes × 60 (`kube/KubeJobsExecutor.java:232`) | Termination message at `/dev/termination-log`, a JSON object or Tekton's `[{key,value}]` array (`kube/KubeJobsExecutor.java:198`, `:205-206`; `executor/TerminationMessageParser.java:35-62`) | Delete the Job and its script ConfigMap (`kube/KubeJobsExecutor.java:534-556`) |

Both executors hold one thread per task in a reconcile loop: a label-selector watch is the fast path, and every
`kube.timeout.reconcileSeconds` (30) the loop re-reads the object by name, applies the same terminal logic, and
re-opens the watch if it was closed (`kube/KubeJobsExecutor.java:416-481`, `kube/TektonServiceImpl.java:495-577`); it
gives up at the timeout plus `kube.timeout.watchGraceMinutes` (2). A Job whose pod count reports a failure before its
`Failed` condition exists is held until the condition arrives, so a deadline kill is reported as `DeadlineExceeded`
rather than `JobFailed`; after `kube.timeout.failedConditionGraceSeconds` (60) without a condition it is reported as
`JobFailed` (`executor/JobWatcher.java:14-22`). Every attempt of a task carries the same labels, so `create` adopts
an object under them only while it is still running — a Job with no true `Complete` or `Failed` condition
(`kube/KubeJobsExecutor.java:179-196`, `:281-291`), a TaskRun whose `Succeeded` condition is absent or `Unknown`
(`kube/TektonServiceImpl.java:219-232`, `:487-492`) — and otherwise creates a new one beside the finished attempt.
Each watch is bound, by name, to the object its attempt created or adopted: events and the reconcile for any other
Job or TaskRun are ignored (`executor/JobWatcher.java:29-31`, `kube/KubeJobsExecutor.java:454-460`;
`kube/TaskWatcher.java:19-21`, `kube/TektonServiceImpl.java:535-541`). The pod read for the start check, the failure
reason and detail, and the termination message is that object's own, selected by the label its controller stamps on
it — `job-name` on a Job's pod, `tekton.dev/taskRun` on a TaskRun's (`kube/KubeJobsExecutor.java:159-161`, `:430`,
`kube/TektonServiceImpl.java:91-94`, `:510`) — never another attempt's pod under the same task labels. A Tekton cancel
marks every TaskRun still running under the labels (`kube/TektonServiceImpl.java:662-679`). The Jobs executor
mounts a `script` task's body from a per-task ConfigMap at
`/scripts/script`, which MUST start with a shebang (`kube/KubeJobsExecutor.java:347-387`); Tekton runs it as the
step's `script` (`kube/TektonServiceImpl.java:385-389`).

**Starting a task.** Starting is bounded apart from running, and a task that never started is handed back rather
than failed:

| Situation | Reported as | Then |
| --- | --- | --- |
| Create refused by a namespace quota (403 "exceeded quota") | `ExceededQuota` (`dispatcher/TaskService.java:143-146`) | the engine requeues it |
| Create refused by an admission webhook | `AdmissionDenied` (`dispatcher/TaskService.java:149-151`) | the task fails |
| A container held on an image it cannot pull (`ErrImagePull`, `ImagePullBackOff`, `InvalidImageName`, `ImageInspectError`) for `kube.timeout.startFailureGraceSeconds` (60) | `ImagePull` (`executor/PodStartWatch.java:19-20`, `:48-57`) | the runtime object is deleted; the task fails |
| A container that cannot be created (`CreateContainerConfigError`, `CreateContainerError`) for the same grace | `DispatchError` (`executor/PodStartWatch.java:22-23`) | the runtime object is deleted; the task fails |
| The pod still Pending (unschedulable, held by a quota, never created) `kube.timeout.startMinutes` (15; 0 is off) after the watch began | `StartTimeout` (`executor/PodStartWatch.java:61-68`) | the runtime object is deleted; the engine requeues it |

Each reconcile tick reads the task's pod, every container and init container, until the pod leaves Pending
(`kube/KubeHelperService.java:482-516`, `kube/KubeJobsExecutor.java:466-480`, `kube/TektonServiceImpl.java:546-556`).
The engine requeues `ExceededQuota` and `StartTimeout` (and `RateLimited`, which this dispatcher never sends) on the
budget the contract gives (`dispatcher-contract.md`, "Ending").

## What the container receives

The engine has filled in every reference before the task reaches the dispatcher: params at admission, the spec on
the claim (`parameters.md`; `dispatcher-contract.md`, "What a poll hands out"). The dispatcher then sets these
environment variables, a later row winning on a name collision (`kube/KubeHelperService.java:116-163`):

| Variable | Value |
| --- | --- |
| Proxy | `PROXY_HOST`, `PROXY_PORT`, `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and their lower-case forms, when `proxy.enable=true` (`kube/KubeHelperService.java:90-107`) |
| `DEBUG`, `CI`, `FLOW_VERSION` | The run's debug flag, `true`, and the dispatcher's `flow.version` |
| `RESULTS_PATH` | `/tekton/results` (a directory, one file per result) on Tekton; `/dev/termination-log` (one file) on Jobs |
| `RESULTS_MAX_BYTES` | The most bytes the task may write to `RESULTS_PATH`: `4096` on Jobs (`kube/KubeJobsExecutor.java:80`, `:225-226`); `tekton.results.maxBytes` on Tekton, default `4096`, raised to the cluster's `max-result-size` under sidecar logs (`kube/TektonServiceImpl.java:311-314`). A task budgets against it rather than a constant of its own |
| `PARAM_NAMES` | The original param names, comma-separated, so a library can map `PARAM_PRIVATEKEY` back to `privateKey` |
| `PARAM_<NAME>` | One per param; the name upper-cased with any character outside `[A-Za-z0-9_]` replaced by `_` (`kube/KubeHelperService.java:170-172`); a string value as is, null as empty, anything else JSON-encoded (`kube/KubeHelperService.java:179-187`). Two params that fold to one name fail the task (`kube/KubeHelperService.java:142-155`) |
| `TRACEPARENT` | The task's W3C trace context, derived from its run and task run ids, on every task (`dispatcher-contract.md`, "Trace context"; `executor/TaskImageResolver.java:89`) |
| The task's `spec.envs` | As declared (`executor/TaskImageResolver.java:106-108`) |

There is no `/params` file directory and no `PARAMS` JSON variable; large inputs belong on a workspace mount, with
the param carrying the path. An item of a for-each task carries `item` and `index` like any other param, so the
container sees `PARAM_ITEM` and `PARAM_INDEX`. A secret-typed param is built into `PARAM_<NAME>` exactly as a string;
there is no per-task Kubernetes Secret, so anyone who can read the pod spec can read it. On Tekton every param is also
a param of the TaskRun object (`kube/TektonServiceImpl.java:359-374`), so its value is in the TaskRun as well as the
pod.

## Results on Kubernetes

The results limit belongs to the dispatcher that runs the task (`dispatcher-contract.md`, "Ending"); here it is the
container termination message, 4096 bytes by default. An oversize payload never reaches the engine, because
Kubernetes truncates a container termination message at 4096 bytes and the truncated prefix is broken JSON.
`TerminationMessageParser` reports an unparseable message as absent rather than as "no results"
(`executor/TerminationMessageParser.java:35-62`), and the Jobs executor fails the task with `ResultsTooLarge` when the
last line of the pod log is Kubernetes' own too-large line or when the unparseable message is at the ceiling; a short
unparseable message is a task writing something that is not a results payload, so it is logged and carries no
results (`kube/KubeJobsExecutor.java:544-561`). On Tekton the overflow fails the TaskRun itself and is mapped the same
way (`kube/TektonServiceImpl.java:608-610`). When the task declares results, the Jobs executor keeps only those names
(`executor/TerminationMessageParser.java:64-73`).

## Run labels on Kubernetes objects

Run labels are user metadata and reach the dispatcher unchecked, but Kubernetes rejects an entire object when one
label breaks its rules, so `KubeHelperService` coerces every user-supplied key and value into shape before it is
merged into a TaskRun, Job or volume's labels, one place all executors share (`kube/KubeHelperService.java:239-327`).

| Part | Rule applied | Mapping |
| --- | --- | --- |
| Value, and the name half of a key | At most 63 characters of `[A-Za-z0-9._-]`, alphanumeric at both ends | Any other character becomes `_`, the string is truncated to 63, then trimmed to alphanumeric ends |
| The optional `prefix/` half of a key | A DNS subdomain: at most 253 characters of `[a-z0-9.-]`, each dot-separated part alphanumeric at both ends | Lower-cased, any other character becomes `-`, truncated to 253, each part trimmed, empty parts dropped |
| A key with no usable name | — | Dropped; nothing can be written under it |
| A key the dispatcher already set (`boomerang.io/*`, `app.kubernetes.io/*`) | — | The dispatcher's value wins; these are the selectors every lookup, watch and delete runs on |

Every alteration is logged at debug. So `team/name=platform/flow` is written as `team/name=platform_flow` rather than
failing the volume with a 422.

## Volumes and workspaces

Every task gets `/data`, a per-pod `emptyDir`, memory-backed when `kube.task.storage.data.memory=true` and the task's
`worker.storage.data.memory` param is `true` (`kube/KubeJobsExecutor.java:293-319`,
`kube/TektonServiceImpl.java:275-298`). Shared storage is one workspace per run, declared on the workflow and copied
onto the run and each task run with its `mountPath` settled (`dispatcher-contract.md`, "Storage"). The dispatcher
provisions one persistent volume claim keyed by the run id, labelled `boomerang.io/tier=workspace` and
`boomerang.io/workspace-ref=<run id>` (`kube/KubeHelperService.java:345-355`), before the run starts
(`dispatcher/WorkflowService.java:36-56`), and deletes it when its reconciliation finds the run completed
(`dispatcher/WorkspaceReconciler.java:57-72`). No volume is shared between runs, a retry and its origin included:
files that must survive a run are artifacts with scope `workflow` (`data-model.md`, "Artifacts"). Both executors mount
that one claim at the task run's path (`kube/KubeJobsExecutor.java:322-345`; `kube/TektonServiceImpl.java:248-266`,
one declaration and one binding named `workspace`), `/workspace` when it names none
(`kube/KubeHelperService.java:533-538`). Finished task pods still hold the claim, so under `Never` it stays
`Terminating` until retention removes them.

`size` is a Kubernetes quantity (`1Gi`, `500Mi`); a bare number means Gi and is written as `<n>Gi` wherever a
workspace enters the engine, because a Kubernetes quantity would read it as bytes
(`service-core/src/main/java/io/boomerang/common/model/WorkflowWorkspace.java:39-49`). On save, create and update
alike, a blank size takes the platform's `max.workspace.storage` value, lowered to the owning workspace's own storage
quota when quotas are enforced and it is smaller (0 is unlimited), and a blank mount path becomes `/workspace`; a size
above that workspace quota is refused with `QUOTA_EXCEEDED` when quotas are enforced
(`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java:468-496`), and the same check runs
when a run is submitted with its own workspace (`service-core/src/main/java/io/boomerang/workflow/QuotaService.java:357-372`). A
size, class or access mode still blank here takes `kube.workspace.storage.*` (`1Gi`, the cluster's default class,
`ReadWriteMany`; `dispatcher/WorkspaceService.java:51-58`); a blank class leaves `storageClassName` unset, because an
empty string disables dynamic provisioning (`kube/KubeServiceImpl.java:127-130`). After creating a claim the
dispatcher waits up to `kube.timeout.waitUntil` (30 s) for it to reach `Pending` or `Bound`, treating a momentarily
absent claim as not yet settled (`kube/KubeServiceImpl.java:155-172`); any error while creating it surfaces as one
provisioning failure (`dispatcher/WorkspaceService.java:59-74`).

A workflow upgraded from v3 keeps its mount path, written as `/workspace/workflowrun` when v3 had none
(`service-core/src/main/java/io/boomerang/migration/_0010__V3MigrateWorkflows.java:226`), so its tasks need no
change. One that declared v3's workflow storage, the volume keyed by the workflow, carries `legacyWorkflowStorage` on
its revision and is refused at submit with `WORKFLOW_STORAGE_REMOVED` until it is saved again
(`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java:1844-1848`).

## Isolation and placement

`dispatcher.tasks.runtimeClassName` sets the pod `runtimeClassName` (gVisor, Kata, confidential containers) for every
task the deployment runs: on the pod spec for Jobs (`kube/KubeJobsExecutor.java:244-246`) and on the TaskRun's pod
template for Tekton (`kube/TektonServiceImpl.java:448-454`). There is no per-task isolation field: a different tier is
a second dispatcher deployment with its own name and task types. Node selector, tolerations, host aliases and the
image pull secret are likewise per deployment (`dispatcher.tasks.*`, `kube.image.pullSecret`;
`kube/KubeJobsExecutor.java:237-240`, `kube/TektonServiceImpl.java:455-458`); `dispatcher.tasks.serviceaccount` is
set on Jobs only (`kube/KubeJobsExecutor.java:241-243`). Empty toleration or host-alias entries are dropped before
dispatch, so a `[]` or `[{}]` default never reaches the API server (`kube/KubeHelperService.java:189-204`). Tasks,
claims and ConfigMaps are created in `kube.namespace`, or the kubeconfig context's namespace when it is blank; the
dispatcher refuses to start when neither resolves (`config/KubeClientConfig.java:31-45`).

## Container resources

Every task container carries the requests and limits one shared resolver reads from configuration
(`executor/TaskResourceResolver.java:66-86`), so the sizing is the same on both executors: the Jobs executor sets it
on the task container (`kube/KubeJobsExecutor.java:229`) and the Tekton executor on the step's `computeResources`
(`kube/TektonServiceImpl.java:396`).

| Property | Default | Applied as |
| --- | --- | --- |
| `kube.resource.request.memory` | `2Gi` | `requests.memory` |
| `kube.resource.limit.memory` | `16Gi` | `limits.memory` |
| `kube.resource.request.ephemeral-storage` | `2Gi` | `requests.ephemeral-storage` |
| `kube.resource.limit.ephemeral-storage` | `16Gi` | `limits.ephemeral-storage` |
| `kube.resource.request.cpu` | empty | `requests.cpu` |
| `kube.resource.limit.cpu` | empty | `limits.cpu` |

Each value is a Kubernetes quantity and each tolerates being blank: blank sets that request or limit not at all,
never an empty quantity and never a zero limit, so a deployment can run with memory limits and no CPU limit. With all
six blank the container carries no resources block. Both CPU values ship blank because a CPU limit throttles a task
rather than failing it, which is a worse default than no limit; memory and ephemeral-storage ship with values because
a container without them can take a node. A memory-backed `/data` is a tmpfs, so what a task writes there counts
against the memory limit rather than against ephemeral-storage: that is how a container that would breach the
ephemeral-storage limit keeps running, and a deployment that enables it sizes memory to cover the data too.

The sizing is per dispatcher deployment, not per task, the same shape as the isolation tier: a workflow author cannot
ask for a bigger container, and a workload that needs different sizing runs a second dispatcher deployment with its
own task types. A runtime that takes a byte or CPU count rather than a quantity string, such as a Docker executor (not
built), reads the same configured values through the resolver (`memoryLimitBytes`, `cpuLimitNanos`;
`executor/TaskResourceResolver.java:88-105`); Docker has no ephemeral-storage concept, so that pair is
Kubernetes-only.

## AI tasks

An `ai` revision declares its image and command (`dispatcher-contract.md`, "AI tasks"), and this dispatcher runs them
exactly as it does for `custom` or `template`: a revision with an image and no command runs the image's own
entrypoint, and one with no image is refused as `NO_TASK_IMAGE` (`executor/TaskImageResolver.java:124-143`,
`dispatcher/TaskService.java:119-126`). There is no worker-image property: every `ai` revision declares its image.
That image MUST start with one of `flow.dispatcher.ai.image-prefixes` (comma-separated, default `boomerangio/task-ai`,
which admits every tag and digest of the Boomerang worker); the dispatcher refuses any other `ai` task before it
creates anything, failing it with `statusReason=ImageNotAllowed` and a message naming the image and the property
(`dispatcher/TaskService.java:127-133`, `executor/TaskImageResolver.java:71-77`), so a dispatcher registered for `ai`
stays the egress zone for the worker alone unless an operator adds a prefix. An `ai` task never runs a script
(`executor/TaskImageResolver.java:149-151`).

**Credential defaults.** A dispatcher may carry the endpoint and token itself: `flow.dispatcher.ai.endpoint` and
`flow.dispatcher.ai.token` (both blank by default) reach every `ai` pod as `OPENAI_BASE_URL` and `OPENAI_API_KEY`,
each only when set, ahead of the task's own environment (`executor/TaskImageResolver.java:90-92`); the task's own
`endpoint` and `token` params still arrive as `PARAM_ENDPOINT` and `PARAM_TOKEN` and win inside the image.

**OpenTelemetry.** With `flow.dispatcher.ai.otel.endpoint` set, every `ai` pod also gets
`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_HEADERS` (from `.otel.headers`, visible in the pod spec like the
token), `OTEL_EXPORTER_OTLP_PROTOCOL` (from `.otel.protocol`, default `http/protobuf`), `OTEL_SERVICE_NAME` =
`flow-task-<taskRef>`, and `OTEL_RESOURCE_ATTRIBUTES` carrying `flow.workflow.run.id`, `flow.task.run.id` and
`flow.task.name`, percent-encoded (`executor/TaskImageResolver.java:93-104`, `:118-122`); blank sends none, and a
task's own variable of the same name wins. task-ai exports GenAI-convention spans over `http/protobuf` or
`http/json`; `grpc` is unsupported and logs one warning, exporting nothing. It reads `TRACEPARENT` through the W3C
propagator, so its spans join the run's trace. Langfuse takes OTLP over HTTP only (`<host>/api/public/otel`); Tempo
listens for it on port 4318.

**Network zone.** A dispatcher registered with `taskTypes=[ai]` receives only `ai` tasks (`dispatcher-contract.md`,
"Polling"), so the AI zone is a second dispatcher deployment with its own namespace, egress policy and
`runtimeClassName`, the same shape as an isolation tier. No configuration separates zones inside one dispatcher.

## Not built

A Docker executor and the serverless and microVM dispatchers are planned only (`dispatcher-contract.md`,
"Topologies"). Isolation, sizing and placement are per deployment; nothing sets them per task, and a secret param
never becomes a Kubernetes Secret.

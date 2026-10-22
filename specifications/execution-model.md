# Execution model

How a workflow run and its task runs move from submitted to finished. Paths are under
`service-core/src/main/java/io/boomerang/` unless stated otherwise.

## Run states

Every run carries `status`, the externally visible outcome, and `phase`, where it sits in the pipeline. Both are
closed enums; pause, waiting and retry are separate fields, never status values.

| Field | Values | Source |
| --- | --- | --- |
| `RunStatus` | `notstarted`, `ready`, `running`, `waiting`, `succeeded`, `failed`, `invalid`, `skipped`, `cancelled`, `timedout` | `common/enums/RunStatus.java:6-16` |
| `RunPhase` | `queued`, `pending`, `running`, `completed` | `common/enums/RunPhase.java:11-15` |

The normal path is `notstarted/pending` → admit → `ready/pending` → claim → `ready/queued` → start →
`running/running` → end → `<terminal>/completed`. `completed` is terminal: the dispatcher releases a run's storage by
reconciling what it holds against the engine, not through a further phase (`dispatcher-contract.md`, "Storage").
Each arrow is one guarded write:

| Transition | Task run | Workflow run |
| --- | --- | --- |
| admit: writes the resolved params, secret values sealed, and the labels; the spec keeps its references | `TaskRunService.java:362` | `WorkflowRunStateHelper.java:158` |
| claim: a dispatcher takes it | `TaskRunService.java:317` | `WorkflowRunStateHelper.java:75`, only a run that declares a workspace (`WorkflowRunStateHelper.java:57-73`) |
| release a stale claim | a requeue parks it (`TaskRunService.java:735`) and a termination claim releases it (`TaskRunService.java:236`) | back to `ready/pending`, fenced on `claim.seq` (`WorkflowRunStateHelper.java:125`) |
| start: bakes `timeoutAt` from the start time | `TaskRunService.java:404` | `WorkflowRunStateHelper.java:171`; a run with a workspace already carries one from its submit (`workflow/WorkflowRunService.java:798-802`) |
| complete | `TaskRunService.java:543-595` | `WorkflowRunStateHelper.java:205` |

An end the engine decides itself (a skip, an admission refusal, an engine-run task's result, a sleep or lock resume,
a for-each parent's outcome) is a `TaskOutcome` written by the completion itself, guarded on the statuses it may end
from: `notstarted` for a skip or refusal, `running` or `waiting` otherwise (`engine/model/TaskOutcome.java:15-54`,
`engine/TaskRunService.java:573-595`). A task another caller moved meanwhile matches none and is left alone; a
timeout already recorded when `end` reads the task wins over the outcome (`engine/TaskExecutionService.java:564-586`).

## The claim-based queue

Dispatchers pull work; the engine never pushes. A dispatcher long-polls `DispatcherService`, which pages candidates
with `findClaimable` and claims each one individually (`dispatcher/DispatcherService.java:377-389`); the poll window,
filters, slot limit and reconnect timing are in `dispatcher-contract.md`, "Polling".

- `findClaimable` selects `status=ready`, `phase=pending`, `type` among the dispatcher's types, no `claim.by` and
  `retry.after` absent or elapsed, oldest `creationDate` first, narrowed by any task or workflow filter the poll
  resolved (`engine/TaskRunService.java:120-144`, `:218-227`).
- A poll that claimed nothing is held and looks again when work is admitted on its engine instance or when the
  re-check interval passes, whichever is first. `PollWakeups` is one monitor per instance with a signal count:
  `TaskExecutionService.queue` signals it once per successful `tryAdmit`
  (`engine/TaskExecutionService.java:247-254`), and so does a run submitted to start with a workspace once it is
  parked `ready`/`pending` for its provisioning claim (`workflow/WorkflowRunService.java:801-816`). Both held
  polls, task and workflow, read the count before each look and wait for it to change, so a signal that lands
  during the look is not lost, and re-run their own filter query on a wake (`dispatcher/PollWakeups.java:24-55`,
  `dispatcher/DispatcherService.java:265`, `:381`, `:494-497`). A for-each item admits through the same method, so
  items wake polls too. Work that becomes claimable without an admission on that instance, a `retry.after` that
  elapses or an admission on another instance, is found by the timed re-check.
- `tryClaim` is one `findAndModify` that re-checks eligibility and in the same write sets `phase=queued`, `claim.by`,
  `claim.at` and `claim.leaseExpiresAt`, increments `claim.seq`, clears `retry.after` and bakes `timeoutAt`
  (`engine/TaskRunService.java:317-355`). A null result means another dispatcher won; the loser skips the candidate.
- `claim.seq` is never cleared. The dispatcher names itself as `dispatcherRef`, the value of `claim.by`, on every
  start and end (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/TaskRunner.java:162-187`). A start, end or
  log upload whose `dispatcherRef` does not match `claim.by` is refused with `409 TASKRUN_CLAIM_SUPERSEDED` before its
  body is merged, and so is one for an unfinished task run whose reaped claim a termination hand-off released, or
  for a task in `pending` with its claim kept, which the engine has requeued: a dispatcher holds a task only in
  `queued` or `running` (`engine/TaskRunService.java:1197-1212`, called at `:962`, `:1015`, `:1272`); the handler
  re-checks the claimant on entry (`engine/TaskExecutionService.java:899-930`) and the start and end write their
  fields only under that hold (`engine/TaskRunService.java:1177-1188`). A request with no `dispatcherRef` is
  accepted as the legacy protocol. `claim.seq` is not on the wire, so a dispatcher that lost and re-won a claim is
  not told apart from its earlier self.
- `claim.leaseExpiresAt` (`common/model/RunClaim.java:21`) is the dispatcher's liveness signal. The claim sets it
  90 s ahead (`flow.dispatcher.lease-ms`, `engine/TaskRunService.java:73`, `:333`); each dispatcher sends one batched
  heartbeat every 30 s listing the task runs it still works on
  (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:50`), and the engine moves the lease
  90 s ahead for those it holds, fenced on `claim.by` (`engine/TaskRunService.java:622-634`). A requeue, a
  termination claim and a completion unset it; an abandon keeps it until the completion lands, so a lost end is
  reaped again (`:711`). A claim its dispatcher stops listing, after a crash or a restart under the same id, is
  reaped once its lease lapses.
- The claim answer carries the task's spec with its references filled in; the stored spec keeps them
  (`dispatcher/DispatcherService.java:451-471`). Params are frozen at admission, so a requeued task gets the same
  values; a workspace or global reference in the spec is read at claim (`parameters.md`, "When a value resolves").
- `flow.queue.enabled=false` stops claiming only; the sweeps keep running (`dispatcher/DispatcherService.java:63-65`,
  `:239`, `:340`).

## Compare-and-set transitions instead of locks

Every status or phase change is a single-document compare-and-set: a `findAndModify` whose query names the expected
prior state, publishing the transition and returning the pre-image, or null when another caller won
(`engine/TaskRunService.java:906-927`, `engine/WorkflowRunStateHelper.java:429-440`). The flag writes (pause, waiting,
a run marked timed out) are guarded single-field updates that publish nothing
(`engine/WorkflowRunStateHelper.java:223-293`, `:418-424`). There are no distributed locks, no `@Version` fields and
no leader election. Only the winner performs side effects; a loser logs and returns, so any number of instances and
overlapping sweeps are safe.

## Starting a run

A run without a workspace submitted with `start=true`, the default, is started by the engine at once. A run that
declares a workspace stays `ready/pending` until a dispatcher claims it, provisions its storage and calls
`PUT /dispatcher/v1/workflowrun/{id}/start` (`workflow/WorkflowRunService.java:792-808`,
`dispatcher/DispatcherControllerV1.java:160`). `start=false` parks a run without a workspace for a later
`PUT /{id}/start`. It does not park a run with a workspace: the provisioning claim does not tell the two apart, so the
first dispatcher to poll provisions and starts it (`engine/WorkflowRunStateHelper.java:57-73`). A `runworkflow` task
submits its child with `start=true` (`engine/TaskExecutionService.java:1172-1173`).

The wait for a dispatcher is bounded by the run's own budget: a run with a workspace submitted with `start=true` takes
`timeoutAt` = creation + timeout at submit (`workflow/WorkflowRunService.java:798-802`), the timeout sweep takes
`pending` runs as well as `running` ones (`engine/WorkflowRunStateHelper.java:299-315`), and the start re-stamps the
deadline from the start time. A run no dispatcher claims within its budget ends `timedout` with no task run in flight
and no claim; a parked run has no deadline, and a claimed one is `queued` and left to the stale-claim sweep below.

A dispatcher that fails to provision, or dies holding the claim, tells the engine nothing; the run stays
`ready/queued` and claimed (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/WorkflowRunner.java:38-49`).
The `recoverStaleProvisionClaims` sweep releases a claim older than `flow.watcher.provision-claim-grace-ms`
(5 minutes) for the next poll, and once `claim.seq` shows the third claim went stale it completes the run `failed`
with "Workspace provisioning did not complete after 3 attempts." in `statusMessage`; a run has no `statusReason`
(`engine/WorkflowWatcher.java:411-442`, `engine/WorkflowRunStateHelper.java:108-156`). Both writes are fenced on the
observed `claim.seq`, so a start or a fresh claim wins.

## The watcher sweeps

`WorkflowWatcher` runs on every instance: once at boot, then every `flow.watcher.interval-ms` (30 s) after a random
start delay of up to 30 s; `flow.watcher.enabled=false` stops it (`engine/WorkflowWatcher.java:95-96`, `:142-158`).
Most sweeps page 50 documents (`EngineConstants.SWEEP_PAGE_SIZE`), and each runs isolated, so one failure cannot stop
the rest (`:159-175`). The stalled-run, for-each and stray-action sweeps resume where their last page ended and wrap
to the oldest (`engine/SweepCursor.java:12-20`), so candidates a sweep leaves alone cannot hide the ones behind them;
each instance keeps its own cursors.

| Sweep | Selects | Does |
| --- | --- | --- |
| `reapTaskTimeouts` (`WorkflowWatcher.java:218`) | `queued`/`running` task runs past `timeoutAt` (`TaskRunService.java:607-618`) | requeue or abandon (`WorkflowWatcher.java:504-521`): a requeueable type with fewer than 3 requeues is requeued (`TaskRunService.java:735`); otherwise it is marked `timedout` with `DeadlineExceeded`, only while its deadline has passed (`TaskRunService.java:693-713`), and ended |
| `reapWorkflowTimeouts` (`WorkflowWatcher.java:238`) | `pending` and `running` runs past `timeoutAt`, neither paused nor waiting (`WorkflowRunStateHelper.java:299-315`) | `WorkflowRunService.timeout` (`workflow/WorkflowRunService.java:915`) |
| `recoverStalledRuns` (`WorkflowWatcher.java:248`) | unpaused `running` runs started over 60 s ago (`WorkflowRunStateHelper.java:315-333`) with no task run in flight on two consecutive sweeps, checked for the page in one read (`TaskRunService.java:675-686`); one empty sighting is what a run between a task's end and the next admission looks like | re-drives the graph advance (`TaskExecutionService.java:643-663`) |
| `recoverForeachTasks` (`WorkflowWatcher.java:281`) | for-each parents in the `running` phase for over 60 s, whatever their status (`TaskRunService.java:462-485`) | ends a parent whose outcome is recorded; re-runs the idempotent fan-out (`TaskExecutionService.java:704-739`) for one with no item in flight (`TaskRunService.java:509-519`) in an unpaused run. A paused run is skipped, since the pause gate would refuse every item; resume re-runs the fan-out |
| `resumeDueWaitingTasks` (`WorkflowWatcher.java:319`) | `waiting` task runs whose `waitUntil` passed (`TaskRunService.java:825-832`) | wins each with `tryStartWaitingResume` (`TaskRunService.java:873-881`); a sleep completes, an `acquirelock` tries again (`TaskExecutionService.java:1075-1100`) |
| `cancelDeletedWorkflowRuns` (`WorkflowWatcher.java:336`) | in-flight runs of workflows with `status=deleted` | cancels each through the normal cancel |
| `pruneDeletedWorkflows` (`WorkflowWatcher.java:357`) | a page of deleted workflows with no run in flight (`WorkflowRetentionService.java:137-170`) | purges each run as retention does (`WorkflowRetentionService.java:194-201`), then deletes the workflow's artifacts, leftover task runs, revisions, schedules, actions and relationship node, then the workflow; audit records stay |
| `reapRunsWithMissingRevision` (`WorkflowWatcher.java:367`) | in-flight runs whose `workflowRevisionRef` no longer resolves: the distinct in-flight refs minus those that exist, then a page of the runs holding them (`WorkflowRunStateHelper.java:372-392`) | completes the run `invalid` and winds its stored task runs down as a cancel does: queued and running ones are ended, pending ones queued so they skip (`WorkflowExecutionService.java:265-303`) |
| `recoverStaleProvisionClaims` (`WorkflowWatcher.java:411`) | `ready/queued` runs whose `claim.at` is older than the grace (`WorkflowRunStateHelper.java:108-121`) | releases the claim while `claim.seq` < 3, else completes the run `failed` and winds its task runs down |
| `reapClaimsFromGoneDispatchers` (`WorkflowWatcher.java:450`) | claimed task runs whose dispatcher has not connected for 60 s (`WorkflowWatcher.java:74`); live dispatchers are read once and only the others' claims paged (`TaskRunService.java:656-671`) | requeue or abandon, without the deadline guard, `DispatcherGone` |
| `reapExpiredLeases` (`WorkflowWatcher.java:482`) | claimed task runs whose lease has passed (`TaskRunService.java:639-651`) | requeue or abandon, `LeaseExpired` |
| `closeStrayActions` (`WorkflowWatcher.java:529`) | `submitted` actions whose run is terminal or gone, the page's runs read in one query | marks the action `cancelled` by compare-and-set |
| `expireArtifacts` (`WorkflowWatcher.java:182`) | `available` artifacts past `expirationDate` | deletes the file, then sets `expired`; the record stays (`data-model.md`, "Artifacts") |
| `reapStaleArtifactUploads` (`WorkflowWatcher.java:187`) | `uploading` artifacts older than `flow.artifacts.upload-grace-minutes` (60) | deletes any partial file and the record |
| `purgeExpiredRuns` (`WorkflowWatcher.java:192-206`), hourly on its own clock (`flow.watcher.retention-interval-ms`) | `completed` runs created before now minus `workflowrun`/`retention.days` (365, never below 60), oldest first, at most 20 pages a pass; a run another run names in `initiatedByRef`, by its id (a retry's origin) or by one of its task runs' ids (a child's parent), is kept until that run is gone (`WorkflowRetentionService.java:97-128,186-192`) | deletes the run's run- and log-scoped artifacts (files first), actions, task runs, relationship node and the run; workflow-scoped artifacts and audit records stay |

## Timeouts and crash recovery

Timeouts are deadlines: `timeoutAt` = start + budget in minutes + 5 s grace (`engine/RunTimeouts.java:15-19`,
`engine/EngineConstants.java:13`), written by the task claim, the task start, the run start and, from the creation
time, the submit of a run with a workspace (`workflow/WorkflowRunService.java:798-802`), and cleared by completion,
requeue and abandon. There are no per-run timers; the sweeps are the only reaper. A crashed dispatcher,
a dead executor thread or a lost pod is recovered by the same path: the lease lapses (90 s), the dispatcher goes stale
(60 s) or `timeoutAt` passes, and the task is requeued or timed out. Both reap writes are fenced on the observed
`claim.seq`, so a claim that races the reap wins (`engine/TaskRunService.java:897-901`).

A task's budget, in minutes, is the smallest of the `task` settings' `default.timeout` (read when the run's task runs
are created), the task's declared timeout and the run's timeout, 0 meaning no ceiling (`engine/DAGUtility.java:74-85`,
`:236-257`). The run is a ceiling because a guard must cover the work beneath it. The seed ships `default.timeout` at
0, so a task inherits its run's timeout (`service-core/src/main/resources/seed/settings.json:361-366`); the setting
is an optional cap on a single pod, not a budget every task may spend.

When a timed-out task ends, its run times out too (`engine/TaskExecutionService.java:602-606`). A workflow timeout is
two writes and one transition: `WorkflowRunService.timeout` marks a pending or running run `timedout` on the caller's
thread, publishing nothing and leaving the phase as it is (`workflow/WorkflowRunService.java:915-940`,
`engine/WorkflowRunStateHelper.java:419-426`); the asynchronous `timeoutWorkflow` then completes it with one
compare-and-set, and only that winner ends the run's task runs and starts an automatic retry
(`engine/WorkflowExecutionService.java:213-266`). A lost completion leaves the run `timedout` in its phase, and the
timeout sweep drives it again once the run's own deadline has passed.

**The run's budget bounds execution, not waiting.** A run is waiting when every in-flight task is `waiting`: an
approval, a manual task, an event wait, a run-workflow task waiting on its child, a sleep or an acquire-lock wait
(`engine/EngineConstants.java:41-48`, `engine/TaskRunService.java:799-822`). When the last executing task enters a
wait, or a task ends and leaves only waits in flight, the run gains `waitingSince`
(`engine/WorkflowRunStateHelper.java:251-264`, called at `engine/TaskExecutionService.java:423`, `:633`, `:1097`)
and the timeout sweep skips it. When any task starts executing, or the wait ends, one write clears the flag and moves
`timeoutAt` forward by the time spent waiting (`engine/WorkflowRunStateHelper.java:268-293`, called at
`engine/TaskExecutionService.java:352`, `:600`, `:1082`). The reported `duration` is execution time
(`engine/RunTimeouts.java:28-42`). A gate nobody answers leaves the run active until it is cancelled.

**A wait is indefinite unless its task declares a timeout.** A waiting-type task's budget is its own declared timeout
alone (`engine/TaskExecutionService.java:342-345`, `:431-446`); one that declares none has no `timeoutAt`, and one
that does is reaped like any task and times out its run. That timeout is left out of the critical path that floors
the run budget (`workflow/RunTimeoutPolicy.java:145-149`), so a gate that may sit for an hour does not force the run
to ask for one.

A run's timeout is settled at submit, on the path every run creation passes through
(`workflow/WorkflowService.java:1827-1835`, `workflow/RunTimeoutPolicy.java:68-95`): the request's timeout, else the
revision's, else the run-duration quota itself, the workspace's own limit or else the platform
`quotas`/`max.workflowrun.duration` setting (`:161-172`). Below the revision's critical path, the longest chain of
declared task timeouts (`:102-152`), the submit is refused with `WORKFLOWRUN_TIMEOUT_TOO_SHORT` (1306). The
workspace's maximum run duration then clamps it; a maximum below the floor is refused with the same error, and a
workspace quota of 0 sets no maximum (`:83-93`).

## Retry

One backoff exists: `Backoff.nextRetryAt` gives 10 s doubling per attempt, capped at 5 minutes, plus up to 5 s of
jitter (`common/util/Backoff.java:12-22`). The result is stored as `retry.after` and gates claim eligibility;
`retry.count` counts requeues (`common/model/RunRetry.java:17-18`). A requeueable task (`template`, `custom`,
`script`, `generic`, `ai`, `uploadartifact`, `downloadartifact`) gets four attempts, its first and three requeues,
from one budget every cause below shares (`engine/EngineConstants.java:23-34`). The public task run shows a claimed
task's `attempt`, `retry.count` plus one; `retry` itself stays on the entity (`common/model/TaskRun.java:60-71`).

| Situation | Retried? | Where |
| --- | --- | --- |
| A deadline, lease or gone-dispatcher reap of a requeueable task with fewer than 3 requeues | yes, the same task run is requeued with backoff | `WorkflowWatcher.java:504-521` |
| The dispatcher reports it could not start the task (`ExceededQuota`, `StartTimeout`, `RateLimited`), requeueable type, fewer than 3 requeues | yes, on the same budget; once it is spent the task fails with that reason | `TaskRunService.java:1014-1017`, `:1099-1122` |
| Any other `failed` or `invalid` end | no; the graph advances or the run fails | `TaskRunService.java:1100-1104` |
| A gate, wait or inline system task times out | no; terminal `timedout` | `EngineConstants.java:18-31` |
| A workflow run times out with retries left | yes, as a new workflow run (`trigger=retry`, `initiatedByRef` = the first origin) | `WorkflowExecutionService.java:243-259`, `workflow/WorkflowRunService.java:1007-1012` |

A retried workflow run is a new run, so it clears the same quotas a submit clears and is clamped to the run-duration
maximum as it stands now (`workflow/WorkflowRunService.java:967-982`). The floor is not re-checked: a retry re-runs
the same revision. When the quota refuses an automatic retry the source run is already terminal, so the timeout path
logs and stops (`engine/WorkflowExecutionService.java:249-257`); a user retry gets `429 QUOTA_EXCEEDED`.

A user retry takes a `scope` of `all`, `failed` or `nodes`, plus `nodes`, `params` and `labels`. With `failed` or
`nodes`, every origin task run that succeeded and is not downstream of the retry point is copied under the new run
with its results and a `reusedFromRef`, without claim, deadline, retry or wait state, so the plan adopts it by name
and only the rest executes (`workflow/WorkflowRunService.java:1079-1168`, `:1229-1271`). Every task that neither
succeeded nor was skipped re-runs with its descendants, a for-each parent whole; `nodes` adds the named tasks and
theirs. A skipped task run is never copied but planned afresh, so a condition decides it again. Queueing refuses a
completed task run, so the start advances from the copies as if each had just ended
(`engine/WorkflowExecutionService.java:188-192`). Run results a re-run set-property task writes are dropped first; the
rest stay. The run records the point as `retryFrom`. A scoped retry of an active origin cancels the origin once the
new run is queued; a plain retry leaves it alone (`workflow/WorkflowRunService.java:1050-1055`). The new run gets an
empty workspace volume, so a re-run task cannot read files a carried-over task wrote; the retry dialog warns of this
(`client-web/src/Features/WorkflowRun/RetryRunModal/RetryRunModal.tsx:94-95`, `:155-164`).

A retry is refused before anything is written, as `WORKFLOWRUN_RETRY_INVALID` naming the cause: `nodes` given with
another scope or missing with `nodes`, a revision that no longer exists, an unknown name, the start or end node, a
name between an acquire-lock and the release-lock of the same key, or `failed` on a succeeded run with no failed task
(`workflow/WorkflowRunService.java:1080-1137`, `:1199-1221`). A retry that fails while it is put in place discards the
new run, its copies and its node and answers with a coded error (`:1014-1048`, `:1177-1181`).

A requeue of a claimed attempt parks the task `waiting/pending`, keeps `claim.by` (a pod may still be alive) and
increments `claim.seq`; the next attempt cannot be claimed until a dispatcher's termination poll releases the claim
and returns the task to `ready` in the same write (`engine/TaskRunService.java:716-782`, `:278-301`). An unclaimed
attempt goes straight back to `ready/pending`. A start or end from the timed-out attempt's own dispatcher that
arrives before the release is refused with `TASKRUN_CLAIM_SUPERSEDED`, as the task is `pending` with its claim kept,
so the retry the requeue granted stands (`:1186-1201`, `engine/TaskExecutionService.java:899-930`).

## Pause

Pause is the `pauseRequestedAt` timestamp on the run (`common/entity/WorkflowRunEntity.java:58`), set and cleared by
guarded writes (`engine/WorkflowRunStateHelper.java:223-246`). It is enforced in one place: `TaskExecutionService.queue`
returns before admitting a task when the run is paused (`engine/TaskExecutionService.java:174-179`). The gate holds a
for-each task before it fans out and each of its items before admission, since items are queued through the same
method. Work already admitted, claimed or running continues and times out on its own deadline
(`engine/TaskRunService.java:607-618`). The run's deadline is not reaped while paused, and resume moves it forward by
the time paused, as a wait does; a pause and a wait that overlap are counted once
(`engine/WorkflowRunStateHelper.java:282-313`). Resume clears the flag and calls `advance`, which queues whatever the
gate held back, including the unadmitted items of a running for-each task (`workflow/WorkflowRunService.java:885-900`,
`engine/TaskExecutionService.java:643-663`). The stalled-run sweep and the for-each fan-out recovery skip a paused run.

## The graph advance

The run's directed acyclic graph is materialised in full when the run is queued, every node a `TaskRunEntity`
(`engine/WorkflowExecutionService.java:76`, `engine/DAGUtility.java:198-379`), and advanced level-triggered from
stored state. When a task completes, the completion winner reads the ended task's dependants, by the stored
`dependencies.taskRef`, and the tasks they depend on, never the run's whole graph (`DAGUtility.retrieveDependants`,
`engine/DAGUtility.java:145-181`), queues every dependant whose dependencies are all `completed`, and finishes the
run when the end node's dependencies are all done, reading the whole graph once there for the path check
(`engine/TaskExecutionService.java:616-637`, `:1488-1608`). A dependency with no task run never counts as satisfied
(`:1612-1628`). `advance` re-applies the same logic for every completed task over one whole-graph read and is safe
to call at any time; the watcher, resume and a scoped retry's start use it (`:643-663`). Four rules hold:

1. Callers pass ids only; the handler MUST re-read the document at entry (`engine/TaskExecutionService.java:141-148`).
2. The handler MUST check the transition has not already happened (phase guards at `:153-156`, `:286-290`,
   `:519-522`).
3. Every write MUST be a guarded compare-and-set or a field-scoped update. A dispatcher's start and end merge their
   request fields one by one, held to the claimant and an unfinished phase (`engine/TaskRunService.java:984-993`,
   `:1065-1082`). No handler saves a task run whole: every wait is armed by a field-scoped compare-and-set fenced
   on the `running` phase (`engine/TaskRunService.java:838-869`).
4. Only the compare-and-set winner performs side effects: queueing dependants, finishing the run, starting a retry.

Results are keyed by name on every path: a `setwfproperty` result, an event delivery and a run-workflow task's
`workflowRunRef` upsert the element with that name, a positional `$set` or else a `$push` guarded on the name being
absent (`engine/ResultUtil.java:25-43`), so a key written twice stays one element and a concurrent writer of another
key is never lost. An event wait, and a run-workflow task with `wait=true`, arm with `tryArmWait`, a `status=waiting`
write fenced on the `running` phase (`engine/TaskRunService.java:853-858`). The event wait then re-reads, so an event
delivered between the handler's entry read and the arm (`applyEventDelivery`, `:391-399`) is honoured rather than
rolled back (`engine/TaskExecutionService.java:1348-1370`). An approval or manual task arms with the same write
carrying its `actionRef` result (`engine/TaskRunService.java:864-869`); when the fence fails because a cancel or a
timeout completed the task first, the action just created is deleted and the run is not marked awaiting approval
(`engine/TaskExecutionService.java:1420-1432`).

## Outbound events: the transactional outbox

Compare-and-set winners publish an in-process `TaskRunTransition` or `WorkflowRunTransition`. `WorkflowRunAuditBridge`
records a CREATE audit event on a run's first status change and an UPDATE carrying the terminal status and duration
when it reaches `completed`; these are the events the monthly run quota counts
(`core/audit/WorkflowRunAuditBridge.java:58-103`). Task-run transitions are not audited. Auditing is best effort and
never fails the transition.

When `flow.events.sink.enabled=true` (default `false`, `service-core/src/main/resources/application.properties:51`),
`CloudEventsBridge` inserts one `events_outbox` row per externally visible status change, or per run reaching
`completed` with a status its caller wrote directly; the start and end nodes write none
(`event/CloudEventsBridge.java:34-77`). `OutboxDispatcher` exists only then and drains every
`flow.events.outbox.interval-ms` (5 s) on every instance, delivering at least once, marking rows `sent` by
compare-and-set and `dead` after 3 failed attempts (`event/OutboxDispatcher.java:43`, `:64-91`). Each row is
delivered as its own transition: the run is re-read and given the row's status and phase, and the event's id and time
are the row's, so a late row reports what it recorded and a retried delivery keeps its id (`:97-115`,
`event/EventFactory.java:44-47`, `:69-72`). An event carries identity and lifecycle, not params and results, unless
`flow.events.sink.payload=full` (`api-contract.md`, "Events out"). There is no broker, no partitioning and no leader.

No transaction spans the compare-and-set and the outbox insert (`event/entity/EventOutboxEntity.java:13-19`), so a
crash between them loses that one event; the engine never reads the outbox, so a lost row cannot stall a run. Sent
rows expire after 7 days (`migration/_0021__Indexes.java:113-117`); dead rows are kept with `lastError` (at most 1024
characters) and `deadAt` (`event/OutboxDispatcher.java:141-171`). `GET /api/v2/system/outbox` lists rows by status,
dead by default, and `PUT /api/v2/system/outbox/replay` puts the named rows, or every row in a status, back to
`pending` with a fresh attempt budget (`event/OutboxService.java:59-83`).

## Schedules

Cron and run-once schedules fire from `ScheduleWatcher` on every instance: once at boot, then every
`flow.schedule.watcher.interval-ms` (30 s) after a random start delay, behind its own switch
`flow.schedule.watcher.enabled` (`schedule/ScheduleWatcher.java:44-77`). `fireDueSchedules` wins each due fire with
`tryClaimFire`, a compare-and-set fenced on the observed `nextFireAt` that advances it to the next occurrence from
now, so a backlog collapses to one fire (`:110-126`, `schedule/ScheduleService.java:477-490`). A failed submit is
re-armed with the shared backoff, 3 attempts in all (`schedule/ScheduleWatcher.java:134-158`).

Occurrences use cron-utils' five-field UNIX definition. `CronService.toUnix` reads a stored cron in any form: a six- or
seven-field Quartz expression (the v3 form) is mapped, and a `cron` schedule's "0 mm HH * DAYS" order is reordered
(`schedule/CronService.java:31-49`); it is applied when a schedule is saved, fired and drawn on the calendar
(`schedule/ScheduleService.java:305-306`, `:421-446`). An expression it cannot read never fires.

## Child workflows

A `runworkflow` task submits another workflow as a child run (`engine/TaskExecutionService.java:1110-1197`) with the
task's own params minus `workflowRef` and `wait` (`:1204-1209`): the values its node passes and, under for-each,
`item` and `index`. The parent run's params are not handed down; its timeout, retries, annotations and labels are. A
node param the `runworkflow` task does not declare must be one the child workflow's latest revision declares; saving
refuses any other with `WORKFLOW_INVALID_CHILD_PARAM` (1218) (`workflow/WorkflowService.java:1568-1593`). A
`runscheduledworkflow` task builds its schedule's params the same way, also leaving out the `futureIn`,
`futurePeriod`, `timezone` and `time` it reads itself (`engine/TaskExecutionService.java:1319-1321`).

**Lineage.** The child is submitted with `trigger=task` and `initiatedByRef` = the submitting task run's id, the
typed pair the retry path uses (`workflow/WorkflowService.java:1866-1870`); a run has no `parentRef`. The single-run
read resolves the owning run onto `initiatedByWorkflowRunRef` (`workflow/WorkflowRunService.java:1278-1285`).

**Wait.** `wait` defaults to `false`: the task records the child's id as its `workflowRunRef` result and ends
`succeeded` at once. With `wait=true` it parks as `waiting` with no `waitUntil`, so only the child's completion or a
timeout the task declared ends it, and the parent run's clock stops while nothing else of it executes. The arm happens
before the submit, so a child that finishes at once finds its parent waiting
(`engine/TaskExecutionService.java:1136-1144`).

**Ending the parent.** `ChildWorkflowRunListener` handles each `WorkflowRunTransition` into `completed`: when the run
has `trigger=task` and the named task run is still `waiting`, it calls `TaskRunService.end`, so the completion
compare-and-set ends the task once however often the transition arrives. A `succeeded` child succeeds the task and
hands it the child run's results, so a downstream `$(tasks.x.results.y)` reads them; any other status fails it with
"Child run `<id>` ended `<status>`" and `ChildRunFailed` (`engine/ChildWorkflowRunListener.java:61-127`). A for-each
item is a task run of its own and is ended the same way.

**Cascade cancel.** When a run ends `cancelled` or `timedout`, the same listener cancels every child run whose
`initiatedByRef` is one of its task-run ids and whose phase is still `pending`, `queued` or `running`, read through
the `initiated_by_phase` index (`engine/ChildWorkflowRunListener.java:134-155`). A cancelled child publishes its own
transition, so a nested chain unwinds from the top. Pause does not cascade.

**Nesting depth.** Before submitting, the engine walks `initiatedByRef` up, task run then owning run, and fails the
task with `NestingDepthExceeded`, creating no child, when the depth reaches `workflowrun`/`max.nesting.depth` (5); the
walk stops at the cap (`engine/TaskExecutionService.java:1125-1134`, `:1215-1257`).

## For-each tasks

A task with `foreach` set runs once per item of an array, in parallel, in the same run. Only the types a
dispatcher runs (`template`, `script`, `custom`, `ai`, `generic` and the two artifact types) and `runworkflow` may
carry it; saving refuses it anywhere else, `start` and `end` included (`workflow/WorkflowService.java:128-137`,
`:1414-1421`, `:1611-1619`). A for-each `runworkflow` task submits one child run per item. The graph still sees one
task run per node: `DAGUtility.retrieveGraph` and `retrieveDependants` read only task runs without a `parentRef`
(`engine/DAGUtility.java:131-181`), so items are never vertices.

| Step | What happens | Where |
| --- | --- | --- |
| Fan-out | `queue` resolves `foreach.items` against the task's parameter layers, a literal array or one reference whose value is an array or text holding one, and checks the count against `workflowrun`/`max.foreach.items` (256). A compare-and-set moves the parent `notstarted/pending` → `running/running` and records the array. The parent never passes through `ready`, so it is never claimable, and it carries no claim and no `timeoutAt`, so no lease, orphan or deadline sweep reaps it | `TaskExecutionService.java:669-695`, `TaskRunService.java:440-456` |
| Items | One task run per element, named `<name>[<index>]`, with `parentRef`, `index`, the parent's type, task, spec, workspace, declared results, timeout, labels and annotations, no dependencies, and the parent's params plus `item` and `index`. An item is inserted only when its name is free, so the `node_uniqueness` index makes a concurrent duplicate lose; each unadmitted item is then queued. Items skip the graph check but pass the pause gate, the params cap and admission, and are claimed, leased, timed out and retried like any task | `TaskExecutionService.java:704-783`, `:181-186` |
| Parent completion | An item's end never advances the graph. After its own completion it asks whether any item is unfinished or missing, an `exists` and a `count`, and only when none is loads the items' `index`, `status` and `results`. The parent then ends with its outcome on the item's end thread, in the completion compare-and-set guarded on `running`, so of any number of concurrent last-item enders exactly one completes it, and that one admits the parent's dependants before the item's end returns: no second asynchronous hop sits between the last item and the next task. The fan-out of an empty array and the watcher's recovery complete the parent the same way, on their own thread. A crash before that end leaves the parent running with every item terminal, which `recoverForeachTasks` completes | `TaskExecutionService.java:608-614`, `:811-856`, `TaskRunService.java:489-504` |
| Outcome | `succeeded` when every item succeeded, else `failed` with `ItemFailed` and "`<n>` of `<m>` items did not succeed." Each declared result, or for a task that declares none, such as `runworkflow`, each result its items produced in first-seen order, becomes an array in item order with `null` for an item that did not succeed. When MongoDB refuses the combined arrays as over its 16 MB document limit, the parent fails with `ResultsTooLarge` and no results. An empty array succeeds at once with empty arrays. Items that do not resolve to an array fail the task with `ForeachItemsInvalid`; more than the cap with `ForeachTooManyItems`, creating no item | `TaskExecutionService.java:478-489`, `:818-865`, `:676-693` |

An item that times out is a task timeout: it times out the whole run, which cancels the parent and the remaining items
(`engine/TaskExecutionService.java:602-606`). A cancel reaches every item because the cancel pass reads the stored
task runs, not the graph (`engine/WorkflowExecutionService.java:274`). Downstream, `$(tasks.<name>.results.<result>)`
finds the parent, since the lookup is by exact name and items carry the bracketed suffix; a task name ending in
`[<digits>]` is refused on save with `WORKFLOW_INVALID_TASK_NAME` (1214), while other brackets (`Deploy [prod]`) are
allowed (`workflow/WorkflowService.java:1401-1410`).

## Task locks

The `acquirelock` and `releaselock` types use the `task_locks` collection: one document per key, `_id` =
`<workspace id>:<key>` with the id of the workspace that owns the run's workflow, holding `holder`, `workflowRunRef`,
`acquiredAt` and `expiresAt`, with an expiry index on `expiresAt` (`engine/entity/TaskLockEntity.java:9-25`,
`migration/_0021__Indexes.java:124-128`, `engine/TaskExecutionService.java:1061-1069`). Acquire is an upsert that
matches only an unheld or expired key; a `DuplicateKeyException` means held (`:1019-1049`). A task that cannot acquire
parks as `waiting` for 5 s and the watcher tries again (`:78`, `:995-999`); its declared timeout bounds the wait.
Release deletes only when this task is the holder (`:1053-1057`).

## Not built

| Item | What exists instead | Trigger to build |
| --- | --- | --- |
| Per-type or per-class concurrency caps | each dispatcher claims only the slots it has free, filtered by type, task and workflow label; `flow.queue.enabled` stops all claiming | load testing shows one task type starving the rest |
| Retry classes (rate-limit, deterministic-terminal) | one `Backoff` and one budget for every requeue cause; a task that ran and failed is not retried | a task family whose failures demonstrably need a different policy |
| Supersede generations and a separate reconciler | a retry creates a new workflow run; reconciling is the level-triggered `advance` | an in-place partial re-run of one workflow run becomes a requirement |
| For-each batching, a failure-tolerance rule, a per-task parallel limit, or repeating a group of tasks per item | one task repeated per item; any failed item fails it, and an `always` connection carries on; items run as fast as dispatchers claim them | real workflows that need one of them |
| A post-terminal phase, or any teardown field on the run | the dispatcher asks `POST /dispatcher/v1/workspaces/releasable` which runs it holds volumes for are finished; the run records nothing (`dispatcher/DispatcherService.java:498-521`) | a cleanup outcome has to be shown to a user or read back by the engine |
| A transaction across the compare-and-set and the outbox insert | the accepted loss window above | a missing terminal-status event is reported, or a consumer depends on delivery |

## Also worth knowing

- `flow.watcher.enabled` gates the engine sweeps and retention, `flow.schedule.watcher.enabled` the schedule sweeps,
  and the outbox drain exists only with the event sink on (`core/config/SchedulingConfiguration.java:6-12`). The two
  watcher switches exist for tests; production keeps them on.
- Results are capped by the dispatcher (4096 bytes on Kubernetes) behind a 1 MB engine storage guard at task end
  (`engine/TaskRunService.java:1048-1062`), and resolved params at 16384 bytes before admission
  (`engine/TaskExecutionService.java:211-226`); see `dispatcher-contract.md`, "Ending".

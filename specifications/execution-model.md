# Execution model

How a workflow run and its task runs move from submitted to finished today. Paths are under `service-core/src/main/java/io/boomerang/` unless stated otherwise.

## Run states

Every run carries `status` (the externally visible outcome) and `phase` (where the run sits in the pipeline). Both are closed enums; pause and retry are separate fields, never status values.

| Field | Values | Source |
| --- | --- | --- |
| `RunStatus` | `notstarted`, `ready`, `running`, `waiting`, `succeeded`, `failed`, `invalid`, `skipped`, `cancelled`, `timedout` | `lib-common/.../enums/RunStatus.java:6-16` |
| `RunPhase` | `pending`, `queued`, `running`, `completed`, `finalized` | `lib-common/.../enums/RunPhase.java:6-11` |

The normal path is `notstarted/pending` → admit → `ready/pending` → claim → `ready/queued` → start → `running/running`
→ end → `<terminal>/completed` → finalize → `<terminal>/finalized`. Each arrow is one guarded write:

| Transition | Task run (`engine/TaskRunService.java`) | Workflow run (`engine/WorkflowRunStateHelper.java`) |
| --- | --- | --- |
| admit (persists resolved params) | `tryAdmit` `:278` | `tryAdmit` `:135` |
| claim (a dispatcher takes it) | `tryClaim` `:232` | `tryClaimForProvision` `:79`, `tryClaimForTeardown` `:108` |
| start (bakes `timeoutAt`) | `tryStartExecution` `:380` | `tryStart` `:152` |
| complete | `tryComplete` `:415` | `tryComplete` `:188` |
| finalize | — | `tryFinalize` `:217` |

## The claim-based queue

Dispatchers pull work; the engine never pushes. A dispatcher long-polls `DispatcherService`, which pages candidates with `findClaimable` and claims each one individually (`dispatcher/DispatcherService.java:205-209`).

- `findClaimable` selects `status=ready`, `phase=pending`, `type` in the dispatcher's registered types, no `claim.by`,
  and `retry.after` absent or elapsed, oldest `creationDate` first (`engine/TaskRunService.java:75-95`).
- `tryClaim` is one `findAndModify` that re-checks the full eligibility and, in the same write, sets
  `phase=queued`, `claim.by`, `claim.at`, `$inc claim.seq`, clears `retry.after` and bakes `timeoutAt`
  (`TaskRunService.java:238-258`). A null result means another dispatcher won; the loser skips the candidate.
- `claim.seq` is never cleared and fences stale claimants: `start` and `end` requests that carry a claimant
  identity MUST match `claim.by`/`claim.seq` (`claimantIsValid`, `engine/TaskExecutionService.java:536-560`, called
  at `:247` and `:415`), and the completion write repeats the check in its query (`TaskRunService.java:427-428`).
  A request with no identity is accepted as the legacy protocol (`TaskExecutionService.java:538-545`).
- `claim.leaseExpiresAt` exists on `RunClaim` (`lib-common/.../model/RunClaim.java:21`) and is indexed by the loader
  (`service-loader/.../_0017__RunIndexes.java:82`), but nothing writes it; it is only ever `unset`
  (`TaskRunService.java:583`, `WorkflowRunStateHelper.java:165`). Leases are not a working guard.
- A global kill switch exists: `flow.queue.enabled=false` stops claiming only; the sweeps keep running (`DispatcherService.java:38-40`, `:111`, `:176`).

## Compare-and-set transitions instead of locks

Every state change is a single-document compare-and-set (CAS): a `findAndModify` whose query names the expected
prior state and whose update applies the new one, returning the pre-image or null (`TaskRunService.java:669`,
`WorkflowRunStateHelper.java:331-334`). There are no distributed locks, no `@Version` fields and no leader election.
Only the CAS winner performs side effects; a loser logs and returns, so N instances and overlapping sweeps are safe.

## The watcher sweeps

`WorkflowWatcher` runs on every instance, once at boot and then every `flow.watcher.interval-ms` (default 30 s) with a random start delay; `flow.watcher.enabled=false` disables it (`engine/WorkflowWatcher.java:109-125`).
Each sweep pages 50 documents (`EngineConstants.SWEEP_PAGE_SIZE`) and is isolated so one failure cannot stop the rest (`:129-138`).

| Sweep (`WorkflowWatcher.java`) | Selects | Does |
| --- | --- | --- |
| `reapTaskTimeouts` `:151` | task runs `queued`/`running` with `timeoutAt` elapsed (`TaskRunService.findReapable` `:445`) | requeues a `template`/`custom`/`script`/`generic` task with attempts < 3 (`tryRequeue` `:548`); otherwise marks it `timedout` (`tryTimeout` `:485`) and ends it |
| `reapWorkflowTimeouts` `:181` | running, unpaused workflow runs past `timeoutAt` (`findTimedOut` `:256`) | `WorkflowRunService.timeout` (`workflow/WorkflowRunService.java:870`) |
| `recoverStalledRuns` `:194` | running runs started > 60 s ago with zero in-flight task runs (`existsInFlightByWorkflowRunRef` `:602`) | re-drives the graph advance (`TaskExecutionService.advance` `:515`) |
| `finalizeWorkspacelessRuns` `:214` | completed runs with no workspaces (`findFinalizableWithoutWorkspaces` `:298`) | `tryFinalize` — no dispatcher teardown is needed |
| `resumeDueWaitingTasks` `:232` | `waiting` task runs whose `waitUntil` elapsed (`findWaitingDue` `:615`) | claims via `tryStartWaitingResume` `:637`, then a sleep completes or an `acquirelock` re-attempts (`resumeWaitingTask` `:771`) |
| `cancelDeletedWorkflowRuns` `:248` | in-flight runs of workflows with `status=deleted` | cancels each through the normal cancel path |
| `pruneDeletedWorkflows` `:269` | — | a no-op until `flow.watcher.retention.enabled=true` (`:85-86`); the retention policy is undecided |
| `reapRunsWithMissingRevision` `:282` | in-flight runs whose `workflowRevisionRef` no longer resolves | completes the run as `invalid`, queues pending tasks (which skip) and ends the rest |
| `reapClaimsFromGoneDispatchers` `:326` | claimed task runs (`findClaimed` `:461`) whose dispatcher has not connected for 60 s (`:69`) | same requeue-or-abandon treatment as a deadline reap (`tryAbandon` `:511`) |
| `closeStrayActions` `:378` | `submitted` actions whose run is already terminal | marks the action `cancelled` by CAS |

## Timeouts and crash recovery

Timeouts are deadline-based: `timeoutAt` = start time + budget in minutes + 5 s grace (`engine/RunTimeouts.java:14-18`,
`EngineConstants.java:9`), written by the claim and start transitions and cleared by completion. There are no per-run
timers; the sweeps above are the only reaper. A crashed dispatcher is recovered by the same path: its claimed task
reaches `timeoutAt` (or its dispatcher goes stale) and is requeued or timed out. Both reap writes are fenced on the
observed `claim.seq`, so a claim that races the reap wins (`TaskRunService.java:659-664`). A task's budget is the
smaller of the workflow's `boomerang.io/task-timeout` annotation and the task's own timeout (`engine/DAGUtility.java:194-207`).
A task timeout on the final write times out the whole run (`TaskExecutionService.java:487-490`); a workflow timeout
cancels every queued, running and pending task (`engine/WorkflowExecutionService.java:271-304`).

## Retry

One backoff class exists: `Backoff.nextRetryAt` gives 10 s doubling per attempt, capped at 5 min, plus up to 5 s
jitter (`lib-common/.../util/Backoff.java:12-21`). The result is stored as `retry.after` and gates claim eligibility;
`retry.count` counts attempts (`RunRetry.java:17-18`). What is retried:

| Situation | Retried? | Where |
| --- | --- | --- |
| Task run times out or its dispatcher disappears, type is requeueable, attempts < 3 | yes, requeued with backoff | `WorkflowWatcher.java:55-58`, `:157-163`, `:336-345` |
| Task run reported `failed`/`invalid` by the dispatcher | no — the run advances or fails | `TaskExecutionService.java:463-473` |
| Gate, wait or inline system task times out | no — terminal `timedout` | `WorkflowWatcher.java:53-56` |
| Workflow run times out and `retries` > 0 | yes, as a NEW workflow run (`trigger=retry`, `initiatedByRef`) | `WorkflowExecutionService.java:258-268`, `WorkflowRunService.java:897-935` |

A requeue of a claimed attempt keeps `claim.by` (a pod may still be alive) and bumps `claim.seq`, so the stale attempt cannot report and the next
attempt cannot start until the dispatcher's termination poll releases the claim (`TaskRunService.java:530-592`, `tryClaimForTermination` `:155`).

## Pause

Pause is the `pauseRequestedAt` timestamp on `WorkflowRunEntity` (`lib-common/.../entity/WorkflowRunEntity.java:64`),
set and cleared by CAS (`WorkflowRunStateHelper.tryPause` `:229`, `tryResume` `:246`). It is enforced at exactly one
place: `TaskExecutionService.queue` returns before admitting a task when the run is paused
(`TaskExecutionService.java:141-145`). Work already admitted, claimed or running continues and times out on its
absolute deadline; the workflow-run deadline is not reaped while paused (`findTimedOut` `:263-264`). Resume clears
the flag and calls `advance`, which re-queues whatever the gate held back (`WorkflowRunService.java:849-856`).

## The DAG advance

The directed acyclic graph (DAG) is materialised in full at queue time — every node becomes a `TaskRunEntity` — and
advanced level-triggered from persisted state. When a task completes, the completion winner reads the run's task
list, queues every dependant whose dependencies are all `completed`, and finishes the run when the end node's
dependencies are all done (`TaskExecutionService.java:1061-1148`). A dependency with no task run is never treated as
satisfied (`:1149-1161`). `advance` (`:515-529`) re-applies the same logic for every completed task and is safe to
call at any time; the watcher and resume both use it. Transition handlers follow four rules:

1. Callers pass ids only; the handler MUST re-read the document at entry (`TaskExecutionService.java:90-98`).
2. The handler MUST check the transition has not already happened (phase guards at `:107-110`, `:241-245`, `:409-412`).
3. Every write MUST be a guarded CAS; whole-document `save` is confined to caller-side request merges before the handler runs (`TaskRunService.java:718`, `:779`).
4. Only the CAS winner performs side effects — queueing dependants, finishing the run, spawning a retry.

## Outbound events: the transactional outbox

CAS winners publish an in-process `TaskRunTransition`/`WorkflowRunTransition` event (`engine/model/*.java`). When
`flow.events.sink.enabled=true` (default `false`, `service-core/src/main/resources/application.properties:38`),
`CloudEventsBridge` inserts one `events_outbox` row per externally visible status change (`event/CloudEventsBridge.java:32-76`)
and `OutboxDispatcher` drains it every 5 s on every instance, delivering at least once, marking rows `sent` by CAS,
and marking them `dead` after 3 failed attempts (`event/OutboxDispatcher.java:41`, `:59-85`). There is no broker, no partitioning and no leader.
Accepted limitation: no transaction spans the CAS commit and the outbox insert (`event/entity/EventOutboxEntity.java:13-17`), so a crash
between them loses that one notification. The engine never reads the outbox, so a lost row cannot stall a run.

## Schedules

Cron and run-once schedules fire from `ScheduleWatcher`, standalone mode only, on the same every-instance, 30 s, jittered cadence
(`schedule/ScheduleWatcher.java:34`, `:69-77`). `fireDueSchedules` pages active schedules with `nextFireAt` elapsed and wins each fire with
`ScheduleService.tryClaimFire`, a CAS that advances `nextFireAt` to the next occurrence computed from now (`schedule/ScheduleService.java:459-472`),
so a backlog collapses to one fire. A failed submit is re-armed with the same backoff up to 3 attempts (`ScheduleWatcher.java:137-161`).

## Task locks

The `acquirelock`/`releaselock` task types use the `task_locks` collection: one document per workspace-scoped key with `holder`, `expiresAt` and a
TTL index (`engine/entity/TaskLockEntity.java:9-24`). Acquire is an upsert that matches only an unheld or expired key; a `DuplicateKeyException`
means "held" (`TaskExecutionService.java:711-740`). A task that cannot acquire parks as `waiting` for 5 s and the watcher re-attempts it
(`:57`, `:686-690`). Release deletes only when this task is the holder (`:745-749`).

## Not built

| Item | What exists instead | Trigger to build |
| --- | --- | --- |
| Per-type or per-class concurrency caps | `findClaimable` filters by task type only; the global `flow.queue.enabled` switch is the only throttle | load testing shows one task type starving the rest |
| Retry classes (rate-limit, deterministic-terminal) | one generic `Backoff`; failures are not retried | a task family whose failures demonstrably need a different policy |
| Worker leases and lease renewal | `claim.leaseExpiresAt` declared, never written; recovery waits for `timeoutAt` or 60 s of dispatcher silence | worker-crash recovery latency proves to matter |
| Supersede generations and a separate reconciler | retry creates a new workflow run; "reconcile" is the level-triggered `advance` | in-place partial re-run of one workflow run becomes a requirement |
| A transaction (or `transitionSeq`) across CAS commit and outbox insert | the accepted creation-loss window above | a missing terminal-status event is reported, or a consumer becomes load-bearing on delivery |

## Also worth knowing

- `flow.watcher.enabled` gates the outbox drain as well as the sweeps; schedules fire only in `standalone` mode.
- Result payloads are capped at 4096 bytes at task end and parameters at 16384 bytes at admission (see `task-runtime.md`).

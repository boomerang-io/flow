# 0021 — Every instance does every job, and no coordination or queue machinery is built ahead of evidence

**Status:** accepted · **Date:** 2026-08-18

## Context

Several `service-core` instances share one MongoDB; each takes inbound events, serves claim polls and runs the sweeps,
the schedule watcher and the outbox drain. The choice is how much coordination and queue machinery to build up front.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Coordination: partition runs across instances (sticky routing by run id) | Handlers are not safe to run twice | Hashing, membership and rebalancing when an instance dies; gives no ordering either |
| Coordination: elect a leader for the sweeps | Sweeps are expensive or destructive | Leader death stalls every sweep until re-election; split-brain still needs per-write fencing |
| Queue: per-class concurrency caps and kill switches, and retry classes on a typed `retryClass` field | A known workload saturates one task type, or a runtime reports typed rate limits | New fields and protocol changes with no caller that needs them |
| Recovery: supersede generations (`attempt`, `supersededAt`) and a reconciler that re-drives part of a run in place | In-place partial re-run is a product requirement | New fields, a partial unique index and a second live generation of a node to tell apart everywhere |

## Decision

None of them. Every instance does everything, and every action is a single-document compare-and-set (decision 0004),
so a second instance acting on the same run finds the state changed and does nothing. The sweeps, the schedule watcher
and the outbox drain run on every instance from a random start offset (`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:149-152`,
`schedule/ScheduleWatcher.java:65-67`, `event/OutboxDispatcher.java:64-67`). One switch, `flow.queue.enabled`, stops
claiming and never the sweeps (`dispatcher/DispatcherService.java:63-65`, `:239-242`, `:340-343`). Retry is one backoff (10 s base,
doubling, 5 min ceiling, up to 5 s jitter: `common/util/Backoff.java:12-22`) for the task queue, the outbox and schedule
re-arming, with three requeues (`engine/EngineConstants.java:34`); `RunRetry` holds only `after` and `count`
(`common/model/RunRetry.java:17-18`). A retried workflow run is a new run pointing back at its origin
(`workflow/WorkflowRunService.java:986`, `:1008-1013`), and `TaskExecutionService.advance` re-applies the graph from
persisted state as the reconcile (`engine/TaskExecutionService.java:641-661`). No measured need asks for more.

## Consequences

- A plain load balancer fronts N identical instances; losing one costs at most one sweep interval, as in-process events
  are hints and the sweeps the guarantee. If sweep cost ever matters, shard a sweep page by `_id` hash, still leaderless.
- Operators have one lever: `flow.queue.enabled=false` hands out no new work while work in flight finishes or times out.
- A task is requeued only on a timeout, a lapsed lease, a gone dispatcher or a start that never happened (decision 0023);
  any other `failed` end is final. A retry policy, if built, derives from the typed `statusReason` (decision 0024).
- Reopen on a load test or a reproduced incident: one task type starving others or overrunning the cluster, one class
  needing a stop while others run, or in-place partial re-run of a run becoming a requirement.

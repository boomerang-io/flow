# 0023 — A claim carries a lease, polls run back to back for free slots and are woken by key, and work that never started is handed back

**Status:** accepted · **Date:** 2026-10-29

## Context

A task whose dispatcher crashed waited out its deadline, or for ever, and a restarted dispatcher gets its id back inside
the gone-dispatcher window. A wait between polls added to every hop, a dispatcher could claim more than its cluster
could start, and an admission woke every held poll. A pod held on an image pull waited out the task timeout, and a
failed workspace provisioning went unreported for 15 minutes.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Lease: a heartbeat per task, or ids listed on the poll; or the claim sets it and one batched heartbeat per dispatcher renews it | Few tasks in flight; or many | 6.7 requests a second at 200 tasks; or heartbeats become a MUST |
| Pacing: a fixed 5 s wait, capacity read from the cluster, routing by a `pool` field; or poll again at once for free slots, narrowed by typed filters | Latency does not matter; or hops under a second | Up to 5 s a hop and new fields; or a wrongly sized slot count under-uses a cluster |
| Wake: one counter and `signalAll`, or a change stream; or a registry keyed by task type with one condition per waiter | Few held polls; or matching polls only | A find per held poll per admission; or an in-memory match that must mirror the claim query |
| Start: fail at create or retry in the dispatcher; or bound the start, hand the task back to the engine's requeue, and report a failed provisioning with its cause | One cluster; or any number of dispatchers | Hours lost to an image typo; or typed end reasons and an additive route |

## Decision

`tryClaim` writes `claim.leaseExpiresAt` = claim time + `flow.dispatcher.lease-ms` (90 s)
(`engine/TaskRunService.java:333`); `dispatcher-sdk` sends every id in flight in one `PUT /dispatcher/v1/{id}/heartbeat`
every 30 s (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:190-200`). A poll starts again at
once after one that brought work or was held 30 s, asks for `limit` = its free slots, may name task types, slugs and one
definition label, and the engine claims at most a page of 20 (`QueuePoller.java:104-123`, `InFlightTasks.java:8-33`,
`dispatcher/ClaimFilterService.java:40-69`, `dispatcher/DispatcherService.java:356-402`). A held poll registers its
filter under each type on its own `Condition`, and an admission signals the matching waiters
(`dispatcher/PollWakeups.java:41-160`). The Kubernetes dispatcher reports `ImagePull`, `DispatchError`, `StartTimeout`
and `ExceededQuota` (`executor/PodStartWatch.java:43-70`), which the engine requeues on the shared budget
(`TaskRunService.java:1088-1111`); a failed provisioning is reported with its cause, held with "Retrying in 1 minute
(attempt n of 3)" and failed on the third (`engine/WorkflowExecutionService.java`).

## Consequences

- A lost claim is requeued within the lease plus a sweep; the lease MUST stay at least twice the beat plus a sweep.
- A ready task is claimed within about a second; a full cluster delays a task instead of failing it; a cluster that is
  off shows its cause on the run at once. Older SDKs keep the sweep's 5-minute release. The in-memory wake match and the
  claim query MUST stay one predicate; a divergence costs latency, never a task.

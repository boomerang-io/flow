# 0007 — Deadlines, due waits and retention are durable fields swept on every instance, and a run's clock counts execution

**Status:** accepted · **Date:** 2026-10-29

## Context

A deadline held in memory is lost on restart, and nothing in memory notices a crashed claimant; a wall-clock run
deadline counts time spent waiting on an approval. A sleep resumed only by the 30 s sweep ended up to 30 s late.
Deleting a workflow must not orphan in-flight runs, finished runs must not pile up, and a run a later run points at must
outlast it.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Timers: per-run timers in memory | One instance that never restarts | Lost on restart; one instance must own each run |
| Run clock: one wall-clock deadline over the whole run (Tekton), or three clocks with a run-age ceiling | No human gates; or absolute bounds | Day-long budgets that catch nothing; or two new settings |
| Due waits: an in-memory alarm, a next-due loop or a faster watcher; or a poll every second on its own thread | Exact timing while the instance lives; or a second of lateness is fine | Per-task state or 14 faster sweeps; or one indexed query a second |
| Delete and retention: cascade at once, or a TTL index on `creationDate` | Nothing is in flight; a run is one document | Running work orphaned; dependants and lineage ignored |
| Delete and retention: a `deleted` tombstone and sweeps that cancel in-flight runs, then delete each run's dependants first | What a run owns goes with it | A pass per interval; a backlog drains over passes |

## Decision

A durable `timeoutAt` on each task run and workflow run, reaped every 30 s on every instance and fenced on `claim.seq` ;
a run whose in-flight tasks are all `waiting` gets `waitingSince` and its deadline moves forward by the time waited
(`engine/TaskRunService.java:797-822`, `engine/WorkflowRunStateHelper.java:241-313`), and reported duration excludes
waits. `DueWaitPoller` resumes due waits every `flow.watcher.wait-interval-ms` (1 s) on its own virtual thread, a resume
claiming the wait by moving `waitUntil` 60 s ahead (`engine/DueWaitPoller.java:21`, `TaskRunService.java:941`).
`WorkflowService.delete` is one guarded update to `deleted` (`workflow/WorkflowService.java:1984-1996`); the sweep
cancels the workflow's in-flight runs and then purges each run with its dependants, and `purgeExpiredRuns` runs hourly
over `completed` runs older than the `workflowrun` setting `retention.days` (365, floored at 60), keeping any run
another run names in `initiatedByRef`, 20 pages of 50 a pass (`workflow/WorkflowRetentionService.java:87-189`).

## Consequences

- Any instance reaps a past-deadline run within a sweep; a dead claimant is found sooner by its lease (decision 0023).
  Gates and waits time out terminally; only dispatched types are requeued. A gated workflow budgets for its work.
- Sleeps and lock retries end within about a second; a resume whose instance stops is retaken once its lease passes.
- Purging begins at first start and no value turns it off, so upgraders export history first. The monthly run quota is
  the larger of the audit and live counts (decision 0027); a purge on request reuses the per-run purge (decision 0045).

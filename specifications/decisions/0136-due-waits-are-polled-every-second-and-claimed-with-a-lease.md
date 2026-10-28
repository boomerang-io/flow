# 0136 — Due waits are polled every second and claimed with a lease

**Status:** accepted · **Date:** 2026-10-29

## Context

A sleep and an `acquirelock` retry park as `waiting` with a `waitUntil`. Only the 30 s watcher sweep resumed them, so
a 2 s sleep ended up to 30 s late. The resume claim cleared `waitUntil` before the task ended, so an instance that
stopped in between left the task waiting with no due time. Every other wait resumes on its own event.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. An in-memory alarm per parked wait, the sweep as fallback | Timing must be exact while the instance lives | Per-task state; up to 30 s late after a restart; the alarm is set in `TaskExecutionService` |
| B. A next-due loop per instance: sleep until the earliest `waitUntil`, nudged by a park | Sub-second timing and minimal idle queries matter | A long-lived thread, a nudge path and interrupt handling to get right |
| C. Poll due waits every second, on a thread of their own | A second of lateness is acceptable | One indexed query a second per instance, usually empty |
| D. Shorten the 30 s watcher | Every sweep should run faster | All 14 sweeps run more often for the sake of one |

## Decision

C. `DueWaitPoller` runs `resumeDueWaitingTasks` every `flow.watcher.wait-interval-ms` (1 s) on a virtual thread
(`engine/DueWaitPoller.java:21`), not on the Spring scheduler, whose fixed-delay jobs share one thread. A resume
claims a wait by moving `waitUntil` 60 s ahead instead of clearing it (`engine/TaskRunService.java:941`), and a
task's end clears `waitUntil` (`:637`).

## Consequences

- Sleeps and lock retries end within about a second of their due time.
- A claim whose instance stops is resumed by any instance once its lease passes.
- The sparse `wait_sweep` index holds only open waits; finished sleeps no longer keep a `waitUntil`.
- Revisit with B if the idle poll's load shows up across many instances, or a workflow needs sub-second timing.

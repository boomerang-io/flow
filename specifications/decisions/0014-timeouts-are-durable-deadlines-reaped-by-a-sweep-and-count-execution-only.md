# 0014 — Timeouts are durable deadlines reaped by a periodic sweep, and a run's deadline counts execution, not waiting

**Status:** accepted · **Date:** 2026-10-16

## Context

A deadline held in memory is lost on restart, and nothing in memory notices a crashed claimant. A wall-clock run deadline
also counts time spent waiting on an approval, an event or a child run, so a gated workflow needs a day-long budget that
cannot catch runaway work, while the products embedding Flow execute for an hour behind gates that sit for days.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Mechanism: per-run timers in memory | One instance that never restarts | Lost on restart; unrecoverable after a crash; one instance must own each run |
| Run clock: one wall-clock deadline over the whole run (Tekton) | No human gates | Gated workflows need day-long budgets, and the guard cannot catch runaway work |
| Run clock: keep it, add a deadline to every wait and a run-age ceiling | Installs that must bound every run absolutely | Three clocks and two new settings for what one stopped clock solves (Temporal, Kestra, Windmill) |

## Decision

A durable `timeoutAt` on each task run and workflow run, reaped by sweeps on every instance, with the run clock stopped
while the run waits. A task's deadline is its budget plus 5 s grace, baked at claim and again when it starts executing
(`service-core/src/main/java/io/boomerang/engine/RunTimeouts.java:15-19`, `engine/TaskRunService.java:332-344`, `:418-421`);
a run's is baked when it starts (`engine/WorkflowRunStateHelper.java:184-187`). `reapTaskTimeouts` and
`reapWorkflowTimeouts` are the only reapers, every 30 s (`engine/WorkflowWatcher.java:149-152`, `:218-241`); a
requeueable task with budget left is requeued, any other timed out, fenced on `claim.seq` (`:504-521`). A run whose
in-flight tasks are all `waiting` gets `waitingSince` (`TaskRunService.java:797-822`): the run sweep skips it, and when
work resumes the deadline moves forward by the time waited, as after a pause (`WorkflowRunStateHelper.java:241-313`).
A waiting task is bounded only by a timeout its author declared (`engine/TaskExecutionService.java:338-346`); reported
duration excludes waits (`RunTimeouts.java:28-42`). Outbound client templates time out at connect 10 s, idle read 60 s and
pool lease 10 s (`core/config/RestConfig.java:50-54`). A stored deadline survives a restart, and stopping it needs one field.

## Consequences

- Any instance reaps a past-deadline run within one sweep interval; a dead claimant is found sooner by its lease
  (decision 0064) and by the gone-dispatcher sweep (`WorkflowWatcher.java:450-473`).
- Gates, waits and inline system tasks time out terminally; only dispatched types are requeued (`engine/EngineConstants.java:23-31`).
  A claimed task keeps its own deadline while its run is paused or waiting (`TaskRunService.java:606-618`).
- A gated workflow budgets for its work, not its approvers; an unanswered gate keeps its run active until cancelled. A
  timeout MUST exceed the transport timeout of the work it guards. Revisit a run-age ceiling only with a reproduced case.

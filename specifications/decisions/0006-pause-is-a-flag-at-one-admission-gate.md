# 0006 — Pause is a flag enforced at one admission gate, never a run status

**Status:** accepted · **Date:** 2026-08-13

## Context

Pausing a workflow run is a product feature, but `RunStatus` is a closed enum shared with the frontend, so pause cannot
be a status value. Pause also has to say what happens to work already admitted, claimed or running.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. `PAUSED` as a `RunStatus` value | Nothing else reads the enum | Breaks the closed frontend enum; pause and outcome become entangled |
| B. A `pauseRequestedAt` flag checked at admission, in the claim query and at each transition | Pause must also stop work already admitted | Three places to keep consistent; claimed tasks stall past their deadlines |
| C. A `pauseRequestedAt` flag checked once, where a task is admitted | Work already in flight may run to completion | A paused run still finishes the tasks it had claimed or started |

## Decision

C. `pauseRequestedAt` is a timestamp on the workflow run (`service-core/src/main/java/io/boomerang/common/entity/WorkflowRunEntity.java:57`),
set by compare-and-set on a running, unpaused run (`engine/WorkflowRunStateHelper.java:223-237`). The single gate is
`TaskExecutionService.queue`, which returns before admission while the run is paused (`engine/TaskExecutionService.java:174-179`).
Resume clears the flag, moves the run deadline forward by the time paused, and calls `advance`, which queues whatever
the gate held back (`WorkflowRunStateHelper.java:241-246`, `:282-293`; `workflow/WorkflowRunService.java:884-892`). One
gate is enough because every path that creates new work passes through `queue`.

## Consequences

- Tasks already claimed or running finish, and time out on their own deadlines, regardless of pause
  (`engine/TaskRunService.java:606-618`).
- The run clock stops while paused: the run-timeout sweep skips a paused run and resume extends its deadline
  (`WorkflowRunStateHelper.java:298-313`); the stalled-run and for-each recovery sweeps skip it too
  (`:315-323`, `engine/WorkflowWatcher.java:288-302`).
- `RunStatus` stays a closed ten-value enum (`common/enums/RunStatus.java:6-16`); `PAUSED` and `SUPERSEDED` MUST NOT be added.

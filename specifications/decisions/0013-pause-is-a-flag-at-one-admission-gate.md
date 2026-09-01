# 0013 — Pause is a flag enforced at one admission gate, never a run status

**Status:** accepted · **Date:** 2026-08-13

## Context

Pausing a workflow run is a committed feature, but `RunStatus` is a closed enum shared with the frontend, so pause
cannot be a status value. An earlier design enforced pause in three places (claim-query exclusion, a transition gate
and a sweep skip); the claim-query exclusion proved redundant and needlessly held back work already in flight.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. `PAUSED` as a `RunStatus` value | nothing else reads the enum | breaks the closed frontend enum; pause and outcome become entangled |
| B. `pauseRequestedAt` flag checked at three chokepoints | pause must also stop already-admitted work | three places to keep consistent; in-flight tasks stall past their deadline |
| C. `pauseRequestedAt` flag checked once, where a task is admitted | in-flight work may run to completion | a paused run still finishes tasks already claimed or running |

## Decision

Option C. `pauseRequestedAt` is a timestamp on `WorkflowRunEntity` (`lib-common/src/main/java/io/boomerang/common/entity/WorkflowRunEntity.java:64`),
set and cleared by CAS (`service-core/src/main/java/io/boomerang/engine/WorkflowRunStateHelper.java:229-252`). The single
gate is `TaskExecutionService.queue`, which returns before admission when the run is paused
(`engine/TaskExecutionService.java:141-145`). Resume clears the flag and calls `advance`, which re-queues whatever
the gate held back (`workflow/WorkflowRunService.java:849-856`). One gate is enough because every path that creates
new work passes through `queue`.

## Consequences

- Claimed and running tasks finish and time out on their absolute deadlines regardless of pause.
- The workflow-run deadline is not reaped while paused, so a run paused past its deadline is reaped on resume
  (`WorkflowRunStateHelper.findTimedOut` `:256-269`).
- `RunStatus` stays a closed ten-value enum; `PAUSED` and `SUPERSEDED` MUST NOT be added.

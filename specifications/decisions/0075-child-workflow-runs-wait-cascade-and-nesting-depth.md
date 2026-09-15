# 0075 — A child workflow run waits, cascades and nests to a capped depth, all on the existing lineage pair

**Status:** accepted · **Date:** 2026-09-15

## Context

A `runworkflow` task submitted its child and succeeded immediately: nothing waited for the child, a cancelled
parent left its child running, and a workflow that ran itself recursed until it exhausted the workspace quota.
Real composition needs the parent to be able to take the child's outcome, and needs the two runs linked well
enough to walk in both directions.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A `parentRef` field on `WorkflowRunEntity` and a held thread per wait | the link is queried constantly and waits are short | a second lineage field beside `initiatedByRef`/`trigger`; a held thread does not survive a crash |
| B. Reuse `initiatedByRef` + `trigger`; park the task as an untimed wait; end it from the child's own transition event | the pair already carries retry and schedule lineage, and every transition is already published | one more index, and a lineage walk for the depth check rather than a stored depth |

## Decision

Option B, on all four points. The child is submitted through
`WorkflowService.submit(workflowId, request, start, initiatedByRef)` with `trigger = task` and the submitting
TaskRun's id, so the lineage stays the typed pair decision 0020 chose. `wait=true` parks the task as `waiting`
with no `waitUntil` (`TaskRunService.tryArmWait`), armed before the submit so a fast child cannot outrun it, and
`ChildWorkflowRunListener` ends it from the `WorkflowRunTransition` event through `TaskRunService.end` — the
dispatcher's own wire-level end, so its completion compare-and-set makes a redelivered event a no-op. The same
listener cancels in-flight children of a cancelled or timed-out run, and the depth walk climbs `initiatedByRef`
against the `workflowrun` setting `max.nesting.depth` (default 5) before any child is created
(`service-core/src/main/java/io/boomerang/engine/TaskExecutionService.java:794-900`,
`engine/ChildWorkflowRunListener.java`).

## Consequences

- Composition works with no new run field, no held thread and no new engine→workflow synchronous call; the
  parent's own `timeoutAt` remains the only bound on a wait.
- A cascade needs `workflow_runs {initiatedByRef, phase}` (`_0046__DeclareRunWorkflowWaitParam`), and the depth
  check costs two lookups per ancestor level, capped by the limit itself.
- Pause deliberately does not cascade — it is an admission flag on one run (decision 0013). Revisit if a paused
  parent leaving a child running is reported as a real operational problem.

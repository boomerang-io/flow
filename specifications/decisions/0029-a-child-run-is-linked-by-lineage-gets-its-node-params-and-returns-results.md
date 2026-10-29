# 0029 — A child run is linked to its task by lineage, receives only its node's params, and hands back its results

**Status:** accepted · **Date:** 2026-10-20

## Context

A `runworkflow` task submits another workflow as a child run. Composition needs the parent to wait and take the
child's outcome and results, the two runs linked both ways, a cap on a workflow that runs itself, and a rule for inputs.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Link: a `parentRef` field on the run and a held thread per wait | The link is queried constantly and waits are short | A second lineage field beside `initiatedByRef` and `trigger`; a held thread does not survive a crash |
| Link: reuse `initiatedByRef` + `trigger`; the task waits untimed and the child's transition event ends it | Every transition is already published | One more index, and a lineage walk for depth instead of a stored depth |
| Results: the resolver follows `workflowRunRef` into the child at read time | Results must be the child's live view | A reference across runs at every substitution; a purged child leaves a dangling read |
| Results: copy the child's results onto the task when the child ends | A snapshot at completion is enough | The task holds a copy; a result renamed in the child is a workflow edit here too |
| Params: only the node's params | Inputs are linked on the node | A child relying on a same-named parent param gets its own default |
| Params: every parent run param, the node's written over them | A child should see its parent's whole context | Every parent value, secrets included, reaches the child unasked, coupled by name alone |

## Decision

The lineage pair, the snapshot and the node's params. `TaskExecutionService.runWorkflow` climbs the lineage against the
`workflowrun` setting `max.nesting.depth` (5), then submits the child with `trigger=task`, `initiatedByRef` = the task
run and `start=true` (`engine/TaskExecutionService.java:1110-1197,1233-1257`). The child gets copies of the task's
params minus `workflowRef` and `wait` (`:1199-1209`) and the parent run's timeout, retries, labels and annotations, not
its workspace; saving checks that the child's latest revision declares each forwarded param
(`workflow/WorkflowService.java:1612-1642`). `wait=true` arms the task `waiting` with no `waitUntil` before the submit,
so a fast child cannot outrun it (`engine/TaskRunService.java:853-858`). `ChildWorkflowRunListener` ends the task
through the dispatcher's own end, whose compare-and-set absorbs a redelivered event: a succeeded child hands it the
run's results beside `workflowRunRef`, any other status fails it with `CHILD_RUN_FAILED`, and a cancelled or timed-out
run cancels its in-flight children (`engine/ChildWorkflowRunListener.java:93-155`). `wait=false` ends the task at once
with only `workflowRunRef`. What decided it: no new field or held thread, and results stay a snapshot like any task's.

## Consequences

- A for-each `runworkflow` (`workflow/WorkflowService.java:128-139`, decision 0032) submits a child per item with `item`
  and `index`; the parent collects results into arrays in item order (`engine/TaskExecutionService.java:852-865`).
- The cascade reads `workflow_runs {initiatedByRef, phase}` (`migration/_0021__Indexes.java:91-93`). Pause does not
  cascade (decision 0006); a parent's secrets reach a child only when its node passes them.

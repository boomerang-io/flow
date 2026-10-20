# 0118 — A waited child run hands its results to its run-workflow task

**Status:** accepted · **Date:** 2026-10-19

## Context

A `runworkflow` task that waited on its child learned only the child's status and id: the run's own results, the
values its set-property tasks wrote, stayed on the child. A workflow that fans out one child per document (a
for-each over `runworkflow`, which saving refused) had no way to read what each child produced, so the
embedding products looped inside one task instead of composing workflows.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Leave it: a downstream task reads the child by id through the API | The consumer is a program, not the next task | Every workflow that composes needs a custom task to fetch results; nothing composes with for-each arrays |
| B. The resolver follows `workflowRunRef` to the child at read time | Results must always be the child's live view | A reference crossing runs at every substitution, and a purged child leaves a dangling read |
| C. Copy the child's results onto the task when the child ends | Results are a snapshot taken once, at completion | The task carries a copy; a result renamed in the child is a workflow edit here too |

## Decision

C. When a waited child ends `succeeded`, `ChildWorkflowRunListener.endWaitingParentTask` ends the task through
the ordinary end with the child run's results beside the `workflowRunRef` it already holds
(`engine/ChildWorkflowRunListener.java:93-126`); a failed child copies nothing and fails the task as before.
A `runworkflow` task may now carry the for-each setting (`workflow/WorkflowService.java:133-143`): each item
submits one child with the task's own params, so `item` and `index`
reach the child (`engine/TaskExecutionService.childParams`), and the parent collects each result its items
produced into an array in item order, since a run-workflow task declares none of its own
(`TaskExecutionService.completeParentIfItemsTerminal`, `producedResults`). One reason decided it: a snapshot
on the task is what every other task type already is, so arrays, `$(tasks.x.results.y)` and retention need no
new rule.

## Consequences

- One child per item composes with the existing array collection and with retry from a `runworkflow` node.
- A result the child renames or drops changes what the parent reads; that is a workflow edit, as for any task.
- The combined arrays are subject to the 16 MB parent-results failure like any for-each.
- Not changed: a fire-and-forget child (`wait=false`) still ends at once with only the child's id.

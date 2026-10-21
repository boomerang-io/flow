# 0016 — A retry is a new run that re-runs from a scope and copies the task runs it keeps

**Status:** accepted · **Date:** 2026-10-16

## Context

A retry redoes what failed, or a chosen stage, without paying again for what succeeded or altering the origin's record.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Reset the chosen tasks inside the origin and advance it (Argo retry, Airflow clear) | A run is the only record anyone keeps | A finished run stops being terminal, its inputs and outputs are overwritten, and one run holds two generations of a node |
| B. Author each stage as a child workflow and re-submit the stage | A stage is a genuinely reusable workflow | Every author encodes replay in every graph and re-sequences the later stages by hand |
| C. A new run with a scope; the succeeded task runs it keeps are copied under it (Temporal reset, Dagster re-execute) | Any multi-stage workflow | One copy loop and one typed field each on run and task run; a copy's log stays on the origin |

## Decision

C. `PUT .../workflowrun/{id}/retry` takes an optional body: `scope` (`all`, `failed`, `nodes`; a closed enum, so a task
named `failed` is never read as a mode), task `nodes`, and param and label overrides merged over the origin's
(`workflow/WorkspaceWorkflowRunControllerV2.java:344-371`). `WorkflowRunService.retry` clones the origin into a new
run on the same revision, `trigger=retry`, `initiatedByRef` = the first origin across chained retries
(`workflow/WorkflowRunService.java:944-1067`). `all` plans the whole graph afresh; `failed` and `nodes` re-run each
task that neither succeeded nor was skipped, plus the named tasks, and their descendants (`:1079-1168`), and copy
every other succeeded task run, for-each items included, with its results and a `reusedFromRef` but no claim,
deadline, retry or wait state (`:1227-1271`). `DAGUtility.createTaskList` adopts the copies by name
(`engine/DAGUtility.java:151-159`) and queueing advances past them (`engine/WorkflowExecutionService.java:188-192`).
A scoped retry cancels an active origin once the new run is in place (`workflow/WorkflowRunService.java:1050-1055`).
What decided it: the origin stays an unchanged terminal record, and claiming and the graph advance see nothing new.

## Consequences

- A copy's log streams from its origin task run (`:530-533`); retention keeps a run another run names (decision 0019).
- Workspace files are not carried over: the new run gets an empty volume, and the retry dialog warns
  (`client-web/src/Features/WorkflowRun/RetryRunModal/RetryRunModal.tsx:161`). A for-each parent re-runs whole.
- Refused: naming a start or end task, or a task between an acquire-lock and a release-lock of the same key, and
  `failed` on a succeeded run (`workflow/WorkflowRunService.java:1122-1138,1197-1221`). A timed-out run with
  `retries` set is retried automatically as `all` (`engine/WorkflowExecutionService.java:243-259`).
- Not built: per-task param overrides (run params carry what changes), and a retry on the latest revision, whose
  changed graph could orphan copies matched by name.

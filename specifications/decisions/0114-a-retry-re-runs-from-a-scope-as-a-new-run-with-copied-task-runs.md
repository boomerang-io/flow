# 0114 — A retry re-runs from a scope, as a new run that copies the task runs it keeps

**Status:** accepted · **Date:** 2026-10-16

## Context

A retry always re-ran the whole graph from the start (`workflow/WorkflowRunService.java` `retry`), so a workflow
that chains parsing, model calls and review gates paid for every stage again to redo one. The products embedding
Flow asked for a "fork" from a chosen point with lineage on a task run, which the child-run cancel cascade
(`engine/ChildWorkflowRunListener.java:127-140`) would have cancelled with the origin. Twelve comparison engines
split evenly between a new run that reuses results and mutating the run in place; none calls it a fork.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Mutate the run in place: reset the chosen tasks and advance (Argo retry, Airflow clear, Prefect) | A run is the only record anyone keeps | Breaks the invariant that a completed run is terminal and that every re-run is a new run pointing back; the origin's rendered inputs and outputs, which are the embedding products' audit evidence, are overwritten |
| B. Compose the workflow from sub-workflows or decision nodes and re-submit the stage | A stage is a genuinely reusable workflow | Every stage becomes a run of its own and every author must encode replay in every graph; a retry of a stage starts a fresh child, and the caller re-sequences the stages after it itself |
| C. Extend retry with a scope; copy the kept task runs under the new run and let the plan adopt them by name (Temporal reset, Dagster re-execute, Kestra replay) | Any multi-stage workflow | One copy loop and one typed field each on run and task run; a copied task run's log and action records live on the origin |

## Decision

C. The retry body carries `scope` (`all`, `failed`, `nodes`) as a closed enum so a task named `failed` is never
read as a mode, `nodes`, and param and label overrides. Everything that did not succeed re-runs in every scope;
`nodes` adds the named tasks and their descendants. The rest is copied with results and a `reusedFromRef`, with
claim, deadline, retry and wait state dropped, before the clone is queued; `DAGUtility.createTaskList` adopts the
copies unchanged. Lineage stays `initiatedByRef` = the origin run. A scoped retry of an active origin cancels the
origin after the new run is written, as Temporal's reset terminates the original. The origin's revision is the one re-run.

## Consequences

- A carried-over task run's log is streamed from the origin (`streamTaskRunLog`); retention and purge-by-run must
  hold a run while a newer run's task runs point at it.
- Workspace files are not carried over: the new run gets an empty volume. Files a retry may split from their reader
  travel as workflow-scoped artifacts and the dialog warns; handing a retry the origin's volume waits for a need.
- A for-each parent re-runs whole: a retry resolves its items again, so copies reused by position could be wrong.
  Per-item retries belong in a child workflow fanned out by a `runworkflow` for-each; a failed child is retried alone.
- Per-task param overrides are not built: the plan sets task params from the revision in `createTaskList`, so they
  would be a `DAGUtility` change to review on its own; values that change between retries belong in run params.
- Running the latest revision is refused: copies are matched by name and a changed graph can orphan them.

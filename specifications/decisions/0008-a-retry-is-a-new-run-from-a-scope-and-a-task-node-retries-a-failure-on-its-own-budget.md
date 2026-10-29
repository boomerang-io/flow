# 0008 — A retry is a new run that re-runs from a scope, and a task node retries a reported failure on its own budget

**Status:** accepted · **Date:** 2026-10-29

## Context

A retry redoes what failed, or a chosen stage, without paying again for what succeeded or altering the origin's record.
A failure a dispatcher reported was final, so an embedder retried transient errors inside its task images: Flow showed
one attempt where several happened, and HTTP 429 was reported as `RateLimited` only to obtain a requeue.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Run: reset the chosen tasks inside the origin (Argo, Airflow), or author each stage as a child workflow | A run is the only record kept; a stage is reusable | A finished run stops being terminal; or every author encodes replay |
| Run: a new run with a scope, copying the succeeded task runs it keeps (Temporal reset, Dagster re-execute) | Any multi-stage workflow | One copy loop and one typed field each on run and task run |
| Task: keep retries inside task images | No change to Flow | Invisible attempts; a policy per image |
| Task: a node setting `retry: {max, backoff}` with a per-reason selector | Authors can tell transient from final | The container cannot set `statusReason`, so the selector cannot separate them |
| Task: the same setting without a selector; the engine never retries a pointless cause and records each attempt | The author bounds attempts, Flow decides what is pointless | Two budgets on `retry`; an attempt history on the run |

## Decision

A new run from a scope, and a selector-less node retry. `PUT.../workflowrun/{id}/retry` takes `scope` (`all`, `failed`,
`nodes`), task `nodes`, and param and label overrides (`workflow/WorkspaceWorkflowRunControllerV2.java:344-371`);
`WorkflowRunService.retry` clones the origin on the same revision with `trigger=retry` and `initiatedByRef` = the first
origin, re-runs the scoped tasks and their descendants and copies every other succeeded task run with its results and a
`reusedFromRef` (`workflow/WorkflowRunService.java:944-1271`). `retry.max` and `retry.backoff` are copied from the node
onto the task run (`engine/DAGUtility.java:312-323`); a reported `failed` end is requeued while `retry.failures` is
below `retry.max` and the reason is not in `StatusReason.NOT_RETRIABLE`, could-not-start reasons keep the engine's own
budget, every requeue pushes the superseded attempt onto `attempts`, and a dispatcher marks a final failure with
`retryable: false` (`engine/TaskRunService.java:830`, `:1175-1223`).

## Consequences

- The origin stays an unchanged terminal record; a copy's log streams from it, and retention keeps a run another run names.
- Workspace files are not carried over (the retry dialog warns); a for-each parent re-runs whole. Refused: a start or end
  task, a task between an acquire-lock and its release, and `failed` on a succeeded run. A timed-out run with `retries`
  set is retried as `all` (`engine/WorkflowExecutionService.java:243-259`).
- An author's retries are bounded, backed off and visible as attempts. Not built: per-task param overrides and a retry
  on the latest revision.

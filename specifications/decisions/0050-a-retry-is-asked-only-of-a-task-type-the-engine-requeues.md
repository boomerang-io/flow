# 0050 — A retry is asked only of a task type the engine requeues

**Status:** accepted · **Date:** 2026-11-02

## Context

A node's `retry` is applied by requeueing its failed task run, and the engine requeues only the types a dispatcher runs. Save accepted `retry` on any node, so on a `runworkflow`, a `sleep` or another engine-run type it was stored and never applied: a failed child run failed its task on the first attempt.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Retry a failed child run: submit a new child, with lineage to the first, after the node's backoff | The child run is the unit that should retry | A new engine path for an engine-run type; the child's own task retries and the node's compound |
| B. Refuse `retry` on a type the engine never requeues, at save | Retries belong to the tasks that run | A definition saved through the API with such a retry must drop it |
| C. Document it only | — | The setting keeps looking like it works |

## Decision

B. Save refuses a node asking for retries (`retry.max` above 0) whose type is not in `EngineConstants.REQUEUEABLE_TYPES`, with `WORKFLOW_INVALID_TASK_RETRY` (1223) naming the task (`workflow/WorkflowService.java:544-556`, `engine/EngineConstants.java:32-40`). A run-workflow task's retries are declared on the tasks of the workflow it runs, where they already work; no caller needed the child run itself retried, so A would be built ahead of need. The editor already offers retry only on the requeueable types (`client-web/src/Features/Reactflow/components/shared/foreach.ts:24-32`).

## Consequences

- A stored `retry` is always one the engine applies.
- A child workflow that should retry declares it on its own tasks.
- Revisit with option A when a workflow needs its whole child run retried as one unit, not its tasks.

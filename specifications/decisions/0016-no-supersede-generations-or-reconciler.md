# 0016 — Supersede generations and a separate reconciler are not built; retry creates a new run

**Status:** accepted · **Date:** 2026-08-21

## Context

The reconciler analysis designed `attempt`/`supersededAt`/`supersededBy` fields on task runs, a partial unique index
on the live generation, and a `reconcile()` component to re-drive part of a workflow run in place. What shipped is
full materialisation of the DAG at queue time and a level-triggered advance from persisted state.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Build supersede generations and a reconciler class | in-place partial re-run of a workflow run is a product requirement | new fields, a partial unique index, and a second live generation of a node to disambiguate everywhere |
| B. Keep two-pointer retry (a new workflow run) and treat "reconcile" as a property of the advance | no in-place re-run requirement | a retried run is a new run id; per-step re-run inside one run is not possible |

## Decision

Option B. `WorkflowRunService.retry` clones the run as a NEW workflow run with `trigger=retry` and
`initiatedByRef` pointing at the origin (`service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:897-935`),
so no second live generation of a node exists within one run. `TaskExecutionService.advance` (`engine/TaskExecutionService.java:515-529`)
re-applies the graph advance from persisted state and is what the watcher and resume call; there is no `reconcile()`
entry point. No field named `supersededAt`, `supersededBy` or `attempt` exists on `TaskRunEntity`.

## Consequences

- Re-running a workflow always produces a new run record; lineage is on typed fields, not annotations.
- The `task_runs {workflowRunRef, name}` uniqueness holds without a live-generation discriminator.
- Trigger to revisit: in-place partial re-run of one workflow run becomes a requirement; the design in
  the earlier design is in git history (`specifications/reconciler-analysis.md` §3, before 2026-09-01) and is the starting point.

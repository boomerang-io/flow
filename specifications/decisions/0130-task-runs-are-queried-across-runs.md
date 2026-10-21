# 0130 — Task runs are queried across runs, as slim rows

**Status:** accepted · **Date:** 2026-10-21

## Context

An embedder's unit of work spans several runs: a parent and one child run per for-each item. Its progress view, its
execution record and its admin listing need every task run of that work, and task runs across many of them filtered
by status or name. A run read returns one run's task runs, so a listing took one call per run.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. `GET …/taskrun/query`: task runs paged by label, run, workflow, task name, status and date, each row without params, results, spec, annotations or message | Task rows across runs, polled | A route; a row's message and results need the run read |
| B. `withTasks` on the run query | A page of whole runs | Every task document in full, about 1–5 MB for one analysis whose AI tasks carry rendered prompts, each run's secret values scrubbed; pages count runs, and tasks cannot be filtered |
| C. A run read with its child runs | One unit of work | Whole documents again, and no listing across units |
| D. A listing on the Insights task route | Finished task runs only | No in-flight state; needs the Insights permission |

## Decision

A (`service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:326`), shaped like the run query. It
needs `labels` or `workflowruns`, the two ways task runs are indexed, and a row leaves out every part that can carry
a secret value, so no row is scrubbed (`:410`). The public task run gains `attempt`, derived from the hidden retry
count, so a row shows how often a task was requeued.

## Consequences

- One label finds a unit's task runs, children included, since task runs carry their run's labels.
- A query by workflow alone is refused; revisit with an index on the task run's workflow if a listing needs it.
- An earlier attempt's end reason is not kept; the count and the final reason are.

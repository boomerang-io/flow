# 0042 — Task runs are queried across runs, as slim rows

**Status:** accepted · **Date:** 2026-10-21

## Context

An embedder's unit of work spans several runs: a parent and one child run per for-each item. Its progress view, its
execution record and its admin listing need every task run of that work, filtered by status or name, and a run read
returns only one run's task runs.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A task-run query paged by label, run, workflow, task name, status, phase and date, each row without the parts that can carry a secret | Task rows across runs, polled | One more route; a row's message and results need the run read |
| B. `withTasks` on the run query | A page of whole runs | Every task document in full, megabytes once `ai` tasks carry rendered prompts, each run scrubbed; pages count runs and tasks cannot be filtered |
| C. A run read with its child runs | One unit of work | Whole documents again, and no listing across units |
| D. A listing on the Insights task route | Finished task runs only | No in-flight state; needs the Insights permission |

## Decision

A: `GET /api/v2/workspace/{workspace}/taskrun/query` (`workflow/WorkspaceTaskRunControllerV2.java:37`,
`workflow/WorkflowRunService.java:326-376`), shaped like the run query. It needs `labels` or `workflowruns`, the two ways
task runs are indexed (`:339-343`), and a row leaves out params, results, spec, annotations and status message, so no
row is scrubbed (`:362-365,408-417`). A task name also matches its for-each items. The public task run carries
`attempt`, derived from the hidden retry count (`common/model/TaskRun.java:60-71`), so a row shows how often a task was
requeued.

## Consequences

- One label finds a unit's task runs, children included, since task runs carry their run's labels and a child its
  parent's.
- A query by workflow alone is refused; revisit with an index on the task run's workflow if a listing needs it.
- An earlier attempt's end reason is not kept; the count and the final reason are.

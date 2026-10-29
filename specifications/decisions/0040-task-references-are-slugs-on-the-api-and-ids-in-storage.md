# 0040 — Task references are slugs on the API and ids in storage

**Status:** accepted · **Date:** 2026-10-19

## Context

A workflow node names its task as `<slug>` for a global task or `<workspace>/<slug>` for a workspace task, while a
dispatcher that routes by task needs that same readable form on the task runs it is handed. Task names are unique only
within a workspace, and every stored node and run already holds the task's id.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Store slugs everywhere | Names are unique platform-wide | Workspace task names are not; every stored node and run, and the v3 migration, rewritten |
| B. Ids everywhere, slugs only on workflow nodes | Nothing outside Flow routes by task | Every dispatcher keeps an id-to-slug cache; two forms of one reference on the API |
| C. Ids in storage, the slug form at every edge | References must stay stable and readable | One translation on every read and save that carries task references |

## Decision

C. `TaskService.slugsByRef` turns ids into slug forms in at most three queries per batch, cached for 30 seconds, and
`refsBySlug` turns slug forms back into ids for a request scoped to a workspace (`workflow/TaskService.java:109-112,611,656`).
Workflow reads and saves, run reads, Insights rows, status events and the dispatcher's poll, start and end answers go
through them (`workflow/WorkflowService.java:1142,1207`, `workflow/WorkflowRunService.java:177`,
`workflow/WorkflowRunInsightService.java:266`, `event/EventSinkService.java:76`, `dispatcher/DispatcherService.java:120`),
and the claim filter takes the same form (`dispatcher/ClaimFilterService.java:87-99`). A task is always found by id.
Nothing stored changes, which decided it.

## Consequences

- No migration: every stored `taskRef` is an id, including those the v3 chain writes.
- A dispatcher routes on the slug its handlers are registered for; a task's name is immutable, so its slug form is
  stable for the task's life. A renamed workspace changes its tasks' slug form, and a cached form follows within 30
  seconds.
- A slug form naming another workspace's task, or no task, refuses the save with `WORKFLOW_INVALID_TASK_REF`
  (`workflow/WorkflowService.java:1199-1215`).
- Revisit if task names become unique platform-wide: then the slug alone could be the stored key.

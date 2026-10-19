# 0119 — Task references are slugs on the API and ids in storage

**Status:** accepted · **Date:** 2026-10-19

## Context

A workflow node's `taskRef` already read and wrote as the task's slug form, `<slug>` for a global task and
`<workspace>/<slug>` for a workspace task, while a task run's `taskRef` went out on the API and to every
dispatcher as the task's id, so an embedding dispatcher that routes by task had to keep its own id-to-slug cache.
A `flow.uniquenames.enabled` switch that made the name the stored key had shipped off and was never switched on.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Store slugs everywhere | Names are unique platform-wide | Workspace task names are unique only per workspace; every stored node and run, and the v3 migration, rewritten |
| B. Ids everywhere, slugs only on workflow nodes | Nothing outside Flow routes by task | Every dispatcher keeps an id cache; two forms of one reference on the API |
| C. Ids in storage, the slug form at every edge | References must stay stable and readable | One translation on every read and save that carries task references |

## Decision

C. `TaskService.slugsByRef` turns ids into slug forms in one node read and one parent read for any batch, cached
for 30 seconds, and `refsBySlug` turns slug forms back into ids on save (`workflow/TaskService.java:707,752`).
Workflow reads and saves, run reads, insight rows, status events and the dispatcher's poll, start and end answers
all go through them, and the claim filter takes the same form (`dispatcher/ClaimFilterService.java:89`). The
switch is removed and a task is always found by id. Nothing stored changes, which decided it.

## Consequences

- No migration: every stored `taskRef` is already an id, including those the v3 chain wrote.
- A dispatcher routes on the slug its handlers are registered for; a task's name is immutable, so a slug form is
  stable for the task's life.
- A renamed workspace changes its tasks' slug form; a cached form follows within 30 seconds.
- A slug form naming another workspace's task is refused on save rather than resolved in the caller's workspace.
- Revisit if task names become unique platform-wide: then the slug alone could be the stored key.

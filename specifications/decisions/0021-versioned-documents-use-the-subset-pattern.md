# 0021 — Versioned documents use the subset pattern (parent plus revision documents)

**Status:** accepted · **Date:** 2023-08-14

## Context

Workflows and tasks are versioned, and a workflow commonly reaches 25 versions while a task reaches 10 or more.
Earlier releases mixed two shapes: tasks embedded a `revisions[]` array on one document, while workflows kept a
separate revisions collection. Both leaked the storage shape into the user-facing model.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One document with an embedded `revisions[]` array | A handful of versions | Documents grow without bound and cannot be paged or queried per version |
| B. One document per version, repeating the common fields | Versions are fully independent | No single home for status and other stable fields; every save duplicates them |
| C. Subset pattern: a parent with the stable fields and one child per version, joined on read | Stable fields change rarely and versions are appended | One extra read per lookup; the join lives in the domain service |

## Decision

Option C. `WorkflowEntity` → `workflows` plus `WorkflowRevisionEntity{workflowRef, version, tasks, params, …}` →
`workflow_revisions` (`lib-common/src/main/java/io/boomerang/common/entity/WorkflowRevisionEntity.java:24-38`);
`TaskEntity` → `tasks` plus `TaskRevisionEntity{parentRef, version, spec, …}` → `task_revisions`
(`TaskRevisionEntity.java:23-34`). The domain services perform the join
(`service-core/src/main/java/io/boomerang/workflow/TaskService.java:57-60`). A new version is always a new child
insert, so the parent is never rewritten for it and per-version queries stay small.

## Consequences

- Versions are pageable and indexable on `(parent, version)`; the loader creates those indexes (`_0033__DefinitionIndexes`).
- Every read of a definition is two lookups; deleting a parent MUST also delete its revisions.
- `workflow_templates` is the one collection still carrying `version` on a single document.

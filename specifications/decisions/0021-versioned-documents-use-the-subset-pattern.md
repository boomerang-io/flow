# 0021 — Versioned documents use the subset pattern: a parent plus one revision document per version

**Status:** accepted · **Date:** 2026-08-14

## Context

Workflows and tasks are versioned: every save that is not a replace adds a version. The stable fields (name, status,
labels) belong to the definition, while the body (tasks, params, spec) belongs to one version, and the storage shape
must not leak into the public model.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One document with an embedded `revisions[]` array | A handful of versions | The document grows without bound; versions cannot be paged or queried |
| B. One document per version, repeating the stable fields | Versions are fully independent | No single home for status; every save duplicates the stable fields |
| C. A parent with the stable fields and one child per version, joined on read | Stable fields change rarely and versions are appended | Two reads per lookup; the join lives in the domain service |

## Decision

Option C. `WorkflowEntity` in `workflows`
(`service-core/src/main/java/io/boomerang/common/entity/WorkflowEntity.java:18-30`) pairs with
`WorkflowRevisionEntity{workflowRef, version, tasks, params, …}` in `workflow_revisions`
(`common/entity/WorkflowRevisionEntity.java:21-37`); `TaskEntity` in `tasks` pairs with `TaskRevisionEntity{parentRef,
version, spec, …}` in `task_revisions` (`common/entity/TaskRevisionEntity.java:23-34`). The domain services join them
(`workflow/WorkflowService.java:1252-1268`, `workflow/TaskService.java:581-599`). A new version inserts a child; a
replace rewrites the latest child in place (`WorkflowService.java:1782-1796`, `TaskService.java:804-827`).

## Consequences

- Versions are pageable and indexed on parent and version: `workflow_ref_version` and `parent_ref_version`
  (`migration/_0021__Indexes.java:99-110`).
- Every definition read is two lookups, and deleting a parent MUST delete its revisions (`TaskService.java:985-986`,
  `workflow/WorkflowRetentionService.java:153-155`).
- `workflow_templates` is the one collection that keeps `version` on a single document
  (`common/entity/WorkflowTemplateEntity.java:30`).

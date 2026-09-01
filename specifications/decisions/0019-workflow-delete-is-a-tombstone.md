# 0019 — Workflow delete is a tombstone swept by the watcher

**Status:** accepted · **Date:** 2026-07-23

## Context

Deleting a workflow used to cascade through its revisions, schedules and runs with no guard for runs still in flight —
a data-loss bug that could orphan running work.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Refuse delete while unfinalised runs exist | a stopgap before the sweep exists | users cannot delete a workflow with a stuck run |
| B. A `tombstonedAt` timestamp plus a cascade later | a separate field is preferred over a status value | a second field to index and query alongside status |
| C. `WorkflowStatus.deleted` set by CAS; the watcher cancels in-flight runs; a retention sweep prunes later | any deployment | nothing is physically removed until a retention policy exists |

## Decision

Option C. `WorkflowService.delete` is a single guarded status update to `deleted`
(`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java:1831-1843`); submit already rejects a
non-active workflow (`:1722`). `WorkflowWatcher.cancelDeletedWorkflowRuns` cancels the workflow's in-flight runs through
the normal cancel path (`engine/WorkflowWatcher.java:248-263`), and `pruneDeletedWorkflows` is a gated no-op behind
`flow.watcher.retention.enabled` (`:85-86`, `:269-274`). A status marker needs no new field and lets the existing
sweep machinery wind work down safely.

## Consequences

- Running work is never orphaned; a deleted workflow's runs finish as `cancelled`.
- Deleted workflows and their runs remain in the database until a retention policy is decided and the prune sweep
  is implemented.

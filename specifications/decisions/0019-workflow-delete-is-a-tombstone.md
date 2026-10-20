# 0019 — Workflow delete is a tombstone: the watcher cancels its runs, then prunes it

**Status:** accepted · **Date:** 2026-07-23

## Context

Deleting a workflow used to cascade through its revisions, schedules and runs with no guard for runs still in flight —
a data-loss bug that could orphan running work. The first replacement tombstoned the workflow and left the prune half
as a no-op behind a retention flag, so a deleted workflow's documents accumulated forever.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Refuse delete while unfinalised runs exist | a stopgap before the sweep exists | users cannot delete a workflow with a stuck run |
| B. A `tombstonedAt` timestamp plus a cascade later | a separate field is preferred over a status value | a second field to index and query alongside status |
| C. `WorkflowStatus.deleted` set by CAS; the watcher cancels in-flight runs; a prune sweep gated on a retention policy | age-based run retention is a designed feature | deletion never completes by default; the flag guards a policy that does not exist |
| D. As C, but the prune sweep hard-deletes unconditionally once no run is in flight | delete means delete; run history for deleted workflows is not a kept promise | pruned run documents are unrecoverable; only the audit trail survives |

## Decision

Option D. `WorkflowService.delete` is a single guarded status update to `deleted`
(`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java:1918-1927`), and submit rejects a non-active
workflow. `WorkflowWatcher.cancelDeletedWorkflowRuns` cancels the workflow's in-flight runs through the normal cancel
path (`engine/WorkflowWatcher.java:321`); `WorkflowRetentionService.pruneDeletedWorkflows` (`workflow/WorkflowRetentionService.java:134`), run by the watcher, pages deleted workflows, skips any with a
run still in flight, and hard-deletes the artifacts, task runs, workflow runs, revisions, leftover actions, schedules
and relationship node, then the workflow document. Audit records are never touched. A status marker needs no new
field and lets the existing sweep machinery wind work down safely; a retention policy, if ever wanted, is a feature
to design, not a switch guarding a no-op.

## Consequences

- Running work is never orphaned; a deleted workflow's runs finish as `cancelled`, and its documents leave the
  database within a sweep interval of its runs finalising.
- Pruned runs leave the live counts, so the monthly quota counts audit events instead (decision 0072); the audit
  trail is the surviving record of them.
- Age-based retention for live workflows' runs remains unbuilt; revisit when a deployment asks for it.

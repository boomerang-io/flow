# 0072 — Monthly run quotas count audit events so deletion cannot reset them

**Status:** accepted · **Date:** 2026-09-03

## Context

The monthly run quota counted live `workflow_runs` documents, so deleting a Workflow (which
removes its relationship node and, once pruned, its run documents) reset the count and freed the
quota mid-month. The audit trail now records one `CREATE` event per WorkflowRun admission
(`core/audit/WorkflowRunAuditBridge.java`), and those events outlive both the run documents and
the Workflow.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep counting live run documents | Deletion resetting the quota is acceptable | A delete/recreate loop grants unlimited monthly runs |
| B. Count only audit events | The audit write were synchronous and guaranteed | It is async best-effort — a just-admitted run may not be audited yet, and capture can be disabled |
| C. `max(audit count, live count)` | Both sources are individually incomplete in opposite directions | Slight over-count is impossible (max, not sum); audit-off installs degrade to A |

## Decision

Option C. `WorkspaceService.setCurrentQuotas` sets the monthly counter to the maximum of the
month's `{workspaceId, action=CREATE, resourceType=workflowrun}` audit events
(`core/audit/AuditQueryService.countRunsCreated`, served by the `workspace_time` index) and the
month's live run documents (`workflow/WorkflowRunService.countForQuota`). The live count covers
the async-audit race; the audit count covers deletion. The concurrent counter stays purely live —
it measures what is running now, which deletion legitimately reduces. TaskRun transitions are not
audited at all: their volume would dwarf the trail and no consumer (quota or insights) reads them.

## Consequences

- Deleting and recreating a Workflow no longer resets the monthly quota; the admit-then-recheck
  guard inherits the same counter.
- Audit retention is floored at 60 days (`AuditRetentionService.MIN_RETENTION_DAYS`) so the month
  window always holds.
- An install with audit capture disabled falls back to the live count alone — deletion resets the
  quota there. Revisit if that becomes a support issue (the fix is forcing run-lifecycle capture).
- Runs marked invalid before admission publish no transition and are never audited; they count
  only while their documents live.

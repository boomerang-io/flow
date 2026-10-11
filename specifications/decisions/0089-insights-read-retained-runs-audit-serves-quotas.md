# 0089 — Insights read the runs a workspace still holds; the audit trail serves quotas only

**Status:** accepted · **Date:** 2026-09-30

## Context

Insights replayed the audit trail's workflow-run events, whose payload carries only status, phase, duration and
workflow (`service-core/src/main/java/io/boomerang/core/audit/WorkflowRunAuditBridge.java:112-124`), and handed the
browser one summary per run to aggregate. That kept counting a deleted workflow's runs, but could not say why a run
failed, which task took the time, how it was triggered or how long it waited, and it shipped every run over the wire.
The monthly run quota reads the same audit events and must keep counting deleted runs
(`core/audit/AuditRetentionService.java:6-9` floors the retention at 60 days for that reason).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the audit source and widen its payload (trigger, reasons, task facts) | Deleted workflows must stay in the statistics | Every transition writes more; task-level facts would need task-run events, which are not audited for volume |
| B. Aggregate server-side from the WorkflowRun and TaskRun records, audit trail for quotas only | Statistics may be honest to retention | A deleted workflow's runs leave the statistics; the page says so |
| C. Union both: retained records for detail, audit for totals | Both properties at once | Two sources for one number, which disagree whenever retention differs |

## Decision

B. `WorkflowRunInsightService` (`service-core/src/main/java/io/boomerang/workflow/WorkflowRunInsightService.java`)
computes the page's statistics from projected run and task-run records, and the page subtitle states that deleted
workflows are not included. `InsightsService` keeps its audit roll-up untouched for `WorkspaceService.setCurrentQuotas`.
Comparable products (GitHub Actions, GitLab CI, AWS Step Functions) draw the same line: analytics over retained
executions, metering counters written at event time.

## Consequences

- Every statistic on the page comes from fields the records already carry: no data-model change, no new index.
- A workflow deleted mid-period drops out of Insights while its runs keep counting toward the month's quota;
  the two numbers can legitimately differ.
- Revisit if a run-retention setting arrives: the subtitle should then quote it, and a rollup collection
  (option C's cost without its ambiguity) becomes worth considering only with a load test showing the projection
  reads too slow.

# 0034 — Insights read the runs and task runs a workspace still holds and group task runs on one route; the audit trail serves quotas only

**Status:** accepted · **Date:** 2026-10-21

## Context

Insights must say why runs failed, which task took the time, how runs were triggered and how long they waited; an
embedder needs task timings and usage per model, agent, task kind and time bucket over an hour to 90 days. The audit
trail's run events carry only status, phase, duration and workflow (`core/audit/WorkflowRunAuditBridge.java:113-126`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Source: A. The audit trail, its payload widened | Deleted workflows must stay in the statistics | Every transition writes more; task facts need task-run events, not audited for volume |
| Source: B. The retained run and task-run records, aggregated server-side | Statistics may follow retention | A deleted workflow's runs leave Insights while still counting toward quotas |
| Source: C. Both, records for detail and audit for totals | Both properties at once | Two sources for one number, which disagree whenever retention differs |
| Grouping: D. One route by `task`, `type`, `workflow` or a label, with usage per row | Callers group by what Flow owns or by labels | A dimension Flow does not own must reach a label first |
| Grouping: E. D plus grouping by a named param | Dimensions live only in task inputs | A param can carry a secret, even inside an object; a `model` param is the model asked for, not served |
| Grouping: F. Purpose-built routes per model, per agent, slowest | One consumer whose keys never change | Flow names an embedder's concepts; each new question needs a route |
| Grouping: G. Percentiles in MongoDB (`$percentile`) | Windows too large to stream | Approximate, needs MongoDB 7.0, disagrees with the summary's tiles |

## Decision

B and D. `WorkflowRunInsightService` computes the summary and workflow detail from projected records
(`workflow/WorkflowRunInsightService.java:108-176`); `GET /api/v2/workspace/{workspace}/insights/tasks` groups by
`task`, `type`, `workflow` or `label:<key>`, the server's model and item labels included (decision 0009), optionally by
hour, day or month and by the span of a run label (`:188-260`). One aggregation streams a small document per counted
task run, with its labels and the usage an `ai` task reports, on the `workflowRunRef`-first index (`:375-410`), and
percentiles use the summary's nearest-rank method in Java (`:635-643`). Quotas count from audit events
(`core/audit/AuditQueryService.java:153,181`). What decided it: analytics over retained executions and metering at
event time, the line GitHub Actions and Step Functions draw, with labels as the grouping key.

## Consequences

- Insights cover only the runs retention keeps (`workflowrun` `retention.days`, 365 by default), so they can differ
  from the quota's monthly count; the page says deleted workflows are excluded (`client-web/src/Features/Insights/Insights.tsx:286-287`).
- A request reads every counted task run of the period and truncates past 1,000 rows
  (`workflow/WorkflowRunInsightService.java:75,250-251`); revisit with G or a rollup collection on a load test.

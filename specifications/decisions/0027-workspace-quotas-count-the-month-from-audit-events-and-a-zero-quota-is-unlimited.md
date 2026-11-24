# 0027 — Workspace quotas count the month from audit events, exempt listed workflows and child runs, and read 0 as unlimited

**Status:** accepted · **Date:** 2026-11-24

## Context

A workspace's monthly figures must survive deleting a workflow and its runs, must charge the task time a workspace used and not only how many runs
it started, and must let an embedding product run its own housekeeping workflows without spending its tenants' month. A stored quota must say both
"no limit" and "the platform default", and no quota may stop a run already going.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Count the month from live run documents, or from audit events alone | Deletion may reset it; or the audit write is guaranteed | A delete-and-recreate loop grants unlimited runs; or a just-admitted run is not yet audited |
| Count runs as the larger of audit `CREATE` events and live runs; minutes from the completion's `UPDATE` event | Each source misses what the other holds | With audit capture off the minutes are not counted and the runs degrade to the live count |
| Charge wall-clock run time; or each task's start-to-end time, summed | Runs are serial; or parallel tasks cost the operator each | Parallel work is undercounted; or a run can use more minutes than it lasted |
| Exempt workflows by label or pattern; or by exact name in the system workspace | Tenants are trusted; or only an operator decides | Anyone with write access to a workflow exempts it; or anyone with write access to `system` |
| A sentinel such as `2147483647` for no limit; or absent inherits, `0` unlimited, positive the limit | No editor needs "no limit"; or operators need both | A magic number on every screen; or a v3 `0` must be mapped on upgrade |

## Decision

Counts from audit events, minutes per task, exact names, and `0` unlimited. The engine writes one `CREATE` per admitted run and one `UPDATE` at completion
(`core/audit/WorkflowRunAuditBridge.java:74-157`), keyed on the workspace's id, so a rename keeps the month, and kept at least 60 days. The month's runs are
`max(CREATE events, live runs less refused-submit entries)`; its minutes are the `UPDATE` events' `cumulativeDuration`, each run rounded up to the minute
(`core/audit/AuditQueryService.java:211-274`): every dispatched task's attempts from start call to end, a for-each parent, a carried-over task, a wait
and an attempt that never started giving nothing (`core/audit/CumulativeDuration.java:39-89`). The `quotas` setting `unmetered.workflows` lists system
workflows whose runs, and their links' runs, use none of the month: marked on both events, left out of the live count, never refused by either monthly
check (`core/UnmeteredWorkflows.java:61-130`, `workflow/QuotaService.java:416-497`). A child run is never checked against either monthly quota, so a
parent is never stopped halfway, and still counts (`workflow/WorkflowService.java:2319-2334`). The queue caps the runs waiting for a workspace slot
(`max.workflowrun.queued`) and ends one waiting longer than `workflowrun`/`queue.max.age` (`engine/WorkflowWatcher.java:125-126`). A stored quota
overlays the platform default only where present (`workflow/QuotaService.java:191-222`); a check enforces it only when positive
(`workspace/model/Quotas.java:10-13`). A quota refuses new runs with `429`; none cancels running work. What decided it: counts written at event time
survive deletion, and minutes per task are what the cluster spent.

## Consequences

- A month can overshoot its minutes by the runs going when it filled. A workflow added to the list mid-month keeps its earlier runs counted (the audit
  count); one removed brings its runs back through the live count, its minutes staying free.
- Anyone with write access to `system` can create a workflow of a listed name. The `system` workspace stores `0` for its counts and minutes.

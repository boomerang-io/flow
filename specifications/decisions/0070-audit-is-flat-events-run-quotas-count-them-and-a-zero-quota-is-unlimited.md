# 0070 — Audit is flat per-event documents captured by annotation; monthly run quotas count them, and a workspace quota of 0 is unlimited

**Status:** accepted · **Date:** 2026-10-15

## Context

The audit trail must say who did what, when and with what outcome, expire, and be countable; a monthly run quota must
survive deleting a workflow and its runs; and a stored quota must say both "no limit" and "the platform default".

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Audit: A. One record per object with an embedded event list, captured by string pointcuts on services | Nothing consumes audit | Unbounded arrays; pointcuts drift silently after a package move; no query surface |
| Audit: B. `@Audited` on controllers, one flat event per attempt, levels applied at capture | Audit must be queried, counted and expired | Each endpoint needs its annotation |
| Monthly count: C. Live run documents only, or audit events only | Deletion may reset the quota; or the audit write is guaranteed | A delete-and-recreate loop grants unlimited runs; or a just-admitted run is not yet audited |
| Monthly count: D. The larger of the two | Each source misses what the other holds | With audit capture off it degrades to the live count |
| Quota value: E. A sentinel such as `2147483647` for no limit | No workspace needs "no limit" from the editor | A magic number on every screen; `0` means two things |
| Quota value: F. Absent inherits, `0` is unlimited, a positive number is the limit | Operators need both "no limit" and "the default" | A v3 `0`, enforced there as a limit of zero, must be mapped on upgrade |

## Decision

B, D and F. `AuditAspect` records one event per `@Audited` attempt, SUCCESS, FAILED or DENIED
(`core/audit/AuditAspect.java:55-89`), when the `audit` group is enabled at a `level` (DESTRUCTIVE, WRITE, ALL;
`core/audit/AuditLevel.java:14-28`) the site meets, and writes it asynchronously, best effort
(`core/audit/AuditEventWriter.java:27-40`). Events are flat documents in `audit`, indexed by workspace and time
(`migration/_0021__Indexes.java:160-168`), expiring after `retentionDays`, floored at 60 so a month always holds
(`core/audit/AuditRetentionService.java:28,67-84`). The engine's transition event writes one CREATE per admitted run and
one UPDATE at completion (`core/audit/WorkflowRunAuditBridge.java:58-110`); task runs are not audited, for volume.
`QuotaService` counts the month's runs as the larger of CREATE events and live run documents, and concurrent runs live
(`workflow/QuotaService.java:222-241`). A stored quota overlays the `quotas` group's default only where present
(`:130-161`), and its checks enforce it only when positive (`workspace/model/Quotas.java:10-13`). What decided it:
counts written at event time survive deletion, and `0` already means "no limit" to Tekton and Temporal users.

## Consequences

- Deleting and recreating a workflow does not reset the monthly quota, except with audit capture off. A v3 database's
  per-object audit records are not carried over, and raising the level is not retroactive.
- The `system` workspace carries `0` for its four counts; a v3 `0` is dropped on upgrade, so that workspace inherits
  the default (`migration/_0007__V3MigrateWorkspaces.java:250-252`). A stored `0` and a default of `0` look the same.

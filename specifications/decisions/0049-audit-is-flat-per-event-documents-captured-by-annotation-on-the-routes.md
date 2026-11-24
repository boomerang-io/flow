# 0049 — Audit is flat per-event documents, captured by an annotation on the routes and written asynchronously

**Status:** accepted · **Date:** 2026-10-15

## Context

The audit trail must say who did what, when and with what outcome, be listed and counted by workspace, actor and resource, and expire. The monthly
quotas also read it, so its events must outlive the runs and workflows they describe. v3 kept one record per object with an embedded event list.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| One record per object with an embedded event list, captured by string pointcuts on services | Nothing consumes the trail | Unbounded arrays; a pointcut drifts silently after a package move; no query surface |
| `@Audited` on controller methods, one flat event per attempt, the capture level applied when the event is taken | The trail is queried, counted and expired | Each mutating route needs its annotation |
| A synchronous write in the request | An event must never be lost | Every audited request waits on a second write, and a failed write fails the request |
| An asynchronous, best-effort write | The trail is a record, not a ledger | An event can be lost on a crash; a reader must not assume one is there yet |

## Decision

Flat events captured by annotation, written asynchronously. `AuditAspect` records one event per `@Audited` attempt, SUCCESS, FAILED or DENIED,
a refused authentication or authorization being DENIED (`core/audit/AuditAspect.java:56-114`), only when the `audit` settings group is enabled at a
`level`, DESTRUCTIVE, WRITE or ALL, that the site meets (`core/audit/AuditLevel.java:14-28`). The engine's run transitions are recorded the same way,
without a route (`core/audit/WorkflowRunAuditBridge.java:74-159`); task runs are not audited, for volume. `AuditEventWriter` saves each event on the
async executor and logs a failure rather than raising it (`core/audit/AuditEventWriter.java:27-40`). Events are documents in `audit`, indexed by time,
workspace and time, actor and time, and resource (`migration/_0021__Indexes.java:175-187`). An event names its workspace by id, which a rename never
changes, with its name at the time beside it for display; a site that names the workspace by its path is resolved to the id before the call, so a delete
or a rename records the workspace as it was (`core/audit/AuditAspect.java:62-91`, `core/audit/AuditEventEmitter.java:47-60`). Events expire after `retentionDays`, floored at 60 so a month
always holds (`core/audit/AuditRetentionService.java:29,52-103`). What decided it: an event per attempt is what a reader asks for, and an annotation on
the route cannot drift from the route.

## Consequences

- A reader of the trail, the monthly quotas among them, must allow for an event not written yet; they pair it with a live count. Raising the level is
  not retroactive, and a v3 database's per-object records are not carried over.
- A renamed workspace keeps its trail and the figures read from it; a workspace created under a deleted one's name starts with none of its history,
  which stays under the deleted workspace's id. Events recorded before 5.0.1 were re-keyed from names to ids on upgrade, except under a name two
  workspaces held, which names neither. During a rolling upgrade an older instance still writes names: run one instance while upgrading.

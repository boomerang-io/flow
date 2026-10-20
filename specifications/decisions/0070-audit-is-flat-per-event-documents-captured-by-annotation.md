# 0070 — Audit is flat per-event documents captured by annotation, with levels

**Status:** accepted · **Date:** 2026-09-03

## Context

The old audit shape was one document per workspace or workflow with an unbounded embedded
`events` list — unqueryable by actor, action, outcome or time, and impossible to expire or count.
Its AspectJ pointcuts targeted service methods by string and silently matched nothing after
package moves; run-scope events were never written at all.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep AOP-on-services with per-object records | Nothing consumes audit | Unbounded arrays; string pointcuts drift silently; no query surface |
| B. Adopt the reference pattern: `@Audited` on controllers, one flat event per attempt, capture-time levels | Audit must answer who/what/when/outcome and support retention | Old records dropped; annotations must be added per endpoint |
| C. Event-source from engine transitions | Only run lifecycle matters | Misses definition/workspace/token operations and denied attempts |

## Decision

Option B. `@Audited` on controller methods drives `AuditAspect` (`service-core/src/main/java/io/boomerang/core/audit/AuditAspect.java:55-72`),
recording one event per attempt with outcome SUCCESS/FAILED/DENIED — annotations are
compiler-checked at the call site, unlike the string pointcuts they replace. Capture gates on the
`audit` settings document (`enabled`, `level` DESTRUCTIVE/WRITE/ALL; `core/audit/AuditLevel.java:9-17`)
at capture time; writes are async best-effort (`core/audit/AuditEventWriter.java:13-27`). Events are flat
documents in the `audit` collection, indexed by `migration/_0021__Indexes.java:160-168`, with a TTL that
`AuditRetentionService` sets from `audit.retentionDays` at startup, floored at 60 days for monthly quota
counting (`core/audit/AuditRetentionService.java:28,59,83`).

## Consequences

- Querying by workspace, actor, resource and outcome becomes possible; so does counting runs.
- A v3 database's per-object audit records are not carried over; the trail starts at the upgrade.
- Raising the level is not retroactive; events older than the retention expire.
- Run-lifecycle events come from the engine's transition event, not an annotation:
  `WorkflowRunAuditBridge` emits one `CREATE` and one completion event per run
  (`core/audit/WorkflowRunAuditBridge.java:58-72`); task-run transitions are not audited.

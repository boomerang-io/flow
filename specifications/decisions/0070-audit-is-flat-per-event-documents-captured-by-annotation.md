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

Option B. `@Audited` on controller methods drives `AuditAspect` (`service-core/src/main/java/io/boomerang/core/audit/AuditAspect.java`),
recording one event per attempt with outcome SUCCESS/FAILED/DENIED — annotations are
compiler-checked at the call site, unlike the string pointcuts they replace. Capture gates on the
`audit` settings document (`enabled`, `level` DESTRUCTIVE/WRITE/ALL) at capture time; writes are
async best-effort (`AuditEventWriter`). Events are flat documents in the `audit` collection with a
TTL driven by `audit.retentionDays` (floored at 60 days for monthly quota counting), restructured
by `_0042__AuditEventRestructure`.

## Consequences

- Querying by workspace, actor, resource and outcome becomes possible; so does counting runs.
- The old per-object records are dropped, not migrated — pre-restructure history is gone.
- Raising the level is not retroactive; events older than the retention expire.
- Run-lifecycle events are not yet emitted — the engine's transition listener calls
  `AuditEventEmitter` in a follow-up; revisit the emitter API then.

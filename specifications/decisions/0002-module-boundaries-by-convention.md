# 0002 — Module boundaries are a convention, not Spring Modulith or ArchUnit

**Status:** accepted · **Date:** 2026-08-14

## Context

The merge of flow and engine into `service-core` was first proposed as a Spring Modulith application
with named module interfaces and verification tests. Modulith also brings its own event outbox and
module-scoped configuration. The product already had a transactional outbox of its own, and mode gating
was always going to be a plain Spring `@Conditional`.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Spring Modulith with verified module boundaries | Many teams share one codebase and need compile-time policing | A second outbox and event model to reconcile with ours; a spike to prove the mode matrix fits; framework churn on every upgrade |
| B. ArchUnit rules over plain packages | Boundaries matter but the framework does not | Rules drift from intent; still a test to maintain per package |
| C. Flat feature packages under one root, boundaries enforced in review | One small team; a reference codebase already runs this way | Nothing stops a bad import except review |

## Decision

Option C. `service-core` is one Maven module with eight feature packages, `io.boomerang.{core, workspace,
workflow, engine, dispatcher, schedule, event, integrations}` (`service-core/src/main/java/io/boomerang/`),
each holding its entities, repositories, models, services and controllers. There is no Modulith
dependency in `service-core/pom.xml` and no ArchUnit test. The one rule that is held today is that `core`
has zero imports from any feature package; the one place it once slipped is recorded in
`core/audit/AuditInterceptor.java:98`. Cross-package calls are ordinary injections; the engine never calls
the platform side synchronously, it publishes `ApplicationEvent`s.

## Consequences

- No framework to upgrade or configure; a package move is a directory move.
- Controllers sit in the package of the service they inject, so a route and its owner are one `ls` apart.
- Import direction is only as good as review. `specifications/architecture.md` carries the verified
  import table; re-run the grep when a package gains a new dependency.
- Revisit if the codebase gains enough contributors that review stops catching upward imports.

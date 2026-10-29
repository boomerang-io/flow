# 0001 — service-core is one deployable that holds the engine as a package, with boundaries kept by convention

**Status:** accepted · **Date:** 2026-08-14

## Context

The public routes, authentication, workspaces and the engine all work on one MongoDB, and the engine's work is claimed
from a queue, so any instance can take any run. Separate services would put a network hop and a second security stack in
front of every run request; inside one deployable, the feature packages need a rule one small team can keep.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Deployable: A. Two services joined by events | An embedder needs a platform-free classpath | Synchronous reads stay remote; two security stacks; a version matrix |
| Deployable: B. One deployable; the shape is properties | Engine work is queue-claimed and instances are interchangeable | Execution shares the process that serves the routes |
| Boundaries: C. Spring Modulith or ArchUnit rules | Many teams share one codebase | A second outbox and event model, or rules that drift from intent; churn per upgrade |
| Boundaries: D. Feature packages, boundaries held in review | One small team | Only review stops a bad import |

## Decision

B and D, because queue-claimed work makes instances interchangeable and a split protects nothing. `service-core` is the
one Spring Boot application (`pom.xml:10`, `service-core/src/main/java/io/boomerang/Application.java:37-45`), every bean
always loads, and the headless shape is two properties (decision 0013). "Engine" names its execution package, never the
application, which ships as `boomerangio/flow-service-core` (`.github/workflows/ci-release.yml:33-36`); the Kubernetes
worker stays its own deployable. Eight packages, `io.boomerang.{core, workspace, workflow, engine, dispatcher, schedule,
event, integrations}`, each hold their entities, repositories, services and controllers, with no Modulith or ArchUnit.
Review holds two rules: `core` imports no feature package except the secret-sealing save hook
(`core/config/SecretValueMongoListener.java:11-13`), and the engine hands platform work to `ApplicationEvent` listeners
(`schedule/ScheduleEventListener.java:26-29`, `core/RelationshipEventListener.java:36-37`).

## Consequences

- Engine errors reach callers with their real status codes; one image serves every shape, set at deploy time
  (`ci-release.yml:31-32`).
- A package move is a directory move. Import direction is only as good as review; `specifications/architecture.md`
  carries the verified import table, re-checked when a package gains a dependency.
- Revisit if an embedder contractually needs a platform-free classpath, if a saturated-execution load test shows route
  latency that queue tuning cannot fix, or if review stops catching upward imports.

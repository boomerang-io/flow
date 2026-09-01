# 0001 — Merge the flow and engine services into one deployable with a mode switch

**Status:** accepted · **Date:** 2026-07-22 (mode list amended 2026-08-15)

## Context

Version 4 ran two services: `service-flow` (API, teams, auth) called `service-engine` (execution) over
HTTP for every run request, both against one MongoDB. The split protected only "browse definitions while
runs are down", while every engine failure surfaced as a flat 500, two unauthenticated internal surfaces
existed, and `lib-common` had to be kept in step across two release lines. One embedder ran the engine
headless without the platform, so that deployment shape had to survive.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep two services, decouple them with async events and split `lib-common` | An embedder needs a platform-free classpath | About 25 synchronous reads stay on HTTP forever; two auth stacks; nothing gets deleted |
| B. One deployable, `flow.mode` selects which packages load | The engine's work is queue-based and instances are interchangeable | Blast radius of execution inside the API process; needs a load test before cutover |
| C. Merge and also embed the worker in-process | Laptop use with no Kubernetes | Drags Kubernetes clients into the product image; the worker's scaling profile differs |

## Decision

Option B. `service-core` is the single Spring Boot application (`service-core/src/main/java/io/boomerang/Application.java:36-39`)
and `flow.mode` has two values, `standalone` (the whole product, the default) and `engine` (headless
execution): `service-core/src/main/java/io/boomerang/config/FlowMode.java:21-23`. Mode-specific beans
carry `@ConditionalOnFlowMode` (`config/ConditionalOnFlowMode.java:31-35`); the security default follows
the mode (`core/security/FlowSecurityProperties.java:23-29`). An earlier three-mode list (`full`, `engine`,
`standalone`) collapsed to two because "full" and "standalone" were the same product, and running with
security off is configuration, not a mode. The worker stays a separate deployable (`service-dispatcher`).

## Consequences

- The flow→engine HTTP client, its 37 URL properties and the engine→flow callback controller are gone;
  the engine now publishes Spring `ApplicationEvent`s that the platform side listens to
  (`schedule/ScheduleEventListener.java:32-33`, `core/RelationshipEventListener.java:36-37`).
- Engine errors reach API callers with their real status codes.
- One image serves both modes (`.github/workflows/ci-release.yml:52-54`); engine mode is a deploy-time property.
- Revisit if an embedder contractually requires a platform-free classpath, or if a saturated-execution load
  test shows API latency that queue tuning cannot fix.

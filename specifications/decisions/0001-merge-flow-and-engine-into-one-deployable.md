# 0001 — Merge the flow and engine services into one deployable

**Status:** accepted · **Date:** 2026-07-22

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
| B. One deployable; deployment shape is a few properties | The engine's work is queue-based and instances are interchangeable | Blast radius of execution inside the API process; needs a load test before cutover |
| C. Merge and also embed the worker in-process | Laptop use with no Kubernetes | Drags Kubernetes clients into the product image; the worker's scaling profile differs |

## Decision

Option B. `service-core` is the single Spring Boot application
(`service-core/src/main/java/io/boomerang/Application.java:37-44`) and every bean always loads. The
engine's work is claimed from a queue, so instances are interchangeable and the platform side costs the
execution path nothing it did not already pay. The headless embedded shape is two properties,
`flow.security.enabled=false` and `flow.workspace.single=true`
(`core/security/FlowSecurityProperties.java:14`, `core/security/FlowWorkspaceProperties.java:14`;
decision 0109), not a mode. The worker stays a separate deployable (`service-dispatcher`).

## Consequences

- The flow→engine HTTP client, its 37 URL properties and the engine→flow callback controller are gone;
  the engine now publishes Spring `ApplicationEvent`s that the platform side listens to
  (`schedule/ScheduleEventListener.java:26-27`, `core/RelationshipEventListener.java:36-37`).
- Engine errors reach API callers with their real status codes.
- One image serves every shape (`.github/workflows/ci-release.yml:31-36`); the shape is set at deploy time.
- Revisit if an embedder contractually requires a platform-free classpath, or if a saturated-execution load
  test shows API latency that queue tuning cannot fix.

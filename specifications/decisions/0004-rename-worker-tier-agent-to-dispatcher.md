# 0004 — Rename the worker tier from agent to dispatcher

**Status:** accepted · **Date:** 2026-07-23

## Context

The worker that polls the engine, claims runs and drives Tekton was called the "agent". Version 5 adds
AI task types, for which "agent" is the natural word, so the two meanings would collide in code, docs and
the API. The worker does not host the work either: it registers, claims, hands each task to a runtime and
relays the result.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep "agent" (Jenkins/Buildkite precedent) | No AI task types are coming | Collides with the AI meaning |
| B. "runner", "worker" or "executor" | The tier itself executes the container | All three imply hosting the work, which it does not |
| C. "dispatcher" | The tier claims and dispatches to a runtime | Every class, path, property, collection and image name changes |

## Decision

Option C. The module is `service-dispatcher` (`service-dispatcher/pom.xml:6`), the image
`boomerangio/flow-service-dispatcher` (`.github/workflows/ci-release.yml:95`), the wire is `/dispatcher/v1/**`
(`service-core/src/main/java/io/boomerang/dispatcher/DispatcherControllerV1.java:47`) guarded by
`DispatcherAuthFilter` (`dispatcher/DispatcherSecurityConfiguration.java:19`), the registry collection is
`dispatchers` (indexed by `migration/_0021__Indexes.java:138`), and the claim owner is written as `claim.by`.
The old `/api/v1/agent` path was dropped outright rather than dual-served, because nothing on the v4 line had
adopted it. "Agent" is reserved for the AI task types. Per-runtime selection lives inside the one module as
`dispatcher.executor` (`tekton` or `kube-jobs`, `service-dispatcher/src/main/resources/application.properties:59`)
rather than as separate `dispatcher-tekton`/`dispatcher-docker` builds.

## Consequences

- One unambiguous word for the worker tier in code, API, properties, images and the release job
  (`deploy-dispatcher`, `ci-release.yml:72`).
- Core holds no property naming a dispatcher at all: the log route a dispatcher serves is registered by the
  dispatcher itself (decision 0112).

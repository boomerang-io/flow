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

Option C. The module is `service-dispatcher` (`pom.xml:12`), the image `boomerangio/flow-service-dispatcher`
(`.github/workflows/ci-release.yml:156`), the wire is `/api/v1/dispatcher/**`
(`service-core/src/main/java/io/boomerang/dispatcher/DispatcherControllerV1.java:41`) guarded by
`DispatcherAuthFilter`, the registry collection is `dispatchers` (renamed by the loader's
`_0015__DispatcherRename`), and the claim owner is written as `claim.by`. The old `/api/v1/agent` path was
dropped outright rather than dual-served, because nothing on the v4 line had adopted it. "Agent" is
reserved for the AI task types. Per-runtime selection lives inside the one module as `dispatcher.executor`
(`tekton` or `kube-jobs`) rather than as separate `dispatcher-tekton`/`dispatcher-docker` builds.

## Consequences

- One unambiguous word for the worker tier in code, API, properties and images.
- Two names survive as property keys core reads for the log stream, `flow.agent.service.host` and
  `flow.agent.logstream.url` (`service-core/src/main/resources/application.properties:70-72`); rename
  when the log path is next touched.
- The release job that pushes the dispatcher image is still called `deploy-agent` (`ci-release.yml:131`).

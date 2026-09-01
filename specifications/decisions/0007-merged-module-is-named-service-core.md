# 0007 — The merged module is named service-core; "engine" stays a package and a mode name

**Status:** accepted · **Date:** 2026-07-23

## Context

Merging `service-flow` and `service-engine` (0001) left one deployable that needed a name. "Engine" was
already the name of the execution package inside it and of the headless `flow.mode` value, so reusing it
for the application would have made one word mean three things.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `service-flow` as the merged name | Minimal churn in poms and CI | Hides that the engine now lives inside it |
| B. `service-engine` | The engine is the important half | Overloads "engine" three ways: app, package, mode |
| C. `service-core` | Matches the reference codebases' convention | A `git mv` of the module plus pom, Dockerfile, CI and image renames |

## Decision

Option C. The module is `service-core` (`pom.xml:11`), the jar `service-core/target/service-core.jar`,
the image `boomerangio/flow-service-core` (`.github/workflows/ci-release.yml:76`), and the application
class `service-core/src/main/java/io/boomerang/Application.java`. The old `service-engine` module was
merged in and deleted; "engine" survives only as the execution package
`service-core/src/main/java/io/boomerang/engine/` and as the `flow.mode=engine` value
(`config/FlowMode.java:23`). The rename was done as a pure-rename commit so history follows the files.

## Consequences

- One deployable name across poms, Dockerfiles, workflows and the compose stack.
- "Engine" is unambiguous: a package, or a mode, never an application.
- Operators upgrading from v4 change image names (`flow-service-workflow`/`flow-service-engine` → `flow-service-core`)
  with no alias image in between (0006).

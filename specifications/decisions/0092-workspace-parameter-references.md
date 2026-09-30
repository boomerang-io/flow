# 0092 — Workspace parameters are referenced as workspace.params; team.params is deprecated

**Status:** accepted · **Date:** 2026-09-30

## Context

The rename of team to workspace (0003) left one user-facing spelling behind: workflows read a workspace
parameter as `$(team.params.x)`, while the UI, API and docs say workspace. Existing workflows and task inputs
carry the old spelling, and a v3 database is upgraded in place, so it cannot simply stop resolving.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `team.params` only | No one reads the references | The one place users still meet "team" |
| B. Accept both; suggest and document `workspace.params`; deprecate `team.params` for removal in the next major | Existing workflows must keep running | Two spellings until the next major |
| C. Rewrite stored references to `workspace.params` in a migration | Every reference is known and plain text | Rewrites user content in task inputs and scripts; a partial match corrupts it |

## Decision

B. `ParamLayers.getFlatMap` exposes workspace parameters under both prefixes and `getFlatKeys` suggests only
`workspace` (`lib-common/.../ParamLayers.java`); `ParameterManager` accepts both scopes and logs a warning once
per `team.params` reference (`engine/ParameterManager.java`, `warnIfDeprecatedScope`). The webapp shows
`$(workspace.params.x)`.

## Consequences

- New workflows use the workspace spelling; old ones keep running and log what to change.
- The next major version removes `ParamLayers.DEPRECATED_WORKSPACE_SCOPE` and its scope entry.

# 0003 — Rename Team to Workspace at the v5 major

**Status:** accepted · **Date:** 2026-07-22

## Context

Version 4 scoped everything to a "team": API paths, the relationship graph node type, token scopes and
the frontend. The community documentation, the reference codebases and the engine-mode story ("one
default workspace") all say "workspace". A major version is the only moment a path rename is acceptable.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep "team" everywhere | No major release is planned | Permanent mismatch with docs and with the engine-mode wording |
| B. Rename internally only | Wire stability matters more than vocabulary | Code says one thing, the API says another, forever |
| C. Full rename: code, paths, stored values, frontend | A major release is being cut anyway | Every stored `TEAM` value and prefix needs a migration; every client must move |

## Decision

Option C. The package is `io.boomerang.workspace` with `WorkspaceService`/`WorkspaceEntity`
(`service-core/src/main/java/io/boomerang/workspace/`). Paths are `/api/v2/workspace/{workspace}/...`
only — the `/api/v2/team` alias has already been retired (`workflow/WorkspaceWorkflowControllerV2.java:35`).
Stored values migrate in the loader: `_0016__WorkspaceRename` re-keys relationship nodes and edges, token
and role types, permissions and audit scope, and renames the `teams` collection to `workspaces`
(`service-loader/src/main/java/io/boomerang/loader/migration/_0016__WorkspaceRename.java`). The
workspace token prefix moved from `bft` to `bfk`; the old prefix is rejected outright, with no
deprecation window (`service-core/src/main/java/io/boomerang/core/enums/TokenTypePrefix.java:10,24,51`).

## Consequences

- One vocabulary across API, code, database and docs.
- Any v4 client that used `/api/v2/team/...` must change its paths; there is no compatibility window.
- Some internals still say "team": the error code `TEAM_INVALID_REF`, and the `teamRef` parameter names
  inside `WebhookEventService`/`ScheduleJob`. These are cosmetic and can be swept at any time.

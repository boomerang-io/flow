# 0099 — Engine mode is a single-workspace installation whose parameters are writable

**Status:** accepted · **Date:** 2026-10-08

## Context

Engine mode has one workspace, `system`, read straight off the collection because `WorkspaceService` composes
members, quotas and insights engine mode does not have. Workflows still reference `$(workspace.params.x)`, the
webapp shows the workspace parameters page in engine mode (`workspace.parameters` is not gated by mode), and
Cheer provisions workspace parameters at startup, but the write routes loaded only in standalone mode.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Load `WorkspaceService` in engine mode | Engine mode should become multi-workspace | Members, quotas and insights arrive with it; the engine stops being headless |
| B. Engine routes for the parameters only, through the parameter logic standalone uses; refuse any other workspace change | A single-workspace engine that still has workspace parameters | Two routes in engine mode; other workspace fields stay unwritable there |
| C. Use global parameters instead | Nothing references workspace parameters | Breaks `$(workspace.params.x)` and the webapp's page in engine mode |

## Decision

B. `WorkspaceParameterService` holds the parameter merge (replace by name, a secured value sent blank keeps its
secret, names validated) and the delete, and both `WorkspaceService` and `EngineWorkspaceService` use it.
`EngineWorkspaceControllerV2` adds `PATCH /api/v2/workspace/{workspace}` (parameters only; any other field is
`TEAM_INVALID_REQ`) and `DELETE …/parameters/{name}`, with the standalone permissions and audit
(`service-core/.../workspace/EngineWorkspaceService.java`, `patch`).

## Consequences

- The same request edits workspace parameters in both modes; the webapp's page and Cheer's provisioner work
  against an engine.
- Labels, display name and approver groups stay unwritable in engine mode; revisit if a single-workspace engine
  needs them.

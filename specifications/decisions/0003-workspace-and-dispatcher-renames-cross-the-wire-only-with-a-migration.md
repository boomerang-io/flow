# 0003 — Team is Workspace and agent is dispatcher, and a rename crosses the wire only with a migration

**Status:** accepted · **Date:** 2026-08-18

## Context

"Team" disagrees with the documentation and the single-workspace wording, and "agent" names the AI task types
(`ai-agent`), so it cannot also name the worker that claims tasks. Some identifier-like strings are stored data:
renaming the reader but not the stored value turns a feature off silently.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Rename: A. In code only | Wire stability matters more than vocabulary | Code says one thing, the routes another, for good |
| Rename: B. Code, routes, stored values and webapp together | A major release is being cut | Every stored value needs a change unit; every client moves |
| Wire: C. Rename freely; fix data drift when noticed | Throwaway data | Silent feature loss on every running install; each layer's tests still pass |
| Wire: D. Persisted strings move only with a change unit | Installs upgrade in place | Each rename costs a change unit |

## Decision

B and D. Routes are `/api/v2/workspace/{workspace}/...` only (`workflow/WorkspaceWorkflowControllerV2.java:38`) and
error reasons `WORKSPACE_*` with codes unchanged (`common/error/BoomerangError.java:26,44-47`). The v3 units rename
`teams` to `workspaces` and drop v3 tokens for re-issue (`migration/_0002__V3PrepareCollections.java:62,67-82`), build
`workspace` graph nodes (`migration/_0013__V3BuildRelationshipGraph.java:246`) and map the `team*` settings keys to
`quotas` and `workspace*` (`migration/_0016__BuildSettingsFromSeed.java:54,70-73`), the keys
`core/FeatureService.java:44-77` and `client-web/src/Features/App/App.tsx:227-231` read. Workspace tokens use `bfk`;
`bft` and `bfw` are rejected (`core/enums/TokenTypePrefix.java:22-35`). The worker is `service-dispatcher`, since it
claims work and hands it to a runtime rather than hosting it: wire `/dispatcher/v1`
(`dispatcher/DispatcherControllerV1.java:52`), registry `dispatchers`, claim field `claim.by`, runtime
`dispatcher.executor`, `tekton` or `kube-jobs` (`service-dispatcher/src/main/resources/application.properties:60`).

## Consequences

- One vocabulary across routes, code, database and documentation. A client on `/api/v2/team/...` or `/api/v1/agent` must
  change its paths; there is no compatibility window.
- Every rename of a settings key, stored enum value or query parameter MUST ship with the change unit that moves the
  stored value, as `_0027__RenameWorkspaceTaskNodes` does for `teamtask` nodes.
- Core names no dispatcher in its properties: a dispatcher registers its own log route (decision 0112). The `teamRef`
  parameters in `event/WebhookEventService.java:102` and `schedule/ScheduleJob.java:51` are cosmetic leftovers.

# 0047 — A signed-in user holds a self-service baseline grant, and a setting says whether users create workspaces

**Status:** accepted · **Date:** 2026-10-30

## Context

A session's grants were the global role for an admin or operator, otherwise one grant per workspace membership. A
new user in no workspace held none, so every bootstrap call, the profile included, answered 403 and the webapp
showed its error page. Whether such a user may create a workspace was never decided: a workspace editor's
`**/write` passed the scope-blind route check, a user in no workspace could not.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A `global`-scoped baseline grant | — | Widens every check that keys on `global`: another user's account, the audit trail, the token ceiling |
| B. The self routes pass on token scope alone | — | Breaks "a route needs a grant"; the manager and the coverage test change |
| C. A third grant scope, `user`, anchored to the user's own id, carrying the baseline | Every signed-in human must reach their profile, flags, navigation and templates | A new enum value inside session tokens' existing `permissions[].scope` |
| D. A personal workspace per user, as v3 | The v3 shape is wanted | A workspace and quotas per user |

## Decision

C, with the creation of workspaces as a setting. `resolvePermissionsForUser` gives a user who is not an admin a
`user`-scoped grant of `user/read`, `user/write`, `system/read`, `workflowtemplate/read` and `workspace/read`, plus
`workspace/write` when the `features` setting `workspaceCreate` is on (`core/TokenService.java:681-720`,
`core/SettingsService.java:138-150`); the global-keyed checks are unchanged. `WorkspaceService.create` refuses a
user who is not an admin when the setting is off (`workspace/WorkspaceService.java`), and the flag reaches the webapp
as `workspace.create` so Home and Workspaces offer the button only to those who may (`core/FeatureService.java`).
`_0039__SeedWorkspaceCreateSetting` adds the key, from the seed, to an install built before it.

## Consequences

- A new user lands on Home with no workspaces and a line asking an administrator to add them, not an error page.
- The setting applies to sessions minted after it changes; a session is re-minted on sign-in.
- A `key` or `user` token never carries the baseline: it copies workspace roles, as before.

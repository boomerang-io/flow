# 0053 — A vocabulary rename stops at the wire unless it ships with a migration

**Status:** accepted · **Date:** 2026-08-18

## Context

The team → workspace rename changed Java identifiers, route paths and webapp strings. Some
strings that look like identifiers are persisted data: settings keys such as
`features.teamQuotas`, enum values stored in Mongo, and query-parameter names. Renaming the
webapp's read of `feature["team.*"]` to `feature["workspace.*"]` while the settings document
still held `team*` keys silently disabled every gated screen, with no error anywhere.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Rename code and wire strings freely; fix data drift when noticed | Throwaway data | Silent feature loss on every running install; no failing test, because each layer is self-consistent |
| B. Persisted keys, stored enum values and parameter names are wire contract: rename only together with a loader migration that moves the stored value in the same change | Any install that upgrades in place | Each rename costs a changeunit and must land with the code that reads the new key |

## Decision

Option B. A rename MAY cross the wire only when the stored value, the server read and the client
read move in one change. The quota settings key moved from `teams` to `workspaces` in
`service-loader/src/main/java/io/boomerang/loader/migration/_0032__WorkspaceQuotaSettingsKey.java:17-24`,
and the four feature-flag keys moved in `_0034__WorkspaceFeatureFlagSettingsKeys.java:17-27`
together with `service-core/src/main/java/io/boomerang/core/FeatureService.java:31-55` and
`client-web/src/Features/App/App.tsx:221-223`, so every layer now reads `workspace*`.

## Consequences

- Code identifiers can follow product vocabulary at will; stored strings cannot.
- Every rename of a settings key, a stored enum value or a query parameter MUST add a loader changeunit; the migration chain is the upgrade path for older installs and is never collapsed.
- Reviewers SHOULD ask "is this string persisted or sent?" before approving a bulk rename.

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
| B. Persisted keys, stored enum values and parameter names are wire contract: rename only together with a change unit that moves the stored value in the same change | Any install that upgrades in place | Each rename costs a change unit and must land with the code that reads the new key |

## Decision

Option B. A rename MAY cross the wire only when the stored value, the server read and the client
read move in one change. The settings seed carries the current keys, and `_0016__BuildSettingsFromSeed`
maps each earlier spelling onto them when it rebuilds a v3 install's settings: the quota group from
`teams`/`workspaces` to `quotas` and the feature flags from `teamQuotas`, `teamParameters`, `teamManagement`
and `teamTasks` to `workspace*` (`service-core/src/main/java/io/boomerang/migration/_0016__BuildSettingsFromSeed.java:54,69-73`),
together with `service-core/src/main/java/io/boomerang/core/FeatureService.java:45-66` and
`client-web/src/Features/App/App.tsx:227-231`, so every layer now reads `workspace*`.

## Consequences

- Code identifiers can follow product vocabulary at will; stored strings cannot.
- Every rename of a settings key, a stored enum value or a query parameter MUST ship with the change unit
  that moves the stored value; a v3 spelling is mapped in the v3 upgrade units.
- Reviewers SHOULD ask "is this string persisted or sent?" before approving a bulk rename.

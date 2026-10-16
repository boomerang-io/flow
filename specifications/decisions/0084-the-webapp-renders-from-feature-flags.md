# 0084 — The webapp renders from feature flags, not from the deployment shape

**Status:** accepted · **Date:** 2026-09-28

## Context

A product that embeds Flow runs `service-core` with `flow.security.enabled=false` and
`flow.workspace.single=true`: one `system` workspace, no sign-in, no workspace management. Operators want the
webapp to show that one workspace's workflows and runs. The webapp must work against that shape and against
the full product without knowing which it faces, and must hide the pages whose surface is not there.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The server computes the flags from the properties and settings; the webapp reads only flags | The webapp should work against any deployment shape without knowing it | A few new flags; every page that calls a gated endpoint needs a flag guard |
| B. `/api/v2/context` exposes the shape, and the webapp branches on it | Few pages differ | Shape checks spread through the webapp; a third shape means another round of branching |
| C. A separate build or mode per shape | The shape is a product of its own | Two webapps to test; every shared screen written twice |

## Decision

Option A. `FeatureService.get` reads the two shape properties and turns a flag on only when its setting is
on and its surface is enabled: `workspace.single` and `authentication` follow the properties, `schedules`
and `integrations` are always on, `insights` and the rest follow their stored settings
(`service-core/src/main/java/io/boomerang/core/FeatureService.java:33-82`), served by `GET /api/v2/features`
(`core/SystemControllerV2.java:103`). The flags follow ARCHIE's `singleWorkspace` toggle, which hides the
switcher and does not redirect. The webapp skips the sign-in config read when `authentication` is `false`
(`client-web/src/Features/App/App.tsx:142-146`), so no route is opened for the unsecured shape.

## Consequences

- An embedding deployment adds `client-web` with no configuration beyond `CORE_SERVICE_INTERNAL_ORIGIN`.
- The webapp has no sign-in when security is off; that shape is for local development or behind a protected route.
- The shape flags come from properties, not stored settings. Revisit if a secured install needs
  `workspace.single` as a stored setting, which is a data-model change.

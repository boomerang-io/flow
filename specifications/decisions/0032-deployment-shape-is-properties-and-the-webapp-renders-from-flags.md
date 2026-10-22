# 0032 — Deployment shape is two properties, security off runs as an unstored synthetic admin, and the webapp reads flags

**Status:** accepted · **Date:** 2026-10-15

## Context

One `service-core` image serves the full product and a headless embedded engine (no sign-in, one workspace), and the
webapp must work against either without knowing which. With security off something must still stand as the caller, or
audit, the profile and every other reader of the identity invent their own meaning for "nobody".

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Shape: A. A mode name gating engine-only beans | The shapes load different code | Every workspace-facing page needs a second controller and service; the copies drift |
| Shape: B. Properties; every bean always loads | The shapes differ only in switches | An embedder sets two properties instead of one |
| Identity: C. A stored default admin user | Operators want an editable account | Listed in secured installs; whoever registers its email at the identity provider inherits it |
| Identity: D. A per-request synthetic token and in-memory admin | Local development, tests, an embedded engine | Lookups by id `system` find no stored record |

## Decision

B and D, and the webapp reads only server-computed flags. `flow.security.enabled` (default `true`,
`core/security/FlowSecurityProperties.java:19-21`) switches authentication and authorization together.
`flow.workspace.single` (default `false`) confines `{workspace}` paths to the seeded `system` workspace and refuses
workspace create and delete (`core/security/SingleWorkspaceInterceptorConfiguration.java:22-28`,
`workspace/WorkspaceService.java:162,330`). With security off, every request carries an `UnauthenticatedGlobalToken`
(principal `system`, actor kind `SERVICE`, one `**/**` grant; `core/security/UnauthenticatedGlobalToken.java:64-72`)
that `core/UserService.java:257-259` maps to an admin user built in memory: the token already holds every permission, so
the profile adds no privilege, where a stored user would add risk. `core/FeatureService.java:31-86` turns the properties
and stored `features.*` settings into the `/api/v2/features` flags.

## Consequences

- An embedder sets `flow.security.enabled=false` and `flow.workspace.single=true` and adds `client-web` with only
  `CORE_SERVICE_INTERNAL_ORIGIN`; with `authentication` false the webapp skips the sign-in config read
  (`client-web/src/Features/App/App.tsx:142-146`), so that shape belongs on a laptop or behind a protected route.
- Audit records `actor.principal=system`; the synthetic user belongs to nothing and the profile lists every workspace as
  its own (`core/UserService.java:310-324`). Both exist only per request.
- Deployment shape is a `flow.<subsystem>.<switch>` property and a runtime toggle a stored `features.<name>` setting,
  never both. Revisit if an embedder needs a smaller classpath, or `workspace.single` must become a stored setting.

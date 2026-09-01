# 0032 — With security off, requests run as a synthetic global admin that is never stored

**Status:** accepted · **Date:** 2026-08-26

## Context

With `flow.security.enabled=false` nothing populated the Spring `SecurityContext`, so `IdentityService`
returned `null` and each consumer invented its own meaning for "nobody": authorization allowed, audit threw
and wrote nothing, and `/api/v2/profile` failed, leaving the webapp on a blank page. A real identity was
needed for the security-off case, and the question was whether it should be a stored default user.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Seed a default admin user in MongoDB | operators want a visible, editable account | it appears in member lists and exports of every secured install, and whoever registers its email at the identity provider inherits an admin record |
| B. Null identity, null-checks everywhere | nothing | every consumer keeps its own fallback; audit stays blank |
| C. A per-request synthetic token and matching in-memory admin user | local development, end-to-end tests, `engine` mode | repository lookups by id `system` find nothing |

## Decision

Option C. `UnauthenticatedGlobalAuthenticationFilter` installs an `UnauthenticatedGlobalToken` (class `global`,
principal `system`, `actorKind=SERVICE`, one `**/**` grant) on any request without an identity
(`service-core/src/main/java/io/boomerang/core/security/UnauthenticatedGlobalAuthenticationFilter.java:37-42`,
`UnauthenticatedGlobalToken.java:61-70`), and `UserService.getCurrentUser()` maps it to `virtualUser()` — an
`admin`-typed `UserEntity` built in memory (`UnauthenticatedGlobalToken.java:81-86`, `core/UserService.java:258-259`).
The deciding reason is that the token already holds every permission, so an admin profile adds no privilege
while a stored user would add real risk.

## Consequences

- Audit records carry `actor.principal=system`; the profile and context endpoints render with security off.
- The virtual user is a member of no workspace; the profile surface reports every workspace as owned by it.
- Both the token and the user exist only per request and disappear when security is switched on.

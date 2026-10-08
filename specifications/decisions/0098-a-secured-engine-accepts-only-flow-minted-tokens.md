# 0098 — A secured engine accepts only Flow-minted tokens, and an engine token opens it

**Status:** accepted · **Date:** 2026-10-08

## Context

Engine mode defaults security off, so every call runs as a synthetic admin. Turning security on is not enough
for a headless engine: the authentication filter also mints a session from an unsigned JWT's email claim, the
shared Basic password, or a forwarded identity header, and no authenticating proxy stands in front of an engine
to make those safe. Once only tokens are accepted, nothing can mint the first one.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Security on as it is | An authenticating proxy fronts the engine | Any caller can name itself through the JWT or forwarded-header paths |
| B. Engine mode with security on accepts only Flow-minted tokens; an engine token supplied as a secret opens it | Headless engines (ARCHIE, Cheer) | One more secret to manage; the webapp's proxy sign-in is unavailable in engine mode |
| C. A separate `flow.security.token-only` switch | Standalone deployments also want tokens only | Another flag to fall out of step with the mode |
| D. Mint the first token with security off, then turn it on | A one-off install | A window with the engine open; a manual step per environment |

## Decision

B. `FlowSecurityProperties.isTokenOnly` is engine mode with security on; `AuthenticationFilter` then skips the
JWT, Basic and forwarded-identity sources, while a Flow token in the header, `x-access-token`, the query parameter
or the session cookie still authenticates (`service-core/.../core/security/AuthenticationFilter.java:104-118`).
`EngineTokenService` registers `flow.security.engine-token` (`bfg_` and at least 32 characters) at startup
as a global `**/**` service token under the fixed id `engine-token`. Engine mode's default stays security off.

## Consequences

- A secured engine is reachable only with a token: its operator supplies the engine token and mints the rest.
- Callers already send `Authorization: Bearer` (Cheer's `flow.execution.token`, every dispatcher), so they change
  configuration, not code.
- The webapp does not run against an engine today; if it ever does, its OIDC session cookie already qualifies.

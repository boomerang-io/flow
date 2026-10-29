# 0038 — Routes are grouped under one prefix per kind of caller, and a dispatcher route runs as the dispatcher's token

**Status:** accepted · **Date:** 2026-10-20

## Context

`service-core` serves the public API, the webapp's server (sign-in) and dispatchers. An operator putting a front
door in front of it must be able to route one without the others; the sign-in exchange matters most, as in proxy
mode it mints a session from forwarded headers. Code a dispatcher route reaches must read its caller like any other.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Paths: one `/api` tree, document what to block | `service-core` is never exposed directly | The operator keeps a list; a new route is exposed until it catches up |
| Paths: `/api/v2` public, `/internal/v1` webapp server, `/dispatcher/v1` dispatcher | Always | Path changes in the webapp and dispatcher |
| Paths: also `/admin` and `/platform` for webapp-only screens | Admin routes must be unreachable from outside | Global tokens automate admin routes, so they are public API |
| Dispatcher identity: its own token | The token is already the caller | Audit names the dispatcher's token as the actor |
| Dispatcher identity: a synthetic `system` caller, or none | One fixed caller is wanted | Hides which dispatcher acted; every new reader on the wire fails on a missing identity |

## Decision

One prefix per caller, each with its own version. The sign-in routes are `/internal/v1/auth/{config,exchange,logout}`
(`service-core/src/main/java/io/boomerang/core/InternalAuthControllerV1.java:39`), called only from the webapp's
loaders and actions (`client-web/src/Config/servicesConfig.ts:27,63-65`) and left out of the published OpenAPI
document (`service-core/src/main/resources/application.properties:163`). The dispatcher wire is `/dispatcher/v1`
on its own filter chain (`dispatcher/DispatcherControllerV1.java:52`), not under `/internal/v1`, so a dispatcher in
another network zone is routed without opening sign-in. `DispatcherAuthFilter` accepts only an unexpired `global`
token with an actor kind and installs it as the request's identity, the one the same bearer carries on `/api/v2`, so
it grants nothing new (`dispatcher/DispatcherAuthFilter.java:72-100`, `core/TokenService.java:428-433`); with
`flow.dispatcher.auth.enabled=false` the request runs as the security-off synthetic admin (`:66-71`).

## Consequences

- A front door routes `/api/v2/**` only, and `/dispatcher/v1` only for a dispatcher outside core's network.
- A prefix is a routing boundary, not a credential: a caller that reaches `service-core` directly reaches
  `/internal/v1`, and proxy identity headers are believed on every prefix the main chain serves
  (`core/security/AuthenticationFilter.java:117-118`).
- Reads that must not depend on the caller stay caller-free: the artifact quota is read from the run's workspace
  (`workflow/QuotaService.java:251`). Revisit if a dispatcher token ever carries less than a `global` grant.

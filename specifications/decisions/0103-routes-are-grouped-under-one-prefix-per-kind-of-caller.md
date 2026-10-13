# 0103 — Routes are grouped under one prefix per kind of caller

**Status:** accepted · **Date:** 2026-10-13

## Context

Everything `service-core` serves sat under `/api`: the public API, the three sign-in routes only the webapp's
server calls, and the dispatcher wire. An operator putting a front door, ingress or load balancer in front of the
API had to know which paths under `/api` not to route. The sign-in exchange is the one that matters: in proxy
mode it mints a session from forwarded identity headers.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Leave the paths, document what to block | `service-core` is never exposed directly | the operator keeps a list; a route added later is exposed until the list catches up |
| B. One prefix per kind of caller: `/api/v2` public, `/internal/v1` webapp server, `/dispatcher/v1` dispatcher | always | a path change in the webapp and the dispatcher: cheap before the first release, breaking after |
| C. B, with the dispatcher under `/internal/v1/dispatcher` | a dispatcher always shares core's network | a dispatcher in its own network zone could not be routed without opening the sign-in routes |
| D. B, plus `/admin` and `/platform` prefixes for webapp-only screens | admin routes must be unreachable from outside | global tokens legitimately automate admin routes, so they are public API |

## Decision

B. The sign-in routes are `/internal/v1/auth/{config,exchange,logout}`
(`service-core/src/main/java/io/boomerang/core/InternalAuthControllerV1.java:41`), called only from the webapp's
loaders and actions (`client-web/src/Config/servicesConfig.ts:27,63-65`) and left out of the published OpenAPI
document (`service-core/src/main/resources/application.properties:166`). The dispatcher wire moves to
`/dispatcher/v1` in its own change, because its path is part of the dispatcher contract. Everything else stays on
`/api/v2`. Each prefix carries its own version.

## Consequences

- A front door routes `/api/v2/**` and nothing else; the dispatcher prefix is routed only for a dispatcher outside
  core's network.
- A prefix is a routing boundary, not a credential. A caller that reaches `service-core` directly still reaches
  `/internal/v1`, and the exchange still believes proxy headers there (boomerang-io/flow#512).
- Proxy identity headers are still honoured on `/api/v2`. Accepting them only on the internal exchange is a
  separate change, to be walked through proxy sign-in first.

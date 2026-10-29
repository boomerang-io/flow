# 0015 — Sign-in is one token exchange for every identity provider, with a session cookie thereafter

**Status:** accepted · **Date:** 2026-08-18

## Context

Browsers sign in either through an authenticating reverse proxy that forwards identity headers, or by OpenID Connect
directly against an identity provider such as Azure AD. Both must lead to one application behaviour, and an install
with no proxy must still be able to sign in.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Proxy-only authentication | Every deployment fronts the service with an authenticating proxy | No standalone sign-in; a session minted per request |
| B. Browser-side OpenID Connect posting the id_token to the backend | No server-side session process | The id_token reaches browser JavaScript; hand-written protocol code |
| C. One exchange route, reached by the proxy-identified request or with a verified id_token, answering with an httpOnly session cookie; the webapp's server runs the redirect | Both deployment shapes | The backend verifies id_token signatures itself, since the route is reachable directly |

## Decision

C. `POST /internal/v1/auth/exchange` mints a `bfs_` session for the identity `AuthenticationFilter` already resolved
(empty body), or for an id_token `OidcTokenVerifier` has checked against the issuer's published keys with exact-match
issuer, audience, expiry and nonce (`service-core/src/main/java/io/boomerang/core/InternalAuthControllerV1.java:76-89`,
`core/security/AuthExchangeService.java:66-134`, `core/security/OidcTokenVerifier.java:83-134`). The cookie is
httpOnly, `Secure`, `SameSite=Lax` and carries only the opaque token (`core/security/SessionCookie.java:22-30`).
`GET /internal/v1/auth/config` tells the webapp which mode applies, derived from the `auth` settings
`oidc.issuer`/`oidc.clientId` rather than a second flag (`core/security/AuthExchangeService.java:51-64`). Logout
revokes the token, not just the cookie (`:137-142`). The routes sit under the webapp server's prefix (decision
0103). After the exchange every caller is a `session` token: one convergence point.

## Consequences

- One trusted issuer is configured; widening to an allowlist is a new decision.
- Header, query-parameter and Basic paths are unchanged, so scripts and webhooks behind a proxy see no difference.
- On the header paths the session mint is reused per identity for 60 seconds (`core/TokenService.java:71,555-586`),
  which also bounds how long a deactivated user keeps authenticating there.

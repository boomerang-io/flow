# 0035 — Sign-in is one token exchange for every identity provider, with a session cookie thereafter

**Status:** accepted · **Date:** 2026-08-18

## Context

The service authenticated browsers only through headers set by an authenticating reverse proxy, minting a
session token on every request and holding no browser-visible credential; a fresh install with no proxy
could not sign in at all. Two identity sources had to be supported — a proxy that forwards identity headers,
and direct OpenID Connect against an identity provider such as Azure AD — without two application behaviours.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep proxy-only authentication | every deployment fronts the service with an authenticating proxy | no standalone sign-in; a per-request token mint |
| B. Browser-side OpenID Connect (PKCE) posting the id_token to the backend | no server-side session process | the id_token reaches browser JavaScript; hand-written protocol code |
| C. One `POST /api/v2/auth/exchange` reached either by the proxy-identified request or with a verified id_token, answering with an httpOnly session cookie; the webapp's own server runs the OpenID Connect redirect dance | both deployment shapes | the backend must verify id_token signatures itself, since the endpoint is reachable directly |

## Decision

Option C. `AuthControllerV2` mints a `bfs_` session for the identity `AuthenticationFilter` already resolved
(empty body) or for an id_token that `OidcTokenVerifier` has checked against the issuer's published keys with
exact-match issuer, audience, expiry and nonce (`service-core/src/main/java/io/boomerang/core/AuthControllerV2.java:71-80`,
`core/security/OidcTokenVerifier.java:83-122`). The cookie is httpOnly, `Secure`, `SameSite=Lax` and carries
only the opaque token (`core/security/SessionCookie.java:22-30`); `GET /api/v2/auth/config` tells the webapp
which mode applies, derived from the `auth.oidc.issuer`/`auth.oidc.clientId` settings rather than a second flag
(`core/security/AuthExchangeService.java:55-66`). Logout revokes the token, not just the cookie (`:141-144`).
The deciding reason is one convergence point: after the exchange, every caller is a `session` token.

## Consequences

- A single trusted issuer is configured; widening to an allowlist is a new decision.
- Existing header, query-parameter and Basic paths are unchanged, so scripts and webhooks behind a proxy see no difference.
- The per-request session mint on the proxy path is bounded by a 60-second reuse window (`core/TokenService.java:578-605`), which also bounds how long a deactivated user keeps authenticating on that path.

# Authentication — unified token exchange (IDPZero + OAuth2-proxy)

**Status: 🟢 BACKEND IMPLEMENTED (2026-08-27, `feat-v5-track10-auth`).** The design below was
ruled 2026-08-18 and the backend half is now built — see the implementation-status section at the
end for what exists, where, and what remains (the webapp sign-in flow is the open half). The
ruling sections in this document are kept verbatim.

## The problem

The webapp has no login flow. `axiosGlobalConfig.ts` is three lines
(`axios.defaults.withCredentials = true`), identity bootstraps from `GET /profile`, and there is no
sign-in route, no OIDC client, and no 401 handling anywhere in `client-web/src`.

`AuthenticationFilter` never reads cookies. It resolves identity from a `Bearer bf[gkus]_` token, an
`access_token` query param, a raw OIDC JWT ("Populated by the app via OAuth2_Proxy" — parsed but
**never signature-verified**, with a live `TODO` acknowledging it cannot tell the JWT actually came
from the proxy), Basic auth, or `x-forwarded-email`/`x-forwarded-user` — and **mints a brand-new
persisted `TokenEntity` on every single request** taking those paths. No persistent browser-visible
credential exists.

An unauthenticated request gets a bare 401 with no `WWW-Authenticate`. Compounding it,
`fetchUserResolver` special-cases only HTTP 423, so a 401 resolves to `undefined`, the render gate
never opens, and the app shows a **blank page forever**.

So Flow assumes an authenticating reverse proxy in front of it — true of existing deployments, false
of a fresh install.

## Direction

**One mechanism, two identity sources.** Both converge on a single token exchange; the UI holds a
session token from that point on. Proxy deployments and local IDPZero differ only in who asserts
identity, not in how the app behaves afterwards.

- **Proxy case:** the SPA POSTs an empty body to the exchange. `AuthenticationFilter` has already
  resolved a principal from the forwarded headers, so the controller mints the session directly.
- **Local IDPZero case:** browser-side PKCE against IDPZero (**public client, no secret** — ARCHIE
  uses a confidential client only because its SSR process can hold one), then POST `{idToken}`. The
  backend verifies signature via JWKS plus issuer/audience/nonce/expiry.

**Verify the id_token properly.** ARCHIE base64-decodes it and trusts transport; CHEER does full
JWKS verification. Flow's exchange endpoint is reachable directly by the browser — it has no
private-network boundary to hide behind — so it must verify, CHEER-style. ARCHIE's exchange is
`permitAll` on `/internal/**` and validates nothing; that model does not transfer.

**Session storage: follow ARCHIE** — httpOnly, `Secure`, `SameSite=Lax` signed cookie, read
server-side, token never reaching client JS. Flow is structurally immune to the cookie-size incident
ARCHIE hit (a 6-workspace user producing a 5,092-byte cookie that broke its callback), because Flow
persists permissions on `TokenEntity` rather than embedding them.

**Logout must revoke.** ARCHIE's logout clears the cookie but never deletes the token, so it stays
valid server-side until its natural expiry. Flow already has `TokenService.delete` — use it.

## Mode selection — ruled: explicit config flag, revisit later

An explicit flag selects proxy vs IDPZero. Rejected for now: auto-detection (POST an empty exchange,
treat 401 as "go log in"), which needs no deploy-time knob but turns a misconfigured proxy into a
silent redirect.

**This is an interim ruling.** OAuth2-proxy is expected to be phased out or re-implemented, at which
point the flag may be unnecessary. Tracked as a GitHub issue; revisit before the flag calcifies into
permanent configuration surface.

> **Implemented (2026-08-27)**: the selection is explicit configuration, but no *second* flag was
> invented — `GET /api/v2/auth/config` derives the mode from configuration that already exists:
> `none` when `flow.security.enabled` resolves false, `oidc` when both `auth.oidc.issuer` and
> `auth.oidc.clientId` settings are non-blank, `proxy` otherwise. An operator selects OIDC by
> filling those settings in; clearing them falls back to proxy. See `AuthExchangeService.config()`.

## Open decisions

- **Trusted issuer configuration** — which OIDC issuers the backend accepts id_tokens from. Single
  configured issuer, an allowlist, or derived from `flow.mode`. This is **new attack surface**, not
  new data: every trusted entry can mint identities Flow will believe.

  > **Resolved in implementation**: a SINGLE configured issuer — `auth.oidc.issuer` /
  > `auth.oidc.clientId` settings, seeded empty by `_0035__AddAuthSettings` (`seed/settings.json`
  > carries them for fresh installs). No allowlist, nothing derived from `flow.mode`. Widening to
  > an allowlist is a deliberate re-open, not a config tweak.

- **Whether the GitHub-style installer-verification pattern is warranted** for the local IDP case —
  see the parallel question recorded in `api-contract-trace.md` §2c. **Still open.**

## What does NOT need to change

No data-model changes. `AuthScope.session`, `TokenTypePrefix.session` (`bfs`),
`TokenService.createSessionToken` (persisted, opaque `bfs_<uuid>`, SHA-256 hashed, revocable, TTL
from `flow.token.max-user-session-duration`) and `resolvePermissionsForUser` all already exist and
fit. `AuthScope`'s own doc comment already reads *"permissions re-resolved on login/exchange"*.

`flow.mode` already gates the whole auth subsystem: `flow.security.enabled` derives from it
(`STANDALONE`→on, `ENGINE`→off) unless set explicitly. The exchange endpoint is a standalone-only
concern.

The closest existing precedent for where the exchange route sits is the GitHub callback's
`permitAll` exemption in `SecurityConfiguration` — reached by browser redirect, so exempted from both
the filter chain and `AuthenticationFilter`.

## Compatibility

Existing `AuthenticationFilter` branches stay intact — the design is additive. Anything not hitting
the exchange and not sending a `bfs_` bearer behaves exactly as today, so scripts and integrations
behind a proxy see no change. Only the webapp gains new behaviour.

**Worth fixing alongside:** the per-request `TokenEntity` mint on the proxy path. Invisible
externally, but it writes a Mongo document per request.

> **Fixed (2026-08-27)**: `TokenService.createSessionToken` (the wrapper only
> `AuthenticationFilter`'s forwarded-email / raw-JWT / Basic branches call) now reuses the mint
> per normalised email within a 60-second in-memory window — at most one persisted `TokenEntity`
> per identity per window per instance, and the same window bounds how stale a reused token's
> permissions can be. The exchange endpoint's mint paths (`createSessionTokenWithRaw` /
> `createSessionTokenForUser`) never read the cache: every exchange hands a fresh raw `bfs_`
> value to the browser cookie. Pinned by `TokenServiceSessionTest`.

## Implementation status (2026-08-27) — what is built (Evolving)

All in `service-core` unless noted; every endpoint below is standalone-mode only
(`@ConditionalOnFlowMode(STANDALONE)`).

| Piece | Where | State |
| ----- | ----- | ----- |
| `POST /api/v2/auth/exchange` — proxy branch | `AuthControllerV2` → `AuthExchangeService.exchangeProxy()` (mints from the identity `AuthenticationFilter` already resolved) | ✅ Built |
| `POST /api/v2/auth/exchange` — OIDC branch | `AuthExchangeService.exchangeOidc()`; body `{idToken, nonce}` | ✅ Built |
| id_token verification | `OidcTokenVerifier` — full JWKS verification via `{issuer}/.well-known/openid-configuration` discovery, RS256, exact-match `iss`/`aud`/`nonce`, `exp`; JWKS fetch through `externalRestTemplate` (proxy/timeout knobs preserved) | ✅ Built |
| Trusted-issuer settings | `auth.oidc.issuer` / `auth.oidc.clientId`, seeded empty by loader `_0035__AddAuthSettings` | ✅ Built |
| Session cookie | Opaque `bfs_` value in an httpOnly/`Secure`/`SameSite=Lax` cookie (`SessionCookie`), read as an identity source by `AuthenticationFilter`; entity stores only the SHA-256 hash | ✅ Built |
| `POST /api/v2/auth/logout` | Clears the cookie AND revokes via `TokenService.delete` | ✅ Built |
| `GET /api/v2/auth/config` | Public pre-auth bootstrap: `{"mode": "oidc"\|"proxy"\|"none", "issuer"?, "clientId"?}`; mode derivation per the ruling note above; exempted like the GitHub callback (permitAll + `shouldNotFilter`); wire shape pinned by `AuthConfigSerialisationTest` | ✅ Built |
| Per-request mint fix | 60s reuse window in `TokenService.createSessionToken` (see the note above) | ✅ Built |
| Webapp sign-in flow | `client-web`: sign-in route, PKCE against IDPZero, 401 handling, calling the endpoints above | 🔴 The open half |
| Installer-verification question | See Open decisions | 🔴 Open |

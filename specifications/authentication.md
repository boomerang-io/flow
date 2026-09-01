# Authentication — unified token exchange (IDPZero + OAuth2-proxy)

**Status: 🟢 IMPLEMENTED END-TO-END (2026-08-31, `feat-v5-track10-auth`).** The design below was
ruled 2026-08-18 and the backend half built 2026-08-27; the webapp sign-in flow shipped 2026-08-31
under a superseding maintainer ruling — **server-side OIDC via remix-auth v4** rather than the
browser-side PKCE originally ruled (see the Direction section for the ruling and the hybrid trust
model). The ruling sections in this document are kept verbatim, with superseded text struck
through in place.

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

- **Proxy case:** an empty-body POST to the exchange. `AuthenticationFilter` has already
  resolved a principal from the forwarded headers, so the controller mints the session directly.
  *(BFF update, 2026-09-01: the browser no longer makes this POST itself. SignedOut submits a
  fetcher to the `/auth/signin` route action, whose server side relays the inbound
  `authorization`/`x-forwarded-user`/`x-forwarded-email` identity headers onto the exchange call
  and relays the response's Set-Cookie verbatim onto its own response —
  `Features/Auth/session.server.ts`. Same wire contract, different first hop.)*
- **Direct OIDC case (ruled 2026-08-31, superseding the browser-side design below):** the PKCE
  dance runs **server-side in route actions/loaders** on the SSR server, via **remix-auth v4 +
  remix-auth-oauth2 v3** (Arctic underneath). Sign-in is a plain `<Form method="post">` to
  `/auth/signin`, whose action builds the strategy per request from `GET /api/v2/auth/config`
  (settings-backed — never frozen at module scope) plus standard OIDC discovery, and answers with
  the authorize redirect (S256 + state + nonce). The `/auth/callback` loader completes the code
  exchange as a public client and POSTs `{idToken, nonce}` to the exchange.
  **The trust model is a hybrid**: Node is only the dance orchestrator — it holds nothing beyond
  two seconds-lived httpOnly transient flow cookies (state+verifier, nonce+returnPath, both
  expired on the callback response) — while **Java stays the verifier and session authority**
  (JWKS signature, issuer, audience, expiry, exact-match nonce), and its `bfs_` Set-Cookie is
  relayed **verbatim** onto the callback's redirect. Why: the id_token never reaches the browser
  at all; no hand-maintained protocol code (discovery/S256/authorize-URL/token exchange all come
  from the framework); and the mechanism is native to React Router 7's action/loader model. The
  flow is **provider-agnostic by construction** — standard discovery, standard authorize/token
  parameters, `openid profile email` — with IDPZero only the compose-stack test IdP (Azure AD is
  the named production example).
  - ~~**Local IDPZero case:** browser-side PKCE against IDPZero (**public client, no secret** —
    ARCHIE uses a confidential client only because its SSR process can hold one), then POST
    `{idToken}`. The backend verifies signature via JWKS plus issuer/audience/nonce/expiry.~~
    *(Superseded 2026-08-31 — built first as ruled, then replaced by the server-side flow above;
    the "public client, no secret" property and the backend verification are unchanged.)*

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
*(BFF update, 2026-09-01: the logout POST also runs server-side — the `/auth/logout` route action
calls `POST /api/v2/auth/logout` via serverFetch and relays the revoking/clearing Set-Cookie
verbatim; it also reads `GET /auth/config` server-side for the proxy `signOutUrl` hard-navigation.
The browser talks only to the SSR server for the whole session lifecycle.)*

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

## URL-param and x-access-token fallbacks — RULED KEEP (2026-09-01, maintainer)

The `access_token` URL parameter and the `x-access-token` header in `AuthenticationFilter` are
**deliberate product features, not v4 leftovers** — they were briefly removed in the 2026-09-01
v4-compat sweep and restored the same day. Do not remove them again.

**Why they MUST stay:** webhook senders frequently cannot set an `Authorization` header. Docker
Hub — a sender `WebhookEventControllerV2` explicitly documents — offers no webhook auth, header,
or payload configuration at all, so a token in the URL (a capability URL) is its *only*
authentication channel; the same holds for many SaaS/legacy webhook configs that accept just a
URL. Industry guidance treats a secret query parameter as the standard fallback for exactly this
case (HMAC signatures being per-vendor work Flow does not implement generically). Without the
URL param, `/webhook`, `/event` and `/callback` are unreachable for header-less senders;
`x-access-token` rides along for senders that can set custom-named headers but not
`Authorization` (some proxies consume/overwrite it).

**Mitigations that make this acceptable:** deployments are HTTPS-only; tokens are scoped and
revocable, and the webapp only ever shows a token at creation time. The residual risk (tokens in
access logs / referrer leakage) is the accepted trade for webhook reachability.
> **Fixed (2026-08-27)**: `TokenService.createSessionToken` (the wrapper only
> `AuthenticationFilter`'s forwarded-email / raw-JWT / Basic branches call) now reuses the mint
> per normalised email within a 60-second in-memory window — at most one persisted `TokenEntity`
> per identity per window per instance. The same window bounds staleness: a permission change —
> including deactivating or deleting the user — is not picked up on these paths for up to 60s,
> because the re-mint that re-checks user status is skipped. The map is bounded: expired entries
> are swept on each put, so it only ever holds identities seen within the last window. The
> exchange endpoint's mint paths (`createSessionTokenWithRaw` / `createSessionTokenForUser`)
> never read the cache: every exchange hands a fresh raw `bfs_` value to the browser cookie.
> Pinned by `TokenServiceSessionTest`.

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
| Webapp sign-in flow | `client-web`: **server-side** per the 2026-08-31 ruling — `Features/Auth/oidc.server.ts` (remix-auth v4 + remix-auth-oauth2 3.4.1) drives `/auth/signin` (action) and `/auth/callback` (loader); the nonce rides the strategy's documented `authorizationParams` extension point, so the backend's exact-match nonce check is kept as-is; SignedOut/proxy/logout surfaces unchanged. Wire pinned by `Auth.action.node.spec.ts`; proven end-to-end on the secured compose stack (e2e 5/5) | ✅ Built |
| Azure-compat: claim fallback chain | `AuthExchangeService.exchangeOidc` follows ARCHIE's proven chain (asdr `auth/callback.tsx`: `email \|\| preferred_username`): `email` → `emailAddress` → `preferred_username` **only when email-shaped** (Flow keys users/activation on email; Azure often carries a UPN there). Names prefer the composite `name` claim (split on first space), falling back to `given_name`/`family_name` + legacy aliases | ✅ Built (2026-08-31) |
| Azure-compat: signature algorithms | `OidcTokenVerifier` accepts the standard asymmetric family (RS/PS/ES × 256/384/512) instead of pinned RS256; HMAC/none rejected always (public JWKS ⇒ symmetric signatures are forgeable). Pinned by ES256-accepted + HS256-rejected tests | ✅ Built (2026-08-31) |
| Installer-verification question | See Open decisions | 🔴 Open |

## Security-off identity — RULED (2026-08-26, maintainer)

**The security-off caller presents as a synthetic ADMIN user that is never persisted.** With
`flow.security.enabled=false`, every request already carries the `UnauthenticatedGlobalToken`
(`AuthScope.global`, `**/**` grant — every authorization check passes), so the open question was
only whether the profile/context surface tells the UI the truth about that power. Ruling:

- `UserService.getCurrentUser()` detects the synthetic token and returns
  `UnauthenticatedGlobalToken.virtualUser()` — id/principal `system`, type `admin`, in-memory only.
  `isCurrentUserAdmin()` routes through the same chokepoint.
- **Never seeded, never written to Mongo.** A stored default user was explicitly rejected: it would
  appear in the member lists/exports of every secured instance, and — since users activate by IDP
  email match — whoever registered its email at the identity provider would inherit an admin
  record. The virtual user is impossible to activate.
- Symmetry is the design: synthetic token ⇒ synthetic user; both exist per-request and stop
  existing the moment `flow.security.enabled=true` puts `AuthenticationFilter` back in charge.
- Known limitation (accepted): repository lookups by id `system` find nothing — identical to the
  previous null-user behaviour, which was already made null-safe.

This closes the "blank page under security-off" hazard: profile/context now resolve, the webapp
renders, and the admin surfaces are visible to a caller whose token could already use every one of
their APIs.

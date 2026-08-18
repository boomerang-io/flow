# Authentication — unified token exchange (IDPZero + OAuth2-proxy)

**Status: 🔵 PROPOSED (2026-08-18).** Design ruled in outline; implementation not started.
Sequences **after** the React Router SSR migration (T8-2/T8-3), because the session-cookie model
below depends on that process boundary existing.

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

## Open decisions

- **Trusted issuer configuration** — which OIDC issuers the backend accepts id_tokens from. Single
  configured issuer, an allowlist, or derived from `flow.mode`. This is **new attack surface**, not
  new data: every trusted entry can mint identities Flow will believe.
- **Whether the GitHub-style installer-verification pattern is warranted** for the local IDP case —
  see the parallel question recorded in `api-contract-trace.md` §2c.

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

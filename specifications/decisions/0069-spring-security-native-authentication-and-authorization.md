# 0069 — Spring Security's native model replaces hand-rolled authentication and authorization mechanics

**Status:** accepted · **Date:** 2026-09-02

## Context

`AuthenticationFilter` trusted a proxy-forwarded bearer JWT's claims with no signature check, built every
`GrantedAuthority` list empty, compared the Basic-auth password with a plain `String.equals`, and
`UnauthenticatedGlobalAuthenticationFilter`/`SecurityInterceptor` reimplemented mechanics Spring Security already
provides natively - the last of which also silently ignored a class-level `@AuthCriteria`. `AESAlgorithm` used
AES/CBC with one hardcoded IV and no authentication tag.

## Options

| Area | Option chosen | Alternative considered | Why not the alternative |
| --- | --- | --- | --- |
| Bearer JWT (A3) | Reuse `OidcTokenVerifier` (JWKS signature + iss/aud/exp, no nonce) on the bearer path | Full `spring-security-oauth2-resource-server` (`NimbusJwtDecoder.withIssuerLocation`) | The issuer is a runtime `Setting`, not a property available at filter-chain construction; a separate resource-server chain would also have to be reconciled with the one filter the async-dispatch fix (76ab43b6e) already tuned |
| Basic auth (A4) | Keep it inside `AuthenticationFilter`, password check via `PasswordEncoder.matches` | `http.httpBasic()` + a custom `AuthenticationProvider` | Basic auth here mints a session `Token` with `allowActivation`/`allowUserCreation` derived from the request's servlet path; a provider invoked from `BasicAuthenticationFilter` has no access to that path |
| Endpoint authorization (A1) | A custom `AuthorizationManager<MethodInvocation>` (`AuthCriteriaAuthorizationManager`) reading the existing `@AuthCriteria` natively, wired as an `Advisor` via `@EnableMethodSecurity` | Rewrite all 103 sites to `@PreAuthorize` with a custom `PermissionEvaluator` | The scope/relationship semantics `@AuthCriteria` already encodes (`assignableScopes`, the resource/action regex) would have to be re-expressed as SpEL at every call site for no behavioural gain |
| Settings encryption (A6) | `Encryptors.delux` (AES-256-GCM, random IV) | `Encryptors.delegatingText` (named in the original brief) | Does not exist in the resolved spring-security-crypto version; `delux` is its GCM equivalent on this classpath |

## Decision

Options as above. A2 (permission-to-`GrantedAuthority` mapping) and A5 (`http.anonymous()`) had no live
alternative to weigh - both are direct native replacements. Scheme versioning for the A6 migration is done at the
application level (`crypt_v1{AESGCM|...}` vs the retired `crypt_v1{AES|...}`) since the library does not tag it;
`service-loader`'s `_0041__ReencryptSettingsAesGcm` re-encrypts every stored value still under the old label,
reproducing the retired cipher verbatim since the loader has no dependency on `service-core`.

## Consequences

- Every endpoint's protection is unchanged in kind; the only behavioural difference is a class-level
  `@AuthCriteria` now works, previously silently ignored (dormant - no endpoint used class-level placement).
- A bearer JWT on the proxy path can no longer be verified without a configured OIDC issuer; an unconfigured
  deployment that relied on this path being unverified must configure `auth.oidc.issuer`/`clientId`.
- Adjacent, not fixed here: `SettingsService.encrypt()`'s `StringUtils.hasText` guard is inverted, so no setting
  is actually encrypted in any current deployment regardless of cipher - needs its own issue.

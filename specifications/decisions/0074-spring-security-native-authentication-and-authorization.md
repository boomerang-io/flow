# 0074 — Spring Security's native model replaces hand-rolled authentication and authorization mechanics

**Status:** accepted · **Date:** 2026-09-02

## Context

`AuthenticationFilter` compared the Basic-auth password with a plain `String.equals`, and
`UnauthenticatedGlobalAuthenticationFilter`/`SecurityInterceptor` reimplemented mechanics Spring Security already
provides natively - the last of which also silently ignored a class-level `@AuthCriteria`. `AESAlgorithm` used
AES/CBC with one hardcoded IV and no authentication tag. The filter also reads a proxy-forwarded bearer token's
claims with no signature check and installs every identity with an empty `GrantedAuthority` list.

## Options

| Area | Option chosen | Alternative considered | Why not the alternative |
| --- | --- | --- | --- |
| Basic auth | Keep it inside `AuthenticationFilter`, password check via `PasswordEncoder.matches` | `http.httpBasic()` + a custom `AuthenticationProvider` | Basic auth here mints a session `Token` with `allowActivation`/`allowUserCreation` derived from the request's servlet path; a provider invoked from `BasicAuthenticationFilter` has no access to that path |
| Endpoint authorization | A custom `AuthorizationManager<MethodInvocation>` (`AuthCriteriaAuthorizationManager`) reading the existing `@AuthCriteria` natively, wired as an `Advisor` via `@EnableMethodSecurity` | Rewrite every annotated endpoint to `@PreAuthorize` with a custom `PermissionEvaluator` | The scope/relationship semantics `@AuthCriteria` already encodes (`assignableScopes`, the resource/action regex) would have to be re-expressed as SpEL at every call site for no behavioural gain |
| Settings encryption | `Encryptors.delux` (AES-256-GCM, random IV) | `Encryptors.delegatingText` | Does not exist in the resolved spring-security-crypto version; `delux` is its GCM equivalent on this classpath |
| Proxy-forwarded bearer token | Leave it read without a signature check | Verify it with `OidcTokenVerifier` against the configured issuer | Sign-in through a proxy means no issuer is configured, and configuring one switches the webapp to its own sign-in, so the route the v3 chart's proxy uses could never sign anyone in; boomerang-io/flow#512 tracks a check that fits |
| Permissions as `GrantedAuthority` | Leave the list empty | Map each permission action onto it | Every check reads the `Token`; scheduled runs and security-off install a `Token` with no authorities, and the flattened list drops the scope of each grant |

## Decision

Options as above. Security-off's synthetic identity moves onto `http.anonymous()`, a direct native replacement
with no live alternative to weigh. The encryption label is versioned at the application level
(`crypt_v1{AESGCM|...}` vs the retired `crypt_v1{AES|...}`) since the library does not tag it; the migration that
re-encrypts stored values, and the key it uses, are decision 0100.

## Consequences

- Every endpoint's protection is unchanged in kind; the only behavioural difference is a class-level
  `@AuthCriteria` now works, previously silently ignored (dormant - no endpoint used class-level placement).
- A blank `flow.authorization.basic.password` admits nobody: the encoder never matches an empty password.
- A proxy-forwarded bearer token is still believed on the proxy's word, exactly as `x-forwarded-email` is.
  Revisit when `service-core` must be reachable other than through its proxy.

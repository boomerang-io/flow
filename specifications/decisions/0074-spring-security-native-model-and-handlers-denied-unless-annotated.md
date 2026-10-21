# 0074 — Authorization uses Spring Security's native model, and a REST handler is denied unless annotated

**Status:** accepted · **Date:** 2026-09-14

## Context

Endpoint authorization is declared per handler with `@AuthCriteria`, whose scope and resource/action rules must run
on every request without hand-written security plumbing. A handler nobody made a decision for must not ship open.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Rewrite every endpoint to `@PreAuthorize` with a `PermissionEvaluator` | Rules read well as SpEL | The rules `@AuthCriteria` encodes re-expressed at every call site, for no behavioural gain |
| B. A custom `AuthorizationManager` reading `@AuthCriteria`, wired through `@EnableMethodSecurity` | The annotation already encodes the rules | One class to maintain |
| C. Allow an unannotated handler; a test asserts coverage | The test is never wrong | The runtime stays permissive when it is |
| D. Deny an unannotated handler at runtime; `@AuthExempt` with a mandatory reason opts out | Always | Every exempt route must say why |
| E. Deny by default, exemptions read from the URL permit lists | Every exempt route is public | Cannot express a route that needs sign-in but no permission rule, such as the Slack routes |

## Decision

B with D. `MethodSecurityConfiguration` intercepts every request-mapped method of an `io.boomerang`
`@RestController` (`service-core/src/main/java/io/boomerang/core/security/MethodSecurityConfiguration.java:51-85`);
`AuthCriteriaAuthorizationManager` reads `@AuthCriteria` from the method or its class and denies a handler with
neither it nor `@AuthExempt`, counted as `flow.security.denied{resource=unannotated}`
(`core/security/AuthCriteriaAuthorizationManager.java:67-98`). `@AuthExempt` requires a `reason`
(`core/security/AuthExempt.java:25-29`); `AuthCriteriaCoverageTest` names an unannotated route at build time. Basic
auth stays in `AuthenticationFilter`, whose session takes its rights from the request path, and uses
`PasswordEncoder.matches`, so a blank password admits nobody (`core/security/AuthenticationFilter.java:185-195,263`).
Security off installs its synthetic admin via `http.anonymous()` (`core/security/SecurityDisabledConfiguration.java:29-31`).
No identity carries a `GrantedAuthority`: every check reads the `Token`, and a flat list would drop each grant's scope.

## Consequences

- A new route fails the build until someone decides its authorization. Controllers are proxied, so a `final`
  controller class or handler method would escape the check.
- Sign-in and the permission rule are separate: a public route needs both a `SecurityConfiguration` permit entry
  and `@AuthExempt`.
- A proxy-forwarded bearer token is read unverified, as `x-forwarded-email` is (`AuthenticationFilter.java:197-244`):
  an issuer check would lock out proxy sign-in. Revisit when `service-core` is reachable other than through its proxy.

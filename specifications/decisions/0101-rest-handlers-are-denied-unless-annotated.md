# 0101 — A REST handler is denied unless it carries `@AuthCriteria` or `@AuthExempt`

**Status:** accepted · **Date:** 2026-09-14

## Context

Endpoint authorization is declared per handler with `@AuthCriteria`. Both the retired `SecurityInterceptor` and
its method-security replacement let a handler with no annotation through (one with a warning, the other by
never intercepting it), so a route added without an authorization decision shipped open to any authenticated
caller — including a workspace-bound key from another workspace. Coverage was an inventory kept by hand.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Allow by default, audit coverage by review | routes are rarely added | the failure is silent and only a review catches it |
| B. Allow by default, a test asserts every handler is annotated | the test is never skipped | a green build still leaves the runtime permissive if the test is wrong |
| C. Deny by default at runtime, with an explicit opt-out annotation that requires a reason, plus the coverage test | always | every exempt route must say why; framework controllers must be kept outside the rule |
| D. Deny by default, and read the exemptions from the URL permit lists | every exempt route is also public | cannot express a route that must sign in but has no permission rule (the Slack routes); a permitted prefix exempts whatever is added under it later |

## Decision

C. `MethodSecurityConfiguration`'s pointcut intercepts every request-mapped method of an `io.boomerang`
`@RestController` (`service-core/src/main/java/io/boomerang/core/security/MethodSecurityConfiguration.java:51-85`);
`AuthCriteriaAuthorizationManager` denies a handler that carries neither `@AuthCriteria` nor `@AuthExempt` on the
method or its class, counted under `flow.security.denied{resource=unannotated}`
(`core/security/AuthCriteriaAuthorizationManager.java:68-98`). `@AuthExempt` has a mandatory `reason`
(`core/security/AuthExempt.java:25-29`). `AuthCriteriaCoverageTest` fails the build first, naming the route. The
opt-out is named after the annotation it opts out of, not "public": exempt routes still pass the filter chain,
and some (the dispatcher wire) are authenticated by a different chain rather than open.

## Consequences

- A new route is a build failure until someone decides its authorization; the decision is readable in the code.
- Sign-in and the permission rule stay separate decisions: a public route carries a permit entry in
  `SecurityConfiguration` and `@AuthExempt`, and the two must agree.
- The sixteen handlers that were unannotated by design state their reason. The five Slack routes record that they
  still require sign-in and that request-signature verification is not wired (boomerang-io/flow#374).
- Every `io.boomerang` controller is a CGLIB proxy; a `final` controller class or handler method would not be
  intercepted and must not be introduced.

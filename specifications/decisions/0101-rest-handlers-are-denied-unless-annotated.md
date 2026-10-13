# 0071 — A REST handler is denied unless it carries `@AuthCriteria` or `@AuthExempt`

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
| C. Deny by default at runtime, with an explicit opt-out annotation that requires a reason, plus the coverage test | always | every public route must say why it is public; framework controllers must be kept outside the rule |

## Decision

C. `MethodSecurityConfiguration`'s pointcut intercepts every request-mapped method of an `io.boomerang`
`@RestController` (`service-core/src/main/java/io/boomerang/core/security/MethodSecurityConfiguration.java:54-87`);
`AuthCriteriaAuthorizationManager` denies a handler that carries neither `@AuthCriteria` nor `@AuthExempt` on the
method or its class, counted under `flow.security.denied{resource=unannotated}`
(`core/security/AuthCriteriaAuthorizationManager.java:68-97`). `@AuthExempt` has a mandatory `reason`
(`core/security/AuthExempt.java`). `AuthCriteriaCoverageTest` fails the build first, naming the route. The opt-out
is named after the annotation it opts out of, not "public": exempt routes still pass the filter chain, and some
(the dispatcher wire) are authenticated by a different chain rather than open.

## Consequences

- A new route is a build failure until someone decides its authorization; the decision is readable in the code.
- The nine routes that were unannotated by design now state their reason; the five Slack routes record that
  request-signature verification is not wired (boomerang-io/flow#374).
- Every `io.boomerang` controller is a CGLIB proxy; a `final` controller class or handler method would not be
  intercepted and must not be introduced.

# 0031 — Permission checks enforce: a mismatch is a real 403, not a shadow metric

**Status:** accepted · **Date:** 2026-08-31

## Context

`SecurityInterceptor` compared each endpoint's `@AuthCriteria` against the caller's grants but, to protect
live installations whose stored permissions might not match the new vocabulary, it only counted mismatches in
a `flow.security.would.deny` metric and let the request through. `RelationshipService.check()` did the same.
The product ships as a new major with no requirement to upgrade clients in place, so the staged rollout had no
remaining purpose.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep shadow mode, flip per deployment after reviewing the metric | upgrading installs whose grants may be stale | every install is effectively unauthorized until an operator flips it; the flag is easy to forget |
| B. Enforce unconditionally | no in-place client compatibility is promised | editor/reader-shaped grants must actually pass the relationship layer, or enforcement breaks read paths |

## Decision

Option B. A token class outside `assignableScopes` returns 401 and a missing `resource/action` grant returns
403, both counted in `flow.security.denied`
(`service-core/src/main/java/io/boomerang/core/security/SecurityInterceptor.java:61-94`).
`RelationshipService.check()` returns `false` on a failed permission check (`core/RelationshipService.java:371-393`);
to make that safe, `checkPermissions` now asks only whether some grant covers the resource type at all
(`**/read` passes for a reader), leaving action granularity to the interceptor (`:425-446`).

## Consequences

- `flow.security.would.deny` no longer exists; dashboards MUST read `flow.security.denied` (tag `layer=relationship` for the data layer).
- Authorization bugs surface as real denials — the open gaps in `authorization.md` are now user-visible.
- A relationship-layer denial still serialises as HTTP 401 (`PERMISSION_DENIED` maps to `UNAUTHORIZED`); aligning it to 403 is a separate wire change.

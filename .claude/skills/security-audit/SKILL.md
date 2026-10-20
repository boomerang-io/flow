---
name: security-audit
description: Systematic audit of authorization (@AuthCriteria coverage) and audit-trail coverage across the Boomerang Flow backend. Produces a severity-ranked findings report. Trigger when asked to audit security, check permissions, review authorization, find unprotected endpoints, or review audit coverage.
---

# Skill: Security Audit — Boomerang Flow

Boomerang Flow enforces authorization with a **method-annotation model**, not per-method
`requirePermission` calls. Every protected controller endpoint carries an `@AuthCriteria`
annotation; `AuthCriteriaAuthorizationManager` reads it and enforces both the token scope and the
`resource/action` permission. A handler MUST carry `@AuthCriteria` or `@AuthExempt(reason = …)`;
one with neither is denied at runtime and fails `AuthCriteriaCoverageTest`. **An `@AuthExempt`
handler gets no permission check**, and unless its path is also on the permit list any signed-in
caller reaches it. That makes "an exemption whose reason does not hold, on a mutating endpoint"
the single highest-value thing this audit looks for.

Produces a structured, severity-ranked report.

---

## Phase 1: Load the Authorization Model (source of truth is the code)

Start from `specifications/authorization.md`, then read and record the code it cites. Every path
below is under `service-core/src/main/java/io/boomerang/`.

1. **`core/security/enums/PermissionResource.java`** — the full resource vocabulary (system,
   workflow, workflowrun, workflowtemplate, taskrun, task, action, user, workspace, token,
   parameter, schedule, insights, integration, webhook, `**`=ANY).
2. **`core/security/enums/PermissionAction.java`** — read, write, delete, action.
3. **`core/security/enums/AuthScope.java`** — the token's class: session, user, key, global.
   It is never a resource scope; each grant carries its own `PermissionScope` (global or
   workspace) on `ResolvedPermissions`.
4. **`core/security/AuthCriteria.java`** — the annotation contract: `resource`, `action`,
   `assignableScopes`.
5. **`core/security/AuthCriteriaAuthorizationManager.java`** — the enforcement logic, wrapped
   around every REST handler by `MethodSecurityConfiguration`. Note precisely:
   - Neither annotation → denied. `@AuthExempt` → **authz skipped** (this is the hole to hunt;
     read the `reason`).
   - The token's class must be in `assignableScopes`, else 401.
   - Permission regex: `(\*{2}|<resource>)/(\*{2}|<action>)` matched against the token's
     resolved permission actions.
6. **`core/RelationshipService.java`** — the second layer: services check the caller can reach
   the target data through the relationship graph. An endpoint with a correct `@AuthCriteria`
   whose service skips this check still leaks another workspace's data.
7. **`core/security/AuthenticationFilter.java` + `SecurityConfiguration.java`** — which routes are
   `permitAll` (authN bypass): internal, actuator/health, OpenAPI/Swagger. A handler under these
   paths legitimately carries `@AuthExempt`; an `@AuthExempt` handler outside them still requires
   sign-in. Record both sets so you don't false-positive.
8. **`dispatcher/DispatcherAuthFilter.java`** — the `/dispatcher/**` chain checks a dispatcher
   actor token on every request, whatever the security switch says. `DispatcherControllerV1` is
   `@AuthExempt` because of it; confirm the filter covers every route the controller declares.
9. **`core/security/SecurityDisabledConfiguration.java`** — `flow.security.enabled=false`
   (`SecurityDisabledCondition`) permits every request and runs it as a synthetic admin. It is
   the embedded headless engine's shape, never a secured install's; scope findings to the shape
   being audited.

Record the enum vocabularies and the permitAll route list as your reference for later phases.

---

## Phase 2: Enumerate Every Controller Endpoint and Check `@AuthCriteria`

### 2a. Discover controllers

```
Glob: **/*Controller*.java   (covers *ControllerV1, *ControllerV2, LogV1Controller)
Grep: @RestController        (confirm the class is a live controller)
```

Cover **both deployables**: `service-core` (every feature package: `core/`, `workspace/`,
`workflow/`, `engine/`, `dispatcher/`, `schedule/`, `event/`, `integrations/`) and
`service-dispatcher` (`LogV1Controller`). Watch for endpoint methods declared in
**abstract base classes** or shared controllers that a `@RestController`-keyed sweep would miss —
grep every `*Controller*.java` for `Mapping` annotations, including non-annotated bases, and
attribute their endpoints to each concrete subclass.

### 2b. Audit each endpoint method

For EVERY method annotated `@GetMapping / @PostMapping / @PutMapping / @PatchMapping /
@DeleteMapping / @RequestMapping`, check:

| Check | Severity | Description |
| ----- | -------- | ----------- |
| **Unjustified `@AuthExempt`** | CRITICAL | The handler has no permission rule and its `reason` does not hold → any signed-in caller reaches it, or any caller at all if the path is on the permit list. (Downgrade to INFO only if the route is intended to be public or is authenticated another way — state that explicitly.) A handler with neither annotation is denied and fails `AuthCriteriaCoverageTest`; report it as a broken build, not an open route. |
| **Wrong `resource`** | HIGH | `resource` doesn't match what the endpoint operates on (e.g. a token endpoint annotated `WORKFLOW`). |
| **Wrong `action`** | HIGH | Read endpoint annotated `WRITE`/`DELETE` or a mutation annotated `READ` — the verb must match the HTTP method's effect. |
| **Over-broad `assignableScopes`** | MEDIUM | Admits a token class that should not reach the operation (e.g. `key` or `global` on a caller's own profile mutation), or lists classes that can't legitimately perform the action. |
| **Wildcard where specific is defined** | MEDIUM | `resource = ANY (**)` or an `action` wildcard on an endpoint that has a precise resource/action. |
| **Workspace route without a data check** | HIGH | A workspace-scoped route (`/api/v2/workspace/{workspace}/...`) whose service never checks the caller can reach that workspace's data through `RelationshipService`. |

### 2c. Record findings

```
| Controller.method | HTTP Verb + Path | @AuthCriteria (scopes / resource / action) | Expected | Finding | Severity |
```

### 2d. Admin / system / internal surfaces

Pay special attention to `SystemControllerV2`, `OutboxControllerV2` (`/api/v2/system/outbox`),
`InternalAuthControllerV1` (`/internal/v1/auth`), the dispatcher wire `DispatcherControllerV1`
(`/dispatcher/v1`), `LogV1Controller` in service-dispatcher, and anything managing platform config
or cross-workspace data. A missing or too-weak guard here is **CRITICAL** — it exposes platform
internals or another workspace's data. Confirm the `/internal/v1` routes are locked down by more
than obscurity.

---

## Phase 3: Permission Vocabulary Consistency

Cross-check the `resource`/`action` pairs actually used across all `@AuthCriteria` annotations
against the enum definitions:

- Every `resource`/`action` used exists in the enums (compile guarantees this, but flag any use
  of `ANY`/wildcards that looks like a shortcut around a precise permission).
- Every enum resource that names a real API area has at least one endpoint guarding it — an
  unused resource may indicate an endpoint that forgot its annotation.
- The team/workflow scoping in `assignableScopes` is consistent across endpoints that operate on
  the same resource (e.g. all `WORKFLOW`/`WRITE` endpoints should agree on which scopes may write).

Output a short consistency table of anomalies.

---

## Phase 4: Audit-Trail Coverage

Authorization decides *whether* an action is allowed; the audit trail proves *what happened*. A
permission model with unaudited mutations is only half a control.

### 4a. Find the audit mechanism

Read `service-core/src/main/java/io/boomerang/core/audit/`: `@Audited` on controller methods,
captured by `AuditAspect`; explicit emits through `AuditEventEmitter` (the engine's run events via
`WorkflowRunAuditBridge`); `AuditEventWriter`; `AuditLevel` and `AuditOutcome`; read access through
`AuditControllerV2`. Establish which mutations are captured by annotation, which by explicit emit,
and what levels and outcomes exist.

### 4b. Classify every mutation endpoint

For each `@Post/@Put/@Patch/@DeleteMapping` from Phase 2, record whether it produces an audit
event, and flag:

| Check | Severity | Description |
| ----- | -------- | ----------- |
| **Unaudited destructive op** | HIGH | Any delete / purge / force-remove not captured. |
| **Unaudited privilege/membership change** | HIGH | Token issue/revoke, role/relationship grant (`RelationshipService`), workspace membership. |
| **Unaudited admin/config mutation** | MEDIUM | System settings, integrations, schedules, webhooks. |
| **Level/outcome mismatch** | MEDIUM | Audited at the wrong level, or DENIED/FAILED paths not captured (the permission check runs in the interceptor *before* the method — confirm denials are still recorded where that matters). |
| **Deliberate exclusion undocumented** | LOW | Routine/transient mutations may be out of scope, but the rationale must be written down. |

### 4c. Capture-semantics spot checks

- Payloads carry IDs + short summaries, not full content bodies.
- No double-emission (annotation on both controller and the service it calls).
- High-frequency events are sampled/rate-limited.

---

## Phase 5: Findings Report

```markdown
# Security Audit Report — Boomerang Flow
Date: <date>   |   Shape audited: security on | security off; multi-workspace | single workspace

## Executive Summary
<total findings by severity; the headline auth-hole count>

## 1. CRITICAL — Unprotected Endpoints
<@AuthExempt handlers whose reason does not hold>

## 2. HIGH — Wrong Authorization
<wrong resource/action; unaudited destructive/privilege changes>

## 3. MEDIUM — Over-broad Scopes / Vocabulary Drift
## 4. LOW — Informational
## 5. @AuthCriteria Coverage Table (every endpoint, both deployables)
## 6. permitAll Reconciliation (intended-public endpoints)
## 7. Audit-Trail Coverage
## 8. Remediation Priority List  (ordered by severity, S/M/L effort)
```

### Severity Definitions

| Severity | Meaning |
| -------- | ------- |
| CRITICAL | Endpoint reachable without authorization (`@AuthExempt` with a reason that does not hold). |
| HIGH     | Authorization present but wrong — allows unauthorized actions; or unaudited destructive/privilege change. |
| MEDIUM   | Over-broad scopes, vocabulary drift, or audit level mismatch — functional but inconsistent. |
| LOW      | Informational — no direct impact, should be addressed. |

---

## Checklist

- [ ] Every `*Controller*.java` across service-core and service-dispatcher examined
- [ ] Abstract/shared controller bases included
- [ ] Every endpoint method checked for `@AuthCriteria` or `@AuthExempt`; each exemption's reason judged CRITICAL vs intended
- [ ] `resource`/`action`/`assignableScopes` validated against the enums and the endpoint's real effect
- [ ] permitAll routes reconciled (internal, actuator, swagger) so intended-public isn't false-flagged
- [ ] Every mutation classified audited/unaudited with a category
- [ ] Report follows the structure above; remediation prioritized

---
name: security-audit
description: Systematic audit of authorization (@AuthCriteria coverage) and audit-trail coverage across the Boomerang Flow backend. Produces a severity-ranked findings report. Trigger when asked to audit security, check permissions, review authorization, find unprotected endpoints, or review audit coverage.
---

# Skill: Security Audit — Boomerang Flow

Boomerang Flow enforces authorization with a **method-annotation model**, not per-method
`requirePermission` calls. Every protected controller endpoint carries an `@AuthCriteria`
annotation; `SecurityInterceptor.preHandle` reads it and enforces both the token scope and the
`resource/action` permission. **A method with no `@AuthCriteria` is silently treated as public**
— the interceptor logs a warning and returns `true`. That makes "missing annotation on a
mutating endpoint" the single highest-value thing this audit looks for.

Produces a structured, severity-ranked report.

---

## Phase 1: Load the Authorization Model (source of truth is the code)

Read and record:

1. **`security/enums/PermissionResource.java`** — the full resource vocabulary (system,
   workflow, workflowrun, workflowtemplate, taskrun, task, action, user, team, token,
   parameter, schedule, insights, integration, webhook, `**`=ANY).
2. **`security/enums/PermissionAction.java`** — read, write, delete, action.
3. **`security/enums/AuthScope.java`** — session, user, team, workflow, global.
4. **`security/AuthCriteria.java`** — the annotation contract: `resource`, `action`,
   `assignableScopes`.
5. **`security/SecurityInterceptor.java`** — the enforcement logic. Note precisely:
   - No `@AuthCriteria` → **authz skipped** (this is the hole to hunt).
   - Token `type` (scope) must be in `assignableScopes`, else 401.
   - Permission regex: `(\*{2}|<resource>)/(\*{2}|<action>)` matched against the token's
     resolved permission actions.
6. **`security/AuthenticationFilter.java` + `SecurityConfiguration.java`** — which routes are
   `permitAll` (authN bypass): internal, actuator/health, OpenAPI/Swagger. Endpoints under these
   paths legitimately have no `@AuthCriteria`; record them so you don't false-positive.
7. **`security/SecurityDisabledConfiguration.java`** — how `flow.authorization.enabled=false`
   turns the whole thing off (dev only). Note: in `engine` mode there is **no auth module** at
   all (host platform owns identity) — scope findings to the mode being audited.

Record the enum vocabularies and the permitAll route list as your reference for later phases.

---

## Phase 2: Enumerate Every Controller Endpoint and Check `@AuthCriteria`

### 2a. Discover controllers

```
Glob: **/*Controller*.java   (covers *ControllerV1, *ControllerV2, InternalController, LogV1Controller)
Grep: @RestController        (confirm the class is a live controller)
```

Cover **all three services**: `service-flow` (`core/`, `workflow/`, `integrations/`),
`service-engine` (`engine/`), `service-agent` (`agent/`). Watch for endpoint methods declared in
**abstract base classes** or shared controllers that a `@RestController`-keyed sweep would miss —
grep every `*Controller*.java` for `Mapping` annotations, including non-annotated bases, and
attribute their endpoints to each concrete subclass.

### 2b. Audit each endpoint method

For EVERY method annotated `@GetMapping / @PostMapping / @PutMapping / @PatchMapping /
@DeleteMapping / @RequestMapping`, check:

| Check | Severity | Description |
| ----- | -------- | ----------- |
| **Missing `@AuthCriteria`** | CRITICAL | No annotation on a method that is not on a permitAll path → any caller reaches it. (Downgrade to INFO only if the path is on the recorded permitAll list and is intended to be public — state that explicitly.) |
| **Wrong `resource`** | HIGH | `resource` doesn't match what the endpoint operates on (e.g. a token endpoint annotated `WORKFLOW`). |
| **Wrong `action`** | HIGH | Read endpoint annotated `WRITE`/`DELETE` or a mutation annotated `READ` — the verb must match the HTTP method's effect. |
| **Over-broad `assignableScopes`** | MEDIUM | Includes `session`/`global` where the operation should be team- or workflow-scoped; or lists scopes that can't legitimately perform the action. |
| **Wildcard where specific is defined** | MEDIUM | `resource = ANY (**)` or an `action` wildcard on an endpoint that has a precise resource/action. |
| **Scope/verb mismatch with route** | MEDIUM | Team-scoped route (`/api/v2/team/{team}/...`) whose scopes don't include `team`. |

### 2c. Record findings

```
| Controller.method | HTTP Verb + Path | @AuthCriteria (scopes / resource / action) | Expected | Finding | Severity |
```

### 2d. Admin / system / internal surfaces

Pay special attention to `SystemControllerV2`, `InternalController`, agent queue/log endpoints,
and anything managing platform config or cross-tenant data. A missing or too-weak guard here is
**CRITICAL** — it exposes platform internals or another team's data. Confirm `InternalController`
(`/internal`) is genuinely locked down (network policy / permitAll-internal), not just relying on
obscurity.

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

Read `service-engine/**/audit/` and `service-flow/**/core/AuditControllerV2.java`. Establish how
audit events are captured (aspect/annotation vs explicit emit) and what levels/outcomes exist.

### 4b. Classify every mutation endpoint

For each `@Post/@Put/@Patch/@DeleteMapping` from Phase 2, record whether it produces an audit
event, and flag:

| Check | Severity | Description |
| ----- | -------- | ----------- |
| **Unaudited destructive op** | HIGH | Any delete / purge / force-remove not captured. |
| **Unaudited privilege/membership change** | HIGH | Token issue/revoke, role/relationship grant (`RelationshipService`), team membership. |
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
Date: <date>   |   Mode audited: full | engine | standalone

## Executive Summary
<total findings by severity; the headline auth-hole count>

## 1. CRITICAL — Unprotected Endpoints
<methods with no @AuthCriteria that are not on the permitAll list>

## 2. HIGH — Wrong Authorization
<wrong resource/action; unaudited destructive/privilege changes>

## 3. MEDIUM — Over-broad Scopes / Vocabulary Drift
## 4. LOW — Informational
## 5. @AuthCriteria Coverage Table (every endpoint, every service)
## 6. permitAll Reconciliation (intended-public endpoints)
## 7. Audit-Trail Coverage
## 8. Remediation Priority List  (ordered by severity, S/M/L effort)
```

### Severity Definitions

| Severity | Meaning |
| -------- | ------- |
| CRITICAL | Endpoint reachable without authorization (missing `@AuthCriteria`, not permitAll). |
| HIGH     | Authorization present but wrong — allows unauthorized actions; or unaudited destructive/privilege change. |
| MEDIUM   | Over-broad scopes, vocabulary drift, or audit level mismatch — functional but inconsistent. |
| LOW      | Informational — no direct impact, should be addressed. |

---

## Checklist

- [ ] Every `*Controller*.java` across service-flow, service-engine, service-agent examined
- [ ] Abstract/shared controller bases included
- [ ] Every endpoint method checked for `@AuthCriteria`; missing ones classified CRITICAL vs intended-permitAll
- [ ] `resource`/`action`/`assignableScopes` validated against the enums and the endpoint's real effect
- [ ] permitAll routes reconciled (internal, actuator, swagger) so intended-public isn't false-flagged
- [ ] Every mutation classified audited/unaudited with a category
- [ ] Report follows the structure above; remediation prioritized

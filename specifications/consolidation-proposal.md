# v5 Consolidation Proposal — Phase 2A Deliverable (2026-07-22)

**Status:** ✅ **CONFIRMED (2026-07-22)** — all 14 judgement calls + N1 ruled (§10), and
the headline decisions confirmed by the maintainer: **Q-211 MERGE** (behind Phase 3, F1–F5
falsifiability live — DD-02), **Q-213 unified product versioning** (DD-03), **Q-214
frontend folds in after the merged image ships** (DD-04). Team→Workspace rename = DD-01.
This document is now the approved Phase 2A architecture record.
**Inputs:** four analysis streams over the verified codebase + `v5-spec-review.md` evidence
base + the confirmed Phase 1 Q-register answers. Evidence file refs inline.

---

## 0. Recommendation (Q-211)

**MERGE `service-flow` + `service-engine` into one Spring Modulith deployable with
`flow.mode` profiles — sequenced BEHIND the Phase 3 execution-model rebuild, never before
it.** `service-agent` (the runtime tier) stays a separate deployable in every scenario.

Why (full argument in §6):
- The split's isolation is already pierced: every UI run request proxies flow→engine
  synchronously; engine failures become blanket 500s (error fidelity destroyed); both
  services share ONE MongoDB. The split protects only "browse definitions while runs are
  down".
- The strongest anti-merge argument — blast radius of the engine's 200/100-thread pools
  with 100k-slot in-memory queues, `Thread.sleep` tasks, in-memory timeouts — is retired
  by Phase 3 itself (Mongo-resident bounded claims, per-type caps, watcher sweep, virtual
  threads), which happens in either outcome.
- The honest alternative (stay split + async decoupling + lib-common split) **ends in a
  strictly worse place functionally** (effort is comparable and is NOT the decision axis):
  ~25 synchronous read calls stay on HTTP forever — so error fidelity, latency hops, and
  the resilience machinery around them remain permanent product characteristics; two
  unauthenticated internal surfaces and two auth stacks remain; the engine-mode callback
  problem still requires the same event inversion work; nothing gets deleted.
- Engine mode is MORE honest merged: today's "standalone engine" quietly cannot execute
  `runscheduledworkflow` or complete `runworkflow` linkage without flow up, and its
  protection model is undocumented `permitAll()`.

**Falsifiability — this recommendation flips if:**
- **F1** — merged-app load test (saturated execution, per-type caps, VT on) shows API p99
  > 2× split baseline that cap tuning cannot fix. *The pre-cutover measurement.*
- **F2** — an embedder contractually requires a platform-code-free classpath.
- **F3** — the Q-127 idempotency audit finds transitions that cannot be made re-entrant.
- **F4** — evidence of a real deployment needing engine:flow replica ratios ≥ ~5:1.
- **F5** — a 2-week Modulith spike cannot express the mode-loading matrix.

**Stepping stone:** Modulith-boundaries-inside-current-services is *sequencing, not a
destination* — it keeps the engine module independently bootable (rollback path + seed of
engine mode) but must be timeboxed: deployment merge within 1–2 releases of boundaries
landing, or Q-211 re-opens with the F1 measurement in hand.

---

## 1. Module architecture (Q-203)

The spec hypothesis (`api, team, engine, agent, schedule, event, common` + `core`) holds
with **two corrections**: (a) a dedicated **`workflow`** module (definition/catalogue
domain) splits out of `engine` — the engine already owns ALL definition persistence
(repos + services in `service-engine`), while flow's same-named services are pure
authz/canvas facades over `EngineClient`; (b) **`integrations`** is its own optional
module. Nine modules + runtime artifacts:

| Module | Responsibility | Public API (Modulith named interface) | Depends on |
|---|---|---|---|
| **common** | Contract kernel: DTOs, enums, errors, pure utils. No beans, no repositories. | all of it | — |
| **core** | Platform substrate: users, tokens, roles, settings, global params, **relationships** (Q-132), audit, security (`@AuthCriteria` + enums fold in — cycle C4), navigation/context | `TokenService`, `UserService`, `IdentityService`, `SettingsService`, `RelationshipService` | common |
| **team** | Teams, members, approver groups, quotas, team params, insights | `TeamService` | common, core, engine |
| **workflow** | Definition domain: Workflow/Revision, Task catalogue, Templates, canvas + Tekton-format converters. Publishes lifecycle events core consumes for relationship writes — **must NOT depend on core** (engine-mode purity, Q-133) | `WorkflowDefinitionService`, `TaskCatalogueService`; v1 controllers = engine-mode surface | common |
| **engine** | Execution: run/taskrun lifecycle, DAG reconciler, claiming/watcher/sweep (Phase 3), param runtime, gates (owns `ActionEntity`), eventwait | `WorkflowRunService`, `TaskRunService`, `ActionService`, claim API; v1 run controllers | common, workflow (read) |
| **agent** | Agent protocol server (registry/queues) + **AgentRuntime SPI** (Phase 4) | protocol endpoints; `AgentRuntime` SPI (`submit/status/logs/cancel`) | common, engine |
| **schedule** | Schedule CRUD + due-time firing (claim-based due-work if Q-221/Q-227 lands) | `ScheduleService`; consumes engine's `runscheduledworkflow` event | common, workflow, engine |
| **event** | External event boundary: webhook/CloudEvent ingress; CloudEvent egress (status sink). The transport seam that survives the deployment split in engine mode | ingress endpoints; egress config | common, workflow, engine |
| **integrations** | GitHub App, Slack. Full mode only | link/unlink endpoints | common, core, workflow, engine |
| **api** | Thin full-mode composition layer: team-scoped v2 controllers + authz-scope resolution. No entities, no domain logic | the public v2 REST API | all public APIs |

**Runtime artifacts (not Modulith modules):** `agent-runtime-tekton` (today's
service-agent `kube/` code) and a future `agent-runtime-docker` (loads **in-process** for
standalone mode) — both behind the SPI.

**Cycles in current code that must be broken** (all have directed fixes):
C1 flow↔engine HTTP (callbacks invert to events); C2 engine↔agent (log retrieval becomes
`AgentRuntime.logs(handle)` — engine never calls outward); C3 schedule↔engine (engine
publishes, schedule consumes); C4 security↔core (security folds INTO core; 19
`@AuthCriteria` sites); C5 core→workflow.model inverted imports (`SettingConfig`,
`AESAlgorithm` move to core); C6 insights→audit repositories (goes through a core audit
query API).

**App-level:** per-mode composition roots; `InternalController` **dissolves** (callbacks
inverted; `/internal/token` is a flagged security hole).

---

## 2. lib-common disposition (Q-202)

Full class-by-class table produced (72 classes). Headlines:

- **No entity stays in common.** All 9 entities move to their owning module's internals
  (TaskRun/WorkflowRun/EventQueue/Action → engine; Workflow/Revision/Task/Template →
  workflow; Schedule → schedule).
- **Hard prerequisite: flatten model-extends-entity** — `TaskRun extends TaskRunEntity`,
  `WorkflowSchedule extends WorkflowScheduleEntity`, `WorkflowTemplate extends
  WorkflowTemplateEntity`. Entities cannot become module-internal until the DTOs stand
  alone. Same work as the agent-wire-contract split (review §2.3) — sequence early.
- **Enums:** nearly all → common (wire contracts). `RunPhase` stays in common for the
  agent wire but is **stripped from public API models** (the status-only invariant).
  Schedule enums → schedule.
- **Models:** contract types (Workflow, WorkflowRun, TaskRun, Run*/Param*/Task* specs,
  submit/end requests, Trigger family, workspaces) → common; query DTOs → owning module
  API (WorkflowRunCount/Insight/Summary → engine; WorkflowCount → workflow;
  WorkflowSchedule → schedule).
- **Deletions:** `Config` (dead), `WorkflowToken` (zero refs), `LockManagerNew` (100%
  commented), flow's duplicate `KeyValuePair` + `TriggerConditionOperation`, engine's
  duplicate `DataAdapterUtil`/`DateUtil`/`StringUtil` (zero imports). Engine's
  `ParameterUtil` copy (7 imports, diverged) — merge superset into common. The two
  diverged `ActionRepository` interfaces merge into engine.

---

## 3. Interaction classification (Q-204)

17 interaction groups classified. Summary: **10 of 13 flow→engine groups are (a) direct
module calls** — CRUD/reads that exist only because of the process split and stop being
cross-module calls post-merge. Key rows:

| Interaction | Classification |
|---|---|
| Run/definition/template/task CRUD + reads (10 groups) | **(a)** direct module call |
| `submitWorkflow` | **(a)** create record + **(b)** `WorkflowRunSubmitted` ApplicationEvent for kickoff (becomes THE `WorkflowRunRequested` **CloudEvent** if no-merge) |
| `eventWorkflowRun` (event ingress) | **(c)** CloudEvent — the one intrinsically-wire interaction |
| `endTaskRun` (gate resolution) | **(a)** synchronous idempotent CAS write (approver needs an error channel); DAG advance decouples internally |
| Log streaming | (a) flow→engine leg collapses; engine→agent leg stays **(c)** wire, inverted behind the SPI (C2) |
| engine→flow `createSchedule` | **(b)** `ScheduleRequested` event (engine mode: fail-fast-unsupported in v5.0) |
| engine→flow `createWorkflowRunRelationship` | **(b)** `ChildWorkflowRunCreated` event; flow-side listener writes the edge; not loaded in engine mode |
| engine→flow `submitWorkflow` callback | **delete** (dead code) |
| Agent protocol (register/poll/claims/callbacks) | **(c)** wire contract behind an `agent-protocol` API; in-process binding for standalone |
| Event sink (status egress) | **(c)** stays CloudEvents, fed by ApplicationEvents (aspects retired) |
| `/internal/settings`, `/internal/token` | **remove/lock** (security workstream) |

---

## 4. Mode loading matrix (Q-205) + gating mechanism

| Module | full | engine | standalone |
|---|---|---|---|
| common | ✅ | ✅ | ✅ |
| core.settings | ✅ | ❌ (properties-only) | ✅ |
| core.identity | ✅ | ❌ (host owns identity) | ⚠️ single static local user |
| core.relationships | ✅ | ❌ (anchor = `default`) | ⚠️ single-anchor no-op (J-C below) |
| team | ✅ | ❌ | ❌ (default team via same seam) |
| workflow | ✅ | ✅ | ✅ |
| engine | ✅ | ✅ | ✅ |
| agent (protocol) | ✅ | ✅ | ✅ (in-process binding) |
| schedule | ✅ | ❌ (host owns scheduling) | ✅ |
| event | ✅ | ⚠️ transport only (CE ingress/egress) | ✅ |
| integrations | ✅ | ❌ | ⚠️ optional-off |
| security | ✅ | ❌ (network + optional static token) | ⚠️ loaded, auth off default |
| api (v2) | ✅ | ✅ — the SAME surface, `/:team`→`default`, no auth (J1 ruling); team-mgmt/integration/schedule endpoint groups excluded | ✅ |

**Mechanism:** custom `@ConditionalOnFlowMode({FULL, STANDALONE})` meta-annotation
(plain `@ConditionalOnProperty` can't express OR-of-modes), one gate per module-root
`@Configuration` — never per-bean scatter. **Prerequisites:** controllers move into their
owning modules first (current package layout can't be gated cleanly);
`flow.auth.enabled` + `flow.authorization.enabled` unify into one property whose default
derives from mode. Per-mode boot tests (assert bean absence + API surface) are a CI
release gate.

---

## 5. Embedded-engine contract (Q-206…Q-210)

- **Workspace seam (Q-206):** team scoping enters engine code ONLY via
  `boomerang.io/team-*` annotations at 5 absence-tolerant sites; engine collections carry
  no teamId. ONE seam: a `RunScopeResolver` interface at the submit boundary —
  relationship-backed (full) vs constant-`default` (engine). Paired invariant: engine
  never writes scope (C1 inversion).
- **Protection (Q-207):** engine is `permitAll()` today — EY's protection is network-only
  *implicitly*. Contract: explicit network-only default + **optional static bearer token**
  (`flow.security.token`, ~50-line filter, not the flow auth module) — which also
  authenticates the agent protocol in ALL modes (agents currently bypass flow security
  entirely).
- **Minimal API surface (Q-208) — SUPERSEDED by J1 ruling (2026-07-22):** there is no
  separate engine-mode surface. Engine mode serves the SAME v2 team-scoped API with
  `/:team → default` and no auth; the engine's noauth V1 controllers are scrapped; the
  agent wire protocol is the only additional surface. Gates resolved by the host via the
  v2 taskrun end path. Which v2 endpoint *groups* load in engine mode still follows the
  mode matrix (team-management, integrations, schedules excluded).
- **Event ingress (Q-209):** core consumer (`WorkflowRunService.event`) is already
  transport-agnostic (plain domain request). Full mode: ApplicationEvent from the webhook
  controller. Engine mode: a CloudEvents HTTP binding unmarshals into the same consumer.
  **Flagged gap:** ingress is addressed by `workflowRunId`; host products know *topics* —
  a topic-correlation variant (query waiting eventwait tasks by topic) is a deliberate
  Phase 2 design decision.
- **EY migration (Q-210):** Mongo config, collections, prefix, and the whole `/api/v1`
  surface map across unchanged. Changes: image = product image + `flow.mode=engine`
  (publish `engine@` alias 1–2 releases); dead callback URL config removed —
  `runscheduledworkflow` becomes explicitly unsupported (it was silently broken without
  flow anyway); auth posture becomes explicit, token opt-in.

---

## 6. Q-211 evidence summary (full tables in the analysis record)

| Dimension | Verdict |
|---|---|
| Runtime isolation | Split's isolation already pierced by sync proxying + shared Mongo; merged blast radius acceptable ONLY post-Phase-3 (bounded claims replace 100k in-memory queues); VT fixes thread-count, not GC/CPU co-tenancy (bounded by caps + N replicas + sweep) |
| Scaling profiles | Heavy compute is in agents (separate either way); engine load correlates with API load; waste at realistic sizes ≈ 100–300MB beans/instance — immaterial to dozens of replicas |
| Release coupling | Real cost, but Phase 3 converts "restart = incident" (in-memory timers/queues lost) to "restart = claim churn ≤ sweep interval". Precondition: Q-127 audit passes |
| What merge buys | Deletes: EngineClient (34 URL props, ~20 error-fidelity-destroying catch blocks), WorkflowClient callbacks, BOTH unauthenticated internal surfaces, dual JobRunr, one HTTP hop per UI run query + the 3-hop log chain; fixes security once; dissolves lib-common coupling; makes standalone + unified versioning trivial |
| Honest alternative | Outcome-worse: read calls can't be eventified (keep HTTP + resilience forever, or shared-DB reads = a purely nominal boundary); two security stories forever; none of the D4 deletions happen. Effort is comparable either way and is not the deciding factor |
| Engine mode | Mode-gated merged app more honest than today's quietly-broken separate artifact; F2 (classpath compliance) is the real risk to watch |

---

## 7. Breaking changes (Q-212) — headlines

Full per-consumer inventory in the analysis record. **Six major-boundary hard breaks:**
1. Permission enforcement default-on — **the single riskiest flip in v5**: tokens have
   never been validated by production traffic (`SecurityInterceptor` soft-pass). Sequence:
   shadow-enforcement logging → token audit/backfill → per-deployment flag → default-on at
   the major.
2. Agent auth default-on + agent v1 protocol retirement (agent branches on `phase`; phase
   leaves the wire only with protocol v2).
3. `phase` removal from public API responses (frontend reads it in exactly 2 places — a
   two-line fix shipped ahead; unknown consumers gated at the major).
4. `locks` / `jr_*` / `sch_*` collection drops — forward-only rollback point; ship as a
   separate, later loader release.
5. Helm chart topology (3 Deployments → consolidated chart-major; alias images bridge).
6. Release-tag scheme change (Q-213).

**Avoidable-by-design (constraints):** never add `PAUSED`/`SUPERSEDED` as `RunStatus`
values (frontend enum is closed — use `pauseRequestedAt` + supersede flag/exclusion, and
default API responses exclude superseded TaskRuns); keep publishing
`boomerangio/flow-service-engine` as a mode-defaulted alias for ≥1 major cycle.

---

## 8. Migration plan (task 9) — merge branch

Common steps 1–8 (each independently deployable and green):
1. Phase 0 baseline upgrade (+ virtual threads).
2. Security shadow-enforcement + token audit (observability only; quantifies flip risk).
3. Token backfill + gated enforcement (`flow.security.permissions.enforce`, default off).
4. Additive schema + indexes via flow-loader (claim/lease/pause/supersede designed
   **absent-as-eligible** — zero document backfill writes); loader before engine images.
5. Engine rebuild in-place, same image: atomic claims + CAS replacing LockManager (one
   release, rolling), recovery sweep alongside JobRunr initially, `acquirelock` task types
   → TTL-lease docs, alturkovic deleted; `locks` collection left in place for rollback.
6. JobRunr retirement: due-work documents + drain window (schedules re-register from
   `workflow_schedules` — the entity is the source of truth); drop changeset ships later
   (point of no return).
7. Relationship rewrite in-place (direct queries, signatures preserved, JGraphT deleted) —
   flagged as a behavioural *fix* (stale-cache requests may now correctly 401).
8. Agent wire-contract split + protocol v2 (epoch, lease renewal, token auth); engine
   serves v1 (lenient fencing, fixed lease) + v2 simultaneously.

Merge-specific:
9. Modulith boundaries in the target §1 layout while both apps still exist (verification
   tests; engine module stays independently bootable).
10. Callback inversion behind an event abstraction (transport still HTTP between the two
    deployables — same wire behaviour, rollback = config).
11. **Merge deployables**: one artifact, `flow.mode`; publish `flow-service-workflow`
    (mode=full) AND `flow-service-engine` (mode=engine) aliases from the merged binary;
    consolidated Helm chart as a major; agent image unchanged. Run the **F1 load test
    before cutover.**
12. Unified product version (Q-213): one tag → image set; `engine@` alias line for
    embedders' deprecation window; CI trigger rework; CLAUDE.md tag-format fix.

(No-merge branch B preserved in the analysis record: same 1–8, then CloudEvents-via-
Mongo-outbox decoupling + lib-common split + compat-set manifest versioning.)

---

## 9. Q-213 / Q-214 implications under the recommendation

- **Q-213:** unified product version. No independent engine version line — engine mode is
  configuration; a separate line would recreate the compat matrix the merge deletes.
  `engine@` alias tags for a 1–2 release deprecation window.
- **Q-214:** fold the frontend in — **after** the merged image ships (shares no critical
  path; must not add risk). v5 major is the natural re-baseline for its 3.12.x history.
  Webapp served only in `full`/`standalone`, never `engine`.

---

## 10. Consolidated judgement calls for maintainer ruling

From the module analysis (J1–J8) and interaction analysis (I1–I6):

| # | Call | Status / Ruling |
|---|---|---|
| J1 | API surface shape | ✅ **RULED (2026-07-22): ONE API surface for all modes.** The same v2 team-scoped controllers/endpoints serve full, engine, and standalone. Engine mode: single default team — `/:team` resolves to `default` — and no auth; no team-less endpoint variants exist. The engine's noauth V1 controllers are **scrapped** at the merge (only the agent wire protocol survives as a separate surface). Supersedes the "minimal V1 engine surface" framing in §5(c). |
| J2 | Definition CRUD ownership | ✅ **RULED: definitions move BACK into the platform-side services.** The v4 move of Workflow/Task/Template CRUD into the engine was to create one owner; post-merge the flow-side services become that owner again — the delegation chain is deleted, the engine reads revisions via the definition module's API and no longer owns definition persistence. The definition module loads in ALL modes (hosts author workflows in engine mode, anchored to `default`). |
| J3 | Actions (approvals) owner | ✅ **RULED: the two diverged Action implementations (flow ActionService/repo + engine creation/repo) merge into ONE owning module** — engine creates gate records, single merged repository/service, resolution = `endTaskRun` CAS; team keeps approver-group membership; api composes. |
| J4 | Audit architecture + engine-mode availability | ✅ **RULED (2026-07-22): audit loads in ALL modes.** One core audit component fed by domain ApplicationEvents (the engine's AOP save-interceptor aspects retire). Engine mode: the "what" is fully captured; actor attribution = static-token principal or an optional **host-supplied on-behalf-of header** (no auth module needed). Queryable via the same v2 audit endpoint in every mode (J1 single surface). The CloudEvents status stream remains an integration feature — NOT the audit mechanism (embedders are not forced to build collectors). |
| J5 | Global params placement | ✅ **RULED: `workflow` module** (with the definition domain, not core). Engine consumes via the ParamLayers global layer unchanged; admin endpoints follow the module. |
| J6 | Insights | ✅ **RULED: fold into `team`** (renamed `workspace` per N1); goes through a core audit query API (fixes C6). |
| N1 | Naming: Team → Workspace | ✅ **RULED (2026-07-22): full rename at the v5 major.** API paths become `/api/v2/workspace/{workspace}/…` (old `/team/{team}` aliased for a deprecation window); the `team` module is named **`workspace`**; `RelationshipType.TEAM` + `AuthScope.team` values migrate via loader changeset; frontend updated with the v5 re-baseline (Q-214). Aligns vocabulary with CHEER/ARCHIE/community docs and the engine-mode "default workspace" language. Recorded as **DD-01** in the master spec. |
| J7 | Model-extends-entity flattening | ✅ **RULED: confirmed as an early-Phase-3 prerequisite** — flatten TaskRun/WorkflowSchedule/WorkflowTemplate into standalone DTOs together with the agent wire-contract split, before entities move module-internal. |
| J8 | Agent runtime packaging | ✅ **RULED (2026-07-22): the Docker runtime ships as a separate agent process for now** (same agent harness as Tekton, different runtime implementation behind the SPI). In-process embedding is NOT precluded — it remains a future option the SPI keeps open. Standalone quickstart is delivered via docker-compose (app + Mongo + docker-agent). |
| I1 | `endTaskRun` sync vs async | ✅ **RULED: synchronous idempotent CAS write** — "approve" means the gate IS resolved (or truthfully failed) when the approver gets a response; only DAG advancement decouples internally. |
| I2 | `runscheduledworkflow` in engine mode | ✅ **RULED: fail-fast-unsupported in v5.0** (explicit validation error); outbound host event only if a real embedder asks. |
| I3 | Relationships in standalone | ✅ **RULED: loaded as single-anchor no-op** — all v2 code paths byte-identical to full mode. |
| I4 | Insight/count endpoints in engine mode | ✅ **Resolved by consequence of J1+J6**: insights ride the `workspace` module, which is not loaded in engine mode. No separate ruling needed. |
| I5 | Topic-only event correlation for hosts | ✅ **RULED: design in Phase 2** — topic + optional correlation key, broadcast rules, scoping, idempotency settled before embedders integrate; implementation may land in Phase 3. |
| I6 | Agent transport abstraction | ✅ **RULED: named protocol contract** — one interface + dedicated wire DTOs in the worker-tier module; HTTP v1 stays byte-identical; v2 adds epoch/lease/auth at one seam; unblocks removing `phase` from the public API. **Per DD-06 (2026-07-23): the tier and protocol are named `dispatcher`/`DispatcherProtocol` at E7/E8** (v1 wire keeps `/agent/` paths until retirement); J8's artifacts become `dispatcher-tekton`/`dispatcher-docker`. |

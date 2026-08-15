# T4 — DD-02 Merge Execution Plan

**Status: 🔵 ACTIVE (2026-08-14).** The execution sequence for the flow/engine merge.
Architecture record: `consolidation-proposal.md` (approved). Epic backlog: `gap-register.md` §3.
This document records the *sequence, amendments, and progress* — it does not restate the proposal.

**Gate check:** the merge-train precondition ("E8–E10 starts only when E4–E7 green") is satisfied
(E4/E5/E6/E7 shipped). Standing constraints: DD-02 falsifiability F1–F5 stays live (F1 = the
pre-cutover load test is an abort gate); the E8→E10 timebox (deployment merge within 1–2 releases
of boundaries landing, else Q-211 re-opens).

## Amendments to the recorded plan (maintainer-ruled 2026-08-14)

| # | Amendment |
|---|---|
| AM-1 | **No Spring Modulith, no ArchUnit.** Module boundaries are a **principle/convention**, not a framework: single Maven module (`service-core`), flat feature packages under one root — the ARCHIE `service-core` convention (`com.ey.archie.{workspace,agent,library,…}` → ours `io.boomerang.{common,core,workspace,workflow,engine,dispatcher,schedule,event,integrations,api}`). Modulith's other features are redundant here (we have our own outbox; mode gating is plain `@Conditional` and never was a Modulith feature). This amends DD-02's *mechanism* only — one deployable + modes + module boundaries stands. F5 falsifiability is thereby moot. |
| AM-2 | **H3 (dual-serve v1+v2 agent protocol, retire at major) is moot** — `/api/v1/agent` was dropped outright at E7-4 (no v4 adoption); the dispatcher protocol replaced it directly. |
| AM-3 | Proposal step 8's protocol-v2 epoch/lease-renewal did **not** ship — worker leases + fencing are deferred post-merge until a real consumer (lease-reap sweep) exists. I6's "dedicated wire DTOs" overruled: the worker receives the plain run models. |
| AM-4 | E9's pre-filled G2 (`parentRef` + `createdByTaskRunRef`) **conflicts with the earlier ruling** that dropped run-creation dedup fields — at E9's G2 review, propose lineage via the existing `initiatedByRef` + `trigger` instead. |
| AM-5 | The engine-mode static token (Q-207 `flow.security.token`) exists as `flow.dispatcher.token` + `DispatcherAuthFilter` — E8/E10 reuse that filter; the first-class `bfd` token is post-merge (gap-register A3, check ARCHIE first). |
| AM-7 | **Two modes, not three** (maintainer 2026-08-15): `flow.mode = standalone \| engine`. FULL collapses into STANDALONE — "standalone" = the complete self-contained product (workspaces, auth, integrations, schedules; the default); "engine" = embedded headless execution. The old laptop-mode meaning of standalone is not a mode — it's the product with `flow.security.enabled=false`. Re-rules DD-02's mode list and the proposal §4 matrix (full column ≡ standalone). |
| AM-8 | **One security property** (maintainer 2026-08-15): `flow.security.enabled`, default from mode (standalone→true, engine→false). The legacy `flow.auth.enabled`/`flow.authorization.enabled` pair is DELETED at the v5 major (no alias window). Restructure-era bean-name pins (`engine*`) removed. lib-common keeps its entities until the Phase 4/T7 agent-runtime decision (if the agent folds in-process, lib-common dies in one move). |
| AM-6 | **Naming convention overrules the proposal's service names** (maintainer 2026-08-15): `<Name>Service`/`<Name>Controller` (+ `<Name>Client` external-only; `<Name>ExecutionService` engine orchestrators). The DOMAIN service keeps the plain name — `workflow.WorkflowService`/`workflow.TaskService` are the definition services (NOT `WorkflowDefinitionService`/`TaskCatalogueService` as the proposal's module table named them); the api composition shims are `Team*Service`, pairing their `Team*ControllerV2` controllers, and dissolve as H7/thin-controllers land. |

## Sequence

### E8 — boundaries + `service-core` (in progress)
- **E8.0 — prerequisites** ✅ (2026-08-14): J7 done (flattened `WorkflowSchedule` + `WorkflowTemplate`;
  `TaskRun` was E7-2); H4 dedup subset done (`Config`, `WorkflowToken`, dup `KeyValuePair`, dup
  `TriggerConditionOperation` deleted; engine `ParameterUtil` superset-merged into common — the
  erasure-clashing `List<AbstractParam>` overload renamed `abstractParamToRunParam`).
  **Discovered residue:** four flow-local model-extends-entity cases remain (`core/model/User`,
  `UserProfile`, `Setting`; `workflow/model/Action`) — within-module inheritance, flattened when
  their owning pieces restructure (`Action` matters at J3, the single Action owner).
- **E8.1** ✅ (2026-08-14) — `service-flow` → `service-core` (pure-rename commit, 197 files all
  R100, then content fixes: poms/Dockerfile/CI). Release tags + image names unchanged (DD-03/E10).
- **E8.2a** ✅ (2026-08-14) — `service-engine` physically merged into `service-core`; the module
  is deleted. Only 7 FQCN collisions (3 identical dropped; `BoomerangError` enum unioned +
  messages merged; `RestExceptionHandler`/`Application`/`MongoConfiguration` merged). Security:
  new `DispatcherSecurityConfiguration` `@Order(1)` chain owns `/api/v1/**` (permitAll +
  `DispatcherAuthFilter`); flow's chains `@Order(2)`; engine's `SecurityConfig` deleted.
  Internal flow↔engine HTTP now points at self (INTERIM — dissolution below / E9).
  `ci-engine.yml` retired (engine image ships from the merged binary at E10). G1 held:
  `DAGUtility`/`TaskExecutionService` pure R100 moves. One flagged semantic fix:
  `WorkflowWatcher.sweep()` now honors `flow.watcher.enabled` for scheduled sweeps (the kill
  switch previously gated only the boot sweep — cached test contexts kept live timers).
  Merged suite: 66 tests green in service-core.
- **E8.2b** ✅ (2026-08-14) — flow's `EngineClient` (1305→~340 lines) is in-process: signatures
  kept, bodies call the engine services directly; all 37 `flow.engine.*` properties deleted.
  Notable: the blanket catch-and-wrap-as-500 is gone, so engine `BoomerangException`s now
  propagate with their REAL codes (v2 responses that flattened engine 4xx into 500 now surface
  the true status — an observable improvement, flagged); `enableWorkflow`/`disableWorkflow`
  confirmed dead (always 404'd over HTTP) → deterministic 501. Log streaming needed zero HTTP
  (delegates to the engine's `LogClient` path unchanged). Facade dissolves fully at J2.
- **E8.2c** ✅ (2026-08-15) — the full package restructure landed via P1→P3c2 (see
  `package-move-map.md`): nine flat modules physical; house naming (AM-6 —
  `workflow.WorkflowService`/`TaskService` = definitions; `api.Team*Service` shims); cycles
  C1–C6 all resolved; `core` has ZERO upward imports; J2/J3/J5/J6 executed; **E9's callback
  inversion done in-process** (`ScheduleRequested`/`ChildWorkflowRunCreated` ApplicationEvents
  in `common.model`; engine publishes, `schedule`/`core` listen; `WorkflowClient` +
  `InternalController` deleted — **gap A4 closed**); H6 mode gates applied per the matrix +
  engine/standalone boot tests. Relationship seam: **ungated-interim** (a single-anchor no-op
  would silently corrupt access control — revisit with J1's `/:team → default` remapping at
  E10). **E7-5** ✅ — `DispatcherEntity`/`dispatchers`/`dispatcherRef` + `_0011__DispatcherRename`
  changeunit (collection rename, `$rename` on run docs), exercised by `LoaderMigrationTest`.
  Deliberately deferred to E10: J1 engine-mode default-team remapping; the api `Team*` shim
  dissolution (H7).
- **Gates:** G1 = relocation-only for `DAGUtility`/`TaskExecutionService` (zero semantic change;
  review verifies move-only). G2 = the E7-5 rename migration (the register's "None" pre-dates
  pulling E7-5 in).

### E9 — callback inversion
The 3 engine→flow HTTP callbacks (`createschedule`, `submit`, `relationship` in `WorkflowClient`)
become events (`ScheduleRequested`, `ChildWorkflowRunCreated`; dead `submit` deleted) behind an
abstraction — transport stays HTTP until cutover (rollback = config). Plus `InternalController`
dissolves (A4), C10 dedup bindings, B9 stage-2 egress, H7 `RunScopeResolver`.
**Gates:** G1 targeted (`runWorkflow`/`runScheduledWorkflow` in `TaskExecutionService`); G2 per AM-4.

### E10 — merge deployables
One artifact + `flow.mode`; alias images (`flow-service-workflow`=full, `flow-service-engine`=engine)
from the merged binary; Helm chart-major; dispatcher image unchanged. **F1 load test before
cutover — abort gate (H9).**

**DD-01 ✅ (2026-08-15, pulled forward from E11):** Team→Workspace executed on the code+path+value
layers: 24 class renames (`WorkspaceService`/`WorkspaceEntity`/`Workspace*` models/api shims+
controllers); `/api/v2/team/**` ↔ `/api/v2/workspace/**` dual mappings (deprecation window);
`AuthScope.workspace`/`RelationshipType.WORKSPACE`/`TokenTypePrefix.workspace` with "team"
input-alias compat; `_0012__WorkspaceRename` changeunit (rel_nodes type + composite-`_id`
re-key, rel_edges prefixes, tokens.type, roles.type) — idempotency proven by LoaderMigrationTest.
Deliberately wire-stable: `{team}` path var, JSON field names, `teams` collection, `bft` prefix,
`boomerang.io/team-*` keys, `PermissionResource.TEAM`/`AuditScope.TEAM` strings — swept at the
frontend re-baseline (H14).

**Review item parked at DD-01 (H13):** whether any `Team*`/`Workspace*`-prefixed composition
service still exists by then. The prefix is layer-disambiguation (vs the plain-named domain
services), not scope marking; the composition layer is expected to dissolve via H7/thin
controllers, and anything surviving is swept `Team*`→`Workspace*` at DD-01 — review THERE whether
the prefix (and the classes) should exist at all. Note the platform substrate stays genuinely
non-workspace-scoped (users, tokens, system, global catalogue/templates/params).

## Future items (maintainer-added 2026-08-15, not yet scheduled)

1. **WorkflowTemplates sunset evaluation** — possibly retire the whole template-management side
   in favour of a few out-of-the-box json/yaml types shipped statically. Evaluate before
   investing further in `WorkflowTemplateService`/controllers.
2. **`Workspace*` prefix removal on the api shims** (strengthens the H13 parked item — maintainer
   leans REMOVE): everything is bound to a Workspace anyway, so `TaskService` suffices once the
   composition/domain split resolves (H7 dissolution or the naming review at H13).
3. **Audit re-evaluation** — compare our audit implementation (core.audit: interceptor + AOP
   heritage) against ARCHIE's `services/service-core` audit approach; candidate for removal or
   replacement. Do before investing in the J4 all-modes audit build-out.
4. **Slack integration redo** — re-do the entire Slack integration (io.boomerang.integrations
   SlackService). Treat the current one as legacy; do not extend it.
5. **Standalone identity = IDPZero locally** — standalone mode uses IDPZero as its local identity
   provider (the ARCHIE pattern — see `~/Workspaces/tlawrie/asdr/.idpzero`), rather than simply
   running with security off. **Requires frontend changes as well** — so it sequences with/after
   DD-04 (frontend fold-in). Interacts with AM-7/AM-8: the standalone `flow.security.enabled`
   default and the auth filter chain get an IDPZero-backed path when this lands.

### E11 — post-merge (ordered)
H12 DD-03 versioning → A2 enforcement flip + A3 first-class `bfd` token + H2 public-`phase`
decision → H11 collection drops (`locks`, `jr_*`, `_sch_*`) → H13 DD-01 Team→Workspace + H14
DD-04 frontend → alias windows expire → H16 standalone/Phase 4. Plus deferred: worker
leases/fencing, DD-07 re-decision.

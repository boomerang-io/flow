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
| AM-10 | **No `RunScopeResolver` — H7's seam is overruled** (maintainer 2026-08-15). Scoping stays on `RelationshipService` exactly as it always has; no mode-aware indirection in the service layer. Engine mode's single-workspace reality is handled **at the edges**: **the `system` workspace IS the engine-mode workspace** (maintainer: "engine basically runs in what the admins use") — it is already seeded, unlimited-quota and undeletable, so it is guaranteed to exist and needs no invented `default`; engine mode rejects/normalises any non-`system` workspace at the controller. Auth likewise stays outside — IDPZero (future item 5) converts to a token before `RelationshipService` is ever reached. The three resolver commits were reverted. Consequence: `WorkspaceType.system` (currently inert — zero usages) becomes the meaningful marker. |
| AM-9 | **No alias images** (maintainer 2026-08-15): v5 ships on NEW infra and a NEW Helm chart — the `flow-service-workflow`/`flow-service-engine` alias-image deprecation window (H10, proposal §7, DD-03's `engine@` alias line) is DROPPED. E10 = one `service-core` image (engine mode = same image with `flow.mode=engine`) + agent + loader, fresh chart, fresh naming. Simplifies DD-03 to: one product tag → {core, agent, loader}. |
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

### E10 — cutover (reshaped by AM-7/AM-9)
- **E10-prep ✅ (Track 5, 2026-08-15)**: scoping stays on `RelationshipService` (AM-10 — the H7
  seam was built, then reverted; engine mode's single workspace is an edge concern: seed a
  `default` workspace, reject non-`default` at the controller). REMAINING for engine mode:
  that seeding + controller guard. J1 v1
  scrap done: platform v1 controllers deleted (−1121 lines); v1 = the dispatcher wire + the
  agent's four lifecycle callbacks (relocated to `dispatcher`, paths byte-identical; service-agent
  untouched). CI reshaped per AM-9: `ci-release.yml` on product tag `v@**` → three images
  (`flow-service-core` renamed from flow-service-workflow; agent unchanged;
  `flow-service-loader` NEW — the loader had no image pipeline; old public name was
  `boomerangio/flow-loader` from the legacy repo). Missing-workspace param layer tolerated
  (engine-mode default workspace has no stored record). Notable inert residue: the workflow
  delete data-loss hazard is no longer HTTP-reachable; `TaskRunService.query/get/cancel` are
  dead code pending a pruning pass; `workflow.ParameterManager`→workspace repository import
  remains (future prefix/dissolution pass).
- **F1 load test** — abort gate (H9): merged-app saturated execution vs split baseline; needs a
  real environment + maintainer read.
- **Cutover**: new infra + new Helm chart (maintainer-led); engine deployment = the same
  service-core image with `flow.mode=engine`; agent image unchanged.

**DD-01 ✅ (2026-08-15, pulled forward from E11):** Team→Workspace executed on the code+path+value
layers: 24 class renames (`WorkspaceService`/`WorkspaceEntity`/`Workspace*` models/api shims+
controllers); `/api/v2/team/**` ↔ `/api/v2/workspace/**` dual mappings (deprecation window);
`AuthScope.workspace`/`RelationshipType.WORKSPACE`/`TokenTypePrefix.workspace` with "team"
input-alias compat; `_0016__WorkspaceRename` changeunit (rel_nodes type + composite-`_id`
re-key, rel_edges prefixes, tokens.type, roles.type) — idempotency proven by LoaderMigrationTest.
Deliberately wire-stable: `{team}` path var, JSON field names, `teams` collection, `bft` prefix,
`boomerang.io/team-*` keys, `PermissionResource.TEAM`/`AuditScope.TEAM` strings — swept at the
frontend re-baseline (H14).

**H14 ✅ (2026-08-17):** the full wire-name sweep DD-01 deferred, minus JSON body field names
(still deferred to the DD-04 frontend re-baseline — the frontend consumes those directly, so
renaming them is coupled to that work, not this pass).

- **H14-a (breaking):** `/api/v2/team/{team}/**` retired outright — `/api/v2/workspace/{workspace}/**`
  is the only surface now. `{team}` → `{workspace}` path variable, `@Parameter(name="team")` →
  `name="workspace"`, and the `team`/`teams` query params on `IntegrationControllerV2`/
  `SystemControllerV2`/`WorkspaceControllerV2`'s `getWorkspaces` all renamed to match.
  `EngineWorkspaceInterceptor(Configuration)` now matches `{workspace}` only.
- **H14-b:** `boomerang.io/team-name`/`boomerang.io/team-params` → `boomerang.io/workspace-name`/
  `boomerang.io/workspace-params` (12 code sites). Persisted on `workflow_runs`/`task_runs`
  `annotations` (the `#`-escaped map keys) — `_0016__WorkspaceRename` now `$rename`s both.
- **H14-c:** `PermissionResource.TEAM` → `WORKSPACE` (label `"team"`→`"workspace"`, alias kept) and
  `AuditScope.TEAM` → `WORKSPACE` (raw enum name `"TEAM"`→`"WORKSPACE"` — no alias possible, since
  `AuditScope` has no custom parse path at the Mongo boundary; a stray `"TEAM"` would throw on
  load, so `_0016`'s rewrite is load-bearing, not cosmetic). `_0016` also rewrites
  `roles.permissions[]` and `tokens.permissions[].actions[]` (`"team/x"`→`"workspace/x"`) and
  `audit.scope`.
- **H14-d:** `WorkspaceEntity`'s collection renamed `teams`→`workspaces`. Every v3/fresh-install
  unit earlier in the loader chain (`_0003`/`_0007`/`_0008`/`_0012`/`_0013`) deliberately keeps
  targeting the literal `teams` name; `_0016` (ungated, running after all of them) does a plain
  `renameCollection` once nothing else needs the old name.
- **H14-e:** `TokenTypePrefix.workspace` moved `"bft"` → `"bfk"` ("worKspace" — the next free
  letter, since `"bfw"` is workflow's). **Deprecation window:** only the SHA-256 hash of the full
  raw token is stored, so an already-issued `bft_...` token can never be rewritten in the
  database — there is nothing to migrate, and both prefixes are accepted indefinitely (no fixed
  retirement date; `bft` drops only once every token minted under it has naturally expired, at
  whichever future release removes the alias). `TokenTypePrefix.TOKEN_PATTERN` (the pre-DB shape
  gate `DispatcherAuthFilter`/`AuthenticationFilter` both rely on) and `BY_PREFIX` both accept
  `t`/`bft` alongside the new `k`/`bfk`; new tokens mint with `bfk` only
  (`TokenService.create`).
- **H11:** new changeunit `_0027__V4DropResidualCollections` prefix-scans for `jr_*`/`_sch_*`
  (JobRunr's old table-prefix conventions — `<collectionPrefix>jr_*` for the engine's retired
  timeout-job instance, `<collectionPrefix>_sch_*` for flow's retired schedule-firing instance,
  RAW string concatenation exactly matching the historical `org.jobrunr.database.table-prefix`
  properties, not `CollectionNames.resolve`'s own separator convention) and drops the genuinely
  unprefixed `locks` collection `alturkovic/distributed-lock` wrote verbatim (distinct from
  `task_locks`, never touched). Ungated (cheap, idempotent, no-op wherever nothing matches) rather
  than V4-gated, since our own pre-E5 dev/test environments can carry this residue on a
  `FRESH`-generation install too.

**Review item parked at DD-01 (H13):** whether any `Team*`/`Workspace*`-prefixed composition
service still exists by then. The prefix is layer-disambiguation (vs the plain-named domain
services), not scope marking; the composition layer is expected to dissolve via H7/thin
controllers, and anything surviving is swept `Team*`→`Workspace*` at DD-01 — review THERE whether
the prefix (and the classes) should exist at all. Note the platform substrate stays genuinely
non-workspace-scoped (users, tokens, system, global catalogue/templates/params).

## Bootstrap seeding ✅ (2026-08-15)

v5 had **no seeding path** — the legacy `boomerangio/flow-loader` image did it all, and
`service-loader` was indexes+migrations only. A fresh install would have failed on first
user/workspace creation (`RelationshipService.createNodeAndEdge` → `resolveNodeOrThrow` throws
without the `root` node). Full parity ported into changeunits `_0013`–`_0018` (+ `SeedResources`
helper, seed JSON under `service-loader/src/main/resources/seed/`): `root` relationship node ·
the **`system` workspace** (unlimited quotas, undeletable, + its graph edges and legacy
admin-membership replication) · 5 roles · 7 settings · the 87-task / 130-revision catalogue and
its global task graph · workflow + integration templates. All insert-if-absent (idempotent,
non-destructive to upgrades); `LoaderMigrationTest` covers fresh-DB, upgraded-install, and
double-run-with-audit-log-dropped.

## v3 → v5 migration consolidation 🔵 (maintainer-raised 2026-08-16)

**Problem:** every external install is on **v3** — only our own environments went to v4. Today
`_0001__BaselineExistingInstall` assumes a v4-current install, so there is no working external
upgrade path: it would need the legacy Mongock v3→v4 chain AND the v5 Flamingock chain (two
loaders — contradicting AM-9's single-image story), carrying churn from v4 changesets that
iterate on each other plus v4 work v5 has since renamed or undone.

**Target:** `service-loader` alone takes a **v3 database to the v5 end state**, via consolidated
("squashed") changeunits that migrate DIRECTLY to v5 shapes — never through an intermediate a
later changeunit rewrites.

**Scout complete (2026-08-16).** Legacy inventory: v3 baseline = `FlowDatabaseChangeLog` orders
001–112 (verified 100% v3-era); v4 = `FlowDatabasev4ChangeLog` orders 4000–4048. Classification:
**31 KEEP · 7 DROP · 5 SQUASH · 1 SPLIT**. Generation discriminator for a live DB:
`sys_changelog_flow` contains `112` but not `4000` ⇒ v3.

*Most important KEEP:* `4041` (relationship-model introduction) — retargeted to write
`workspace:<ref>` node ids/edge prefixes DIRECTLY (never v4's `team:`, which `_0012` would then
have to rewrite — and `_0012` runs BEFORE the new units, so a `team:` write would never be fixed).
*Notable DROPs:* `4007`/`4012`/`4031` (relationship intermediates superseded by `4041`),
`4015`/`4023` (superseded by our `_0014`/`_0015` seeds), `4022`/`4024` (Quartz — and `4022`
targeted a `quartz` collection while v3 writes `jobs`, so it was a **no-op on every real v3 DB**).
*Correction:* JobRunr `jr_*`/`_sch_*`, EventQueue and the duplicate-audit work do **not** exist in
the legacy loader — those are v5-side concerns only.

**Blockers found in our own just-shipped seeds (fixed immediately):** `_0016__SeedSettings` guards
on `key` while all 7 seed `_id`s already exist in a v3 `settings` collection under different keys
⇒ `DuplicateKeyException` **aborts the whole run**; `_0017__SeedTaskCatalogue` pre-creates `tasks`
with the exact 87 `_id`s a v3 install still holds in `task_templates` ⇒ blocks the migration;
`_0018` duplicates template content whose source workflows still live in `workflows`. Seeds are now
generation-aware (skip on v3; unchanged on v4/fresh). Note `_0001__BaselineExistingInstall` logs
only — it cannot distinguish v3 from v4, so generation detection is explicit.

**Data already lost on v4 installs** (v3→v5 can be written correctly; v4 cannot be fully repaired):
`taskVersion` is `null` everywhere — `4005`'s `Document.replace` on a fresh `Document` is a no-op,
and `4033`/`4034`/`4035` each read the new (absent) key instead of `templateVersion`; approver
groups vanished (`4011` strips `teams.approverGroups[]`; nothing ever wrote `approver_groups`);
no workflow audit records (`4038` matched `"WORKFLOW"` against lowercase node types).

### Maintainer rulings (2026-08-16)

| # | Ruling |
|---|---|
| M-1 | **Personal workspaces KEEP** — v3→v5 recreates a personal workspace per user (as `4014` did) with a `memberOf` edge; `WorkspaceType.personal`. |
| M-2 | **Best-effort v4 repair units** — add changeunits repairing what IS recoverable on v4 installs (re-derive `taskVersion` from `task_revisions` where unambiguous; rebuild workflow audit records from `rel_edges`). Approver groups are unrecoverable on v4 (source gone) — document, do not fake. |
| M-3 | **CosmosDB is NOT a supported v5 target** — `renameCollection` is fine; `_0011__DispatcherRename` stays as-is; consolidated units may use renames freely. |
| M-4 | **A real v3 dump will be provided** — the three schemas unverifiable from loader source (`global_params`, `workflows_activity_approval`, v3 `approver_groups` embedding) are validated against it before those units are trusted. |

### Validated against a real v3 dump (2026-08-16)

Source: `~/Workspaces/cheerdev/ops/**flowabl-live-dump-20231106**/boomerang` (16MB, 23 collections
with data). **Generation proven v3**: 111 changesets applied, max id `112`, zero `4xxx` — exactly
the scout's discriminator. (The dump first suggested — `flowabl-dev-dump-20230505` — is
metadata-only, no `.bson` documents at all, and is *v4-shaped*: it carries `relationships`/
`actions`/`workflow_templates`. It was the dev environment already running v4 development while
live stayed on v3. Not usable.)

| Check | Result |
|---|---|
| `settings` | **Confirms the `_0016` blocker.** All 8 v3 `_id`s present with v3 keys (`controller`, `activity`, `workflow`, `users`, `features`, `teams`, `extensions`, `customizations`) — exactly the ids the seed would re-insert. |
| `workflows_activity_approval` (8 docs) | `{_id, activityId, taskActivityId, workflowId, actioners[{approverId,comments,approved,actionDate}], status, type, creationDate, numberOfApprovers}`. **4003 mutates whole documents in place**, so `actioners[]`/`numberOfApprovers` DO survive — v5 `ActionEntity` has both. No loss here (an earlier suspicion, checked and withdrawn). |
| `teams.approverGroups[]` (28 teams) | Field present on 2 teams, both **empty arrays** — no approver-group data exists on this install, so R-6's v4 loss had no data impact here. Populated shape still unvalidated; handle empty gracefully. |
| **NEW BUG — global parameters never migrated** | v3's collection is **`global_config`** (1 doc: `{_id, key, label, type, value, description, readOnly}`, `_class=FlowGlobalConfigEntity`), but changeset **`4045` reads `global_params`** and maps `values`→`value`. On real v3 data it matches nothing: **global parameters were silently dropped in v4, and the source collection was never even dropped.** The consolidated unit must read `global_config` and map `key`→`name`, `value`→`value` (singular). Verify the collection name against other installs before finalising. |

### v3→v5 implementation batches (chained; each verified against the real dump before the next)

| Batch | Changeunits | Depends on | Notes |
|---|---|---|---|
| **F — foundation** | real-dump test harness · `_0019__LegacyGenerationDetect` · `_0020__V3DropDeadCollections` | — | `_0019` MUST capture the generation before any mutation: once `_0020` drops legacy collections the DB no longer looks v3 and detection would flip mid-chain. Dump referenced by path, never committed (real production data, public repo); test skips when absent. |
| **A — standalone data** | `_0021__V3MigrateSettings` · `_0031__V3MigrateGlobalParameters` · `_0033__V3Indexes` | F | `_0031` reads **`global_config`** (NOT v4's wrong `global_params`) mapping `key`→`name`, `value`(singular)→`value`. Settings: rename keys to `task`/`workflowrun`/`integration` + config-key renames; delete the `users` doc. |
| **B — task catalogue** | `_0022__V3MigrateTasks` · `_0026__V3MigrateTaskRunRefs` · `_0034__V3ReconcileCatalogue` | F | Single pass `task_templates` → `tasks` + `task_revisions` with `parentRef` and `spec.params[]` merged (squashes 4004+4030+4032+4043). Reconcile against `_0017`'s 87 seeded tasks by legacy `_id`. |
| **C — workspaces & users** | `_0027__V3MigrateWorkspaces` · `_0028__V3MigrateUsers` | F | Quotas → v5 names (`maxWorkflowRunMonthly`/`maxWorkflowRunDuration`/`maxConcurrentRuns` + new `maxWorkflowRunStorage`); extract `approverGroups[]` → `approver_groups` (empty in the dump — handle gracefully, shape unvalidated); **personal workspace per user (M-1)**. |
| **D — workflows & runs** | `_0023__V3MigrateWorkflows` · `_0024__V3ExtractWorkflowTemplates` · `_0025__V3MigrateRuns` | B | Fix the v4 `taskVersion` bugs at source (4005's no-op `replace`, 4033/4034/4035 reading the new key). Single-pass slug+`displayName`. Runs: status/phase mapping + `initiatedByRef` (4002 computed then dropped it). |
| **E — relationship graph** | `_0029__V3BuildRelationshipGraph` · `_0030__V3SystemWorkspaceMembers` · `_0032__V3SeedAudit` | B,C,D | **The most important slice.** Write `workspace:<ref>` node ids/edge prefixes DIRECTLY — `_0012` runs BEFORE these units, so a `team:` write would never be corrected. No `_class`. Audit parent resolved via `rel_edges` (4038's lookup never matched). |
| **G — cleanup & v4 repair** | `_0035__V3DropIntermediates` · v4 repair units (**M-2**) | E | Repair what IS recoverable on v4 installs: re-derive `taskVersion` from `task_revisions` where unambiguous; rebuild workflow audit records from `rel_edges`. Approver groups unrecoverable on v4 — document, never fake. |

### Post-G consolidation review (maintainer-requested 2026-08-16)

Once Batch G lands, review **every** changeunit as a whole: ordering, numbering, and whether any
should be merged. **Nothing has run against a real production database yet** (only the dump
harness), so units may be freely renumbered, reordered, merged or split — the audit store has no
production history to respect. Specific things to examine:

1. **Seeds vs migration ordering (the big one).** `_0013`–`_0018` (bootstrap seeds) currently run
   BEFORE the v3→v5 units, which is the ONLY reason they needed generation-aware skip logic
   (`_0016`/`_0017`/`_0018` skip on v3 to avoid `DuplicateKeyException` and id collisions). If the
   v3 migration ran FIRST and the seeds after, that skip logic could largely disappear — the seeds
   would simply be insert-if-absent over already-migrated data. Simpler and less conditional.
2. **Units separated from their siblings**: `_0026` (task-run refs) belongs with `_0022` (tasks);
   `_0034` (catalogue reconcile) likewise; `_0031` (global params) and `_0032` (audit) are
   isolated. Numbering came from the scout's proposal, not from execution logic.
3. **Merge candidates**: units that always run together over the same collections in the same
   generation could collapse into one, reducing the audit-log surface and the number of passes
   over 18k+ documents.
4. **v3-only vs v4-only gating**: verify each unit's gate is right, and that a FRESH install skips
   everything it should.
5. Re-verify the whole chain against the real dump after any reordering — order changes are
   exactly where a working migration silently breaks.

## Track 6 rulings (2026-08-17) — informed by the ARCHIE reference implementation

**T6-1 — Dispatcher token follows ARCHIE: `actorKind`, NOT a new prefix.** The earlier plan
(`AuthScope.dispatcher` + a `bfd` prefix, gap-register A3) is **overruled**. ARCHIE — the most
recent implementation — deliberately keeps four scopes and expresses machine identity as an
**orthogonal `TokenActorKind` (`SERVICE`/`AGENT`)** plus `createdBy` (server-injected, never
trusted from the request body) and `lastUsedAt`. So a dispatcher token is an existing global
(`bfg_`) token carrying an actor-kind discriminator — no new `AuthScope` value, no new prefix, no
enum value migration. Least deviation from the proven model.
*ARCHIE's honest gap, inherited:* it has no dispatcher/worker tier and no ephemeral per-job
credential (explicitly deferred in its `authentication.md`), so there is no precedent to copy for
short-lived worker credentials — only the scaffolding.

**T6-2 — Index failures: fail loud for unique, warn for the rest.** `MigrationUtils.ensureIndex`
currently swallows every failure (logs, returns `false`, no caller checks) — so a unique index can
silently not exist while the migration reports success. Fix: a **unique**-index failure throws and
aborts the migration (we dedupe first, so a failure means the dedupe missed something — a real
integrity signal); non-unique/performance indexes keep swallow-and-warn. Also set
`spring.data.mongodb.auto-index-creation=false` **explicitly** — in ARCHIE it is off only by
framework default, which they flagged as a latent risk.

Patterns worth porting later from ARCHIE's token layer (not in this track's scope): positive-only
lookup cache (60s TTL, explicit eviction on revoke, misses never cached, capacity clear); two-tier
permission revalidation (eager push + lazy `permissionsUpdatedAt` vs `updatedAt` compare on cache
miss); stashing the full token entity in `Authentication.details` so authZ needs no second read;
prefix-regex pre-DB gate; throttled `lastUsedAt` writes; sampled auth-failure auditing.

**T6-3 — Token model restructured from scope-typed to actor/ceiling-typed.** `TokenEntity` had
`type: AuthScope` (one scope) + `principal` (one) but `permissions: List<ResolvedPermissions>`
where each grant carried its *own* scope+principal — scope was already multi-valued in grants
while the top level forced one, and the grants could (and structurally did, for `workflow`-typed
tokens) contradict the top-level field. Maintainer-ruled after comparing GitHub (actor prefixes),
Slack (bot vs user), OpenAI (service accounts), Stripe (privilege-ceiling prefixes `sk_`/`rk_`),
and incident.io (roles + plural `team_ids`): **prefixes encode actor or privilege ceiling, never
resource scope.**

*Four token classes* (`TokenEntity.type`, and the raw-token prefix):

| Prefix | Class | Creation authority | Ceiling |
|---|---|---|---|
| `bfs` | `session` — human, short-lived | login/exchange | that user's access |
| `bfu` | `user` — human PAT, long-lived | the user | that user's access |
| `bfk` | `key` — machine (service/agent/workflow), workspace-bound | workspace owner | granted workspaces only — **never** a global grant |
| `bfg` | `global` — platform/admin | admin only | everything |

`workspace` (renamed to `key`, same `bfk` prefix) and `workflow` (folded into `key` +
`actorKind=WORKFLOW`, `principal=<workflowId>`) are retired as top-level classes. `actorKind`
(`SERVICE`/`AGENT`/`WORKFLOW`) is the orthogonal machine-actor discriminator, unchanged from T6-1
except for the new `WORKFLOW` value.

*Enum split (the crux).* `AuthScope` used to serve two jobs: the token's own class AND each
grant's scope (`ResolvedPermissions.scope`). Split into two enums — **kept the name `AuthScope`**
for the token-class enum (now `session|user|key|global`) since every one of its ~350 references
across the codebase (`TokenEntity.type`, `Token`/`TokenCreateRequest`/`TokenCreateResponse`/
`SessionToken`/`AuditActor.type`, `@AuthCriteria.assignableScopes`, `TokenRepository`,
`IdentityService`, `RelationshipService`'s `identity.getType()` switches, `SecurityInterceptor`)
was already in a token-class position — renaming would have been pure churn with no clarity gain.
A **new `PermissionScope`** enum (`global|workspace`, named after ARCHIE's equivalent) took over
the 3 genuine grant-scope positions: `ResolvedPermissions.scope`, `RoleEntity.type`, `Role.type`
(`roles.type` was already confirmed grant-scope-shaped in real usage — `RoleRepository` is only
ever queried with the literal strings `"workspace"`/`"global"`).

*Invariants enforced in code* (`TokenService`): (1) `assertKeyTokenCeiling` rejects any `key`
token whose permissions carry a `global`-scoped grant — structurally unreachable through the
public `create()` API (grant scope is always derived server-side from the token's class: `key` →
`workspace`, `global` → `global`), so the guard is tested directly against a constructed
`TokenEntity`; (2) `create()` requires the caller's current identity to already hold a
`global`-scoped grant before minting a `global` token (`TOKEN_ADMIN_REQUIRED`) — the internal
bootstrap path (`createSessionToken`) never calls `create()`, so the first admin session token is
unaffected; (3) `createdBy` was already server-injected from the authenticated identity, never
the request body (T6-1) — reconfirmed, `TokenCreateRequest` has no `createdBy` field to spoof.

*No backward-compatibility window* (maintainer correction, simplifying the original draft): the
`bft`/`bfw` raw-token prefixes are dropped OUTRIGHT from `TokenTypePrefix`'s pre-DB shape gate —
no deprecation period. A `bft_`/`bfw_` bearer fails the cheap shape gate exactly like any
non-Flow bearer, before ever reaching Mongo (only the SHA-256 hash of the full raw token is
stored, so there is no way to rewrite an already-issued raw token onto a new prefix regardless).
Loader changeunit `_0028__TokenClassRestructure` therefore **deletes** (not renames)
`tokens.type in ["workspace","workflow"]` outright and logs the count — operators re-issue,
matching the existing "legacy v3 tokens are never migrated forward" posture (`_0026__TokenIndexes`).
`global`/`user`/`session` tokens are completely untouched (narrow filter). No `roles`/grant-scope
data needed migrating: `roles.type` was already `workspace`/`global` (seeded fresh, renamed from
`team` by `_0016`), and every `workflow`-scoped `ResolvedPermissions.scope` only ever existed
inside a `workflow`-typed token document, deleted wholesale by the same unit. (The real v3 dump
carries no `tokens`/`roles` collections at all — legacy v3 tokens are a different shape dropped
by `_0004`, and `roles` is v5-only — so this unit's behaviour is proven against
`LoaderMigrationTest`'s synthetic fixture, re-verified idempotent and narrow via a surviving
`global`-typed token fixture, not the real dump.)

## Track 7 — DD-04 frontend fold-in (2026-08-18)

**T7-1 — Folded in at `client-web/` via `git subtree`, full history preserved.** Source is the
**v4 line** (`flow.client.web` `main`, tag `4.0.0` + 3 commits), NOT 3.12.x — DD-04's original
wording was stale. The `3.12.0` in `package.json` is a dead field; releases here are tag-driven
(`4.0.0`, `4.0.0-beta.290`), exactly as on the backend whose poms still read `4.0.0`/`1.0.0`. The
webapp joins the DD-03 unified product version at **5.x**. 873 files, 2,003 commits imported
(monorepo now 3,377); `.git` grew ~8 MB, so preserving history cost almost nothing.

**T7-2 — Packaging: separate image, built from the monorepo.** Keeps today's model (its own Node
container, `boomerang-webapp-server serve`, runtime `rewriteAssetPaths`, `BASE_URL` from
`PRODUCT_SERVICE_ENV_URL`); becomes the **4th image** on the product tag alongside core, agent and
loader. Mode gating lives in the chart — engine mode simply does not deploy it (AM-7: the webapp
is standalone-only). **Deferred for later discussion: bundling the Vite output into
`service-core`'s static resources** for a single deployable — that would drop the node server and
the runtime asset-path rewriting, so it is a behavioural change, not just packaging.

**T7-3 — Full Workspace rename in the frontend, not just URL repointing.** H14 retired
`/api/v2/team/**`, so the app cannot talk to `feat-v5` at all: 45 `/team/` URL sites in
`servicesConfig.ts`, consumed by 75+ files. Beyond repointing to `/workspace/`, the frontend's own
`Team*` types, components, variables and user-facing copy are renamed, completing DD-01 in the UI
so backend and frontend do not carry split vocabulary.
*Verified compatible:* the frontend reads `TaskRun.phase` at 2 sites — kept at E7-2. It declares
`WorkflowRun.phase` but never reads it, so E7-2's removal there is harmless.

### T7 integration findings (fold-in scouting, not yet actioned)

**T7-F1 — The webapp has no login flow of its own.** `axiosGlobalConfig.ts` is three lines
(`axios.defaults.withCredentials = true`); identity is bootstrapped from `GET /profile` on the
session cookie, and there is no sign-in route, no OIDC client and no 401 handling anywhere in
`src/`. The app therefore assumes an **authenticating reverse proxy in front of it** — true on the
IBM-era deployments it grew up on, NOT true for a fresh install. This is the concrete shape of the
already-recorded "standalone uses IDPZero locally, which requires frontend changes" item: the
frontend needs a real sign-in path plus 401→re-auth handling, and the session it obtains must
mint the `bfs` session token the restructured token model expects. **Blocks a usable standalone
install; does not block engine mode** (no webapp there).

**T7-F2 — A dangling second backend.** `servicesConfig.ts` declares `CORE_SERVICE_ENV_URL`
(legacy Boomerang "core services" platform) alongside `PRODUCT_SERVICE_ENV_URL`, but the entire
v5-irrelevant dependency is used for exactly **one** call: `getUserProfileImage` →
`${CORE_SERVICE_ENV_URL}/users/image/{email}`. v5 ships no such service. Drop the base URL and
render avatars locally (initials/Gravatar) — a one-endpoint change that removes a whole external
platform dependency.

**T7-F3 — Client-side route vocabulary is Team-shaped too.** `appConfig.ts` carries the *browser*
routes (`/:team/manage/tokens`, `/:team/editor/:workflow/...`) independently of the API URLs in
`servicesConfig.ts`. The Workspace rename must cover both, and changing `appConfig.ts` changes
**user-visible URLs** — existing bookmarks and deep links break. Decide explicitly whether v5 ships
redirects from the `/:team/*` shapes or accepts the break at the major.

**T7-F4 — The token UI is broken beyond renaming; it needs rework.** `Constants/index.ts` declares
`TokenType = {User:"user", Workflow:"workflow", Team:"team", Global:"global"}`. The restructured
model is `AuthScope = {session, user, key, global}` with an **orthogonal** `TokenActorKind =
{SERVICE, AGENT, WORKFLOW}`. So the token screens (`/admin/tokens`, `/:team/manage/tokens`) offer
two classes the backend **deletes on migration** (`workflow`, `team`/`workspace`) and omit `key`
entirely — now the principal creatable class. This is not a rename: the UI needs a class selector
plus an actor-kind selector, because actor kind is what `workflow` used to encode. Highest-value
frontend follow-up after the API repoint.

**T7-F5 — `RunPhase` was missing `queued`** (frontend had pending/running/completed/finalized;
backend leads with `queued`). Benign at the only two read sites — both test `=== Completed`, so an
unmatched value simply hides an approval/manual action button — but the enum was wrong and any
exhaustive handling added later would have silently mis-branched. **Fixed** in this track (one
line). Cross-checked at the same time: **`RunStatus` matches the backend exactly**, all ten values
and spellings — the "never add `PAUSED`/`SUPERSEDED` to `RunStatus`" invariant has held, and pause
correctly remains invisible to the frontend enum.

## Latent bugs found while testing (unfixed, out of scope when found)

1. **`RelationshipService.filter` NPEs when no principal is on the `SecurityContext`.** Surfaced
   writing MockMvc tests for the engine-mode guard (worked around there by seeding a minimal
   `global` token). Real exposure: any code path reaching `filter` without an authenticated
   principal — engine mode runs with security off, so this is worth a guard.
2. **Content negotiation with no `Accept` header** inconsistently resolves to
   `application/x-yaml` vs JSON depending on the handler's return type (the YAML converter
   registers globally — see `workflow.config.Yaml*`). A client omitting `Accept` can get YAML
   where it expects JSON. Fix is to constrain the YAML converter to the endpoints that want it.

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
H12 DD-03 versioning → A2 enforcement flip + H2 public-`phase` decision → ~~H11 collection drops
(`locks`, `jr_*`, `_sch_*`)~~ ✅ done, see the H14/H11 record above → ~~H13 DD-01 Team→Workspace +
H14~~ ✅ H14 done (JSON body field names deferred to DD-04) → DD-04 frontend → alias windows
expire (H14-a's `/api/v2/team` retirement already landed early; H14-e's `bft`→`bfk` token window
still open, no fixed end date) → H16 standalone/Phase 4. Plus deferred: worker leases/fencing,
DD-07 re-decision. (A3 first-class `bfd` token is superseded — see T6-1: dispatcher identity is
`bfg` + `TokenActorKind`, not a new prefix.)

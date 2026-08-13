# E4 Implementation Review — Findings & Deferred Fix Plan

**Status:** 📋 Captured 2026-07-25 from a four-way parallel critical review of the E4
execution-model code (branch `e4`, tip `7b9aba08`). **No fixes applied.**

**Sequencing ruling (2026-07-25):** these findings are NOT actioned now. The order is
**(1) implement E5 → (2) a critical RE-REVIEW of this list against the post-E5 code → (3)
then the bug/refactor fixes** on whatever still stands. Rationale: E5 (scheduling-substrate
retirement) and the DD-02 flow/engine merge are expected to resolve or reshape a number of
these, so fixing now would be redundant or churned. The re-review after E5 produces the
final actionable list.

## Reconciliations (false alarms corrected during synthesis — do NOT re-raise)

- **Outbox / `workflows.status` are indexed** by the loader (`_0007` dispatch page + TTL;
  `_0010` workflow status), not by entity annotations — they are NOT collection scans. Only
  refinement: the outbox dispatch index is `{status, occurredAt}` while the query also
  filters `retry.after` (minor residual).
- **No field-name mismatch exists.** Every dotted path (`claim.by`, `retry.after`, …) is
  spelled identically across models, services, and loader (grep-verified). No silent
  CAS-failure typo bug.
- **`claim.leaseExpiresAt` is NOT dead — it is E7-pending.** The e4-gate ruled worker tasks
  use `timeoutAt` budget only until E7's renew endpoint (no leases written yet). The field +
  sparse `lease_sweep` index are intentional pre-provisioning. Leave them.

## Disposition legend

- **E5** — expected to be absorbed / naturally done as part of E5 (scheduling substrate).
- **MERGE** — defer to the DD-02 flow/engine merge (structural / index authority).
- **FIX** — standalone; not resolved by E5 or the merge; action after the post-E5 re-review.
- **BUG** — correctness defect (tracked separately from refactor).

## A. Correctness bugs (BUG — confirm still-present at re-review, then fix)

| # | Where | Defect | Note |
|---|---|---|---|
| A1 | `TaskExecutionService.getTaskWorkspaces` ~:554 | Builds each `TaskWorkspace` but never `.add()`s it → every template/script/custom/generic task ships an **empty workspace list** to the dispatcher. | ✅ **FIXED** in the safe-cleanup batch (`.add(tw)`). |
| A2 | `AgentService` claim → response | Claim responses shipped the **pre-image**: agent received old `phase` (`pending`, not `queued`) and old `agentRef`. | ✅ **FIXED in Track 1** (`85d9a3c6`): winner's pre-image patched (`setPhase(queued)`/`setAgentRef(claimedBy)`) like `tryStartExecution`; new `AgentQueueClaimTest.claimResponseCarriesPostClaimPhaseAndOwner` asserts the wire payload (the bug had zero coverage). |
| A3 | `TaskRunService.tryComplete` | ~~Fencing weaker than `fence()`.~~ **NOT A BUG (2026-07-28).** Routing `tryComplete` through `fence()` was tried in Track 1 and **reverted**: the no-identity (empty-Optional) completion path is **load-bearing** — cancel/timeout/system paths complete tasks with no claimant identity, INCLUDING agent-claimed ones. `fence(null)` (requires `claim.seq` absent) would silently block cancel/timeout from completing a claimed task. The weak fencing is intentional. Any hardening belongs INSIDE the idempotency-audit (F2, Phase 3) with the full fencing/lease model — never standalone. | RE-CLASSIFIED → Phase 3 (F2). |
| A4 | `TaskExecutionService.updateStatusAndSaveTask` failed-branch ~:1123 | On `RunStatus.failed` + message, only logs — never persists `statusMessage`. | ✅ **FIXED** in the safe-cleanup batch (persist the message). |

## B. Dead code (FIX — pure deletion; re-check E5 didn't already remove)

| # | Where | Item | Disposition |
|---|---|---|---|
| B1 | `EventSinkService` :56-92,135-157,173-200 | ~140 lines: old `publishStatusCloudEvent(*)` + `httpSink()` fire-and-forget path superseded by CloudEventsBridge/OutboxDispatcher; unused `EventQueueRepository` field. | FIX |
| B2 | `ParameterManager` :322-448 | ~125 lines of commented-out v3 `replaceProperties`/`getEncodedPropertiesForMap`. | FIX |
| B3 | `TaskExecutionService` :78 | Unused `@Autowired JobScheduler jobScheduler`. | **E5** (step 1 deletes the engine JobRunr use). |
| B4 | `TaskRunEntity` :46, `AgentEntity` :24 | Commented-out fields (`//retries`, `//workflowTypes`). | FIX |
| B5 | `TaskRunEntity` :85-108, `WorkflowRunEntity` :78-124 | Hand-rolled `toString()` overrides already drifted (omit `claim`/`retry`/`timeoutAt`/`pauseRequestedAt`) fighting Lombok `@Data`. Delete → use Lombok's. | FIX |
| B6 | `TaskExecutionService` :3 | Jackson 2 (`com.fasterxml…`) straggler vs Jackson 3 (`tools.jackson…`) elsewhere; also `new ObjectMapper()` per call on hot paths (here + `ParameterManager`). | FIX (watch Jackson 2↔3 API diffs). |

## C. Duplication (FIX / E5 — extract shared helpers)

| # | Where | Duplication | Disposition |
|---|---|---|---|
| C1 | `TaskRunService` + `WorkflowRunService` (~13 methods) | The CAS shape (build Criteria → Update → `findAndModify` returnNew(false) → `if preImage!=null publish`) repeats ~13×. A typed `compareAndSet(...)`/transition helper collapses ~10 lines→~3, criteria/update stay inline. | FIX |
| C2 | `TaskRunService` :368, `WorkflowRunService` :370 | Two structurally identical `publish()` helpers → one shared utility. | FIX |
| C3 | Backoff curve | Byte-identical `nextRetryAt` (10s×2, 5m ceiling, jitter) in `WorkflowWatcher` :231 and `OutboxDispatcher` :147 → extract `Backoff` util. | **E5** (ScheduleWatcher's failed-fire retry needs the same curve). |
| C4 | `TaskRunService` :56/:364, `WorkflowRunService` :75/:219 | `TIMEOUT_GRACE_MILLIS` + `timeoutAt = start+budget+grace` duplicated; also `tryAdmit` byte-identical across both. Extract `RunTimeouts.deadline(...)`. | FIX |
| C5 | `TaskExecutionService` :102-129/:210-237 + 5× `durationSince` | ~28-line "workflow-phase-invalid → terminate task" preamble + 5 copies of the duration calc. Extract helpers. | FIX |
| C6 | `DAGUtility.createGraph` :76-81 | O(N)-per-edge name→id lookup → build one `Map` (also a perf item, D-series). | FIX |
| C7 | `WorkflowWatcher` 7 sweeps | Structurally identical page→for-each→try/catch/log; a `sweepPage(name, finder, action)` runner de-dups AND fixes E1 (finder-call not protected). | **E5** (ScheduleWatcher adds an 8th sweep — do the abstraction when it lands). |

## D. Performance (FIX — hot-path; the N+1 removals need characterization tests)

| # | Where | Issue | Risk |
|---|---|---|---|
| D1 | `DAGUtility.updateGraphWithTaskRunStatus` :294-321 | `findById` per node though the fresh list is already held → **O(N²) DB reads per admission check**. | med — removing the re-read changes freshness under concurrency; gate behind a test. |
| D2 | `DAGUtility.createTaskList` :99-107 | `findFirstByNameAndWorkflowRunRef` per task → one batch fetch + name→entity map. | low |
| D3 | `DAGUtility.createGraph` :68-91 | O(N·D) name lookup (see C6). | low |
| D4 | `ParameterManager.resolveParam` :223-248 | Per-`$(tasks.x.results.y)`-token DB query, no memoization within one `resolveParamLayers`. | low |
| D5 | `TaskExecutionService.end` :457-460 + `updatePendingApprovalStatus` | Refetches workflow + runs approval count on **every** task completion regardless of type; the refetched object's mutation is never saved. Gate on task type. | ✅ **FIXED (Track 2)** — refetch + recompute now gated to `approval`/`manual` completions (the only types that resolve an Action); recompute uses `existsByWorkflowRunRefAndStatus` instead of `countBy(...)>0`. |
| D6 | `TaskRunService.findClaimable` / `WorkflowRunService.findClaimableFor*` | Hydrate full docs when only `_id` is used → project `_id`. High-frequency (agent poll). | low |
| D7 | `TaskRunService.excludePausedRuns` :99-113 | Extra query per `findClaimable`/`findReapable` — × N idle-polling agents/sec. Short-TTL cache or denormalize. | ✅ **FIXED (Track 2)** — resolved by design, not cache/denorm: `excludePausedRuns` **deleted** from both queries. Pause is now the single admission gate at `TaskExecutionService.queue`; in-flight/already-`ready` tasks run to completion. No extra query, no G2 data-model change. See `queue-design.md` §1.3. |
| D8 | `tryPause`/`tryResume` (`WorkflowRunService` :289-312), `tryStartWaitingResume` (`TaskRunService` :341) | `findAndModify` discards the pre-image → use `updateFirst` + `getModifiedCount()`. | low |
| D9 | `TaskExecutionService.runWorkflow` :787 | Double `save()` (inner save + the shared `endTask` branch). | low |
| D10 | `TaskExecutionService.executeNextStep` :1040 | Pointless `findById` existence-guard per outgoing edge on an already-live object. | low |
| D11 | `AgentService` long-poll | N per-candidate `findAndModify` claims per poll + blocking `Thread.sleep` on a platform thread (no virtual threads). | **Q-005 measurement** — this is the "new claim poller" CLAUDE.md flags; defer to the Q-005 step. |

## E. Magic strings — ❌ WON'T FIX (per maintainer ruling 2026-08-13)

- ~82 raw `Criteria.where("…")` field literals in service-engine (`"claim.by"` ×9,
  `"retry.after"` ×9, etc.), plus annotation keys (`boomerang.io/*`) and task-param names.
  The original recommendation here — **per-entity `Fields` constants** — is **void**: the
  maintainer ruled that entities stay Lombok-only, with **no nested `Fields` classes or
  field-name String constants** (raw string literals are the house style, consistent with the
  loader). A field-name constants holder is introduced only by explicit maintainer exception.
  The `LoaderMigrationTest` string-pinning remains the loader-side drift guard. See the
  `spring-module` skill (Entity Pattern) for the rule.

## F. Structure (MERGE — defer; the merge reshapes these)

| # | Item | Disposition |
|---|---|---|
| F1 | `TaskExecutionService` ~1140-line god-class → extract `TaskLockService`/`ActionTaskService`/scheduled-workflow handler. Safety-critical CAS/fencing core. | MERGE (or incrementally-behind-tests if pulled forward — maintainer call). |
| F2 | Both Run services mix CRUD+query+CAS+logic → physical CAS extraction. Add a section-banner comment now as a clean cut line. | MERGE |
| F3 | `@Autowired`-field vs constructor injection inconsistent across the execution core; self-proxy `@Async` pattern under-commented. | FIX (banner/comment) / MERGE (full migration). |
| F4 | Entity `@Indexed`/`@CompoundIndex` have **drifted** from the loader AND are inert in service-engine (`auto-index-creation` unset→false); service-flow sets it `true`. First post-merge service-flow query of these entities spins up a second, differently-shaped index set. Strip the annotations, pin `auto-index-creation=false` everywhere, loader = sole authority (DD-07). | MERGE (G2 data-model). |

## G. Config / naming (FIX / E5 / defer)

- `TIMEOUT_GRACE_MILLIS`, `PAGE_SIZE` duplicated across sibling classes (C4 covers grace). — FIX
- No `@ConfigurationProperties` for watcher/outbox intervals (baked `@Value` defaults). Consolidate. — **E5** (ScheduleWatcher config is the natural moment to introduce `flow.watcher`/`flow.outbox`/`flow.schedule` config classes).
- Three similar-named retry concepts (`WorkflowRunEntity.retries` = budget, `.retryCount` = attempts, `RunRetry.count` = per-task backoff). Consider `retries`→`retryBudget` at a touch-point. — defer.
- `RetryClass`/`TimeoutCause`/`failureClass` types do not exist yet (Phase-3-gated) — on the G2 checklist when the typed failure classes land, not ad hoc.

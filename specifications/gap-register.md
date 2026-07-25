# v5 Gap Register & Phase 3 Backlog — Q-220 / Q-226 (Phase 2B closeout, 2026-07-22)

**Status:** 🟡 PROPOSED (answers Q-220 + Q-226; becomes the Phase 3 work order on
confirmation). Consolidates every fix list across: idempotency-audit, timeout-audit,
reconciler-analysis, consolidation-proposal, v5-spec-review, and the answered Q-register.
Cross-checked line-by-line — items the audits' own ranked lists omitted are carried
explicitly (B11, B12, B13, B18, C12).

---

## 1. Final Lock / Ownership Inventory (Q-220)

Honest column: per F2, **none of the alturkovic sites are actually mutually exclusive**
(deterministic token — same-key acquirer succeeds; release deletes anyone's lock).
"Protected by lock" = "mostly works single-instance".

| # | Mechanism | Protects | Actual safety today | v5 replacement | Safety argument |
|---|---|---|---|---|---|
| 1 | Lock behind `acquirelock`/`releaselock` task types | User-declared cross-workflow mutual exclusion (product feature) | Broken (F2); unbounded wait | Atomic TTL-lease doc (`task_locks`), bounded wait | Single-doc atomicity; TTL bounds crash-held leases |
| 2 | Lock in `executeWorkflowAsync` | One start transition | Lock doesn't lock → dup first-tasks, two timeout jobs | CAS `pending/queued→running`, winner-only | Pre-image CAS; exactly one winner |
| 3 | Lock in `timeoutWorkflow` | One timeout+auto-retry | Guard-then-lock race → duplicate retry runs | CAS returning old doc; winner-only retry; `$inc` count | Side-effect keyed to CAS winner |
| 4 | Lock in `TaskExecutionService.start` | One running transition | Execute dispatched after release → double execute | Admission CAS at execute entry | Guard moves to the side-effect |
| 5 | Lock in `TaskExecutionService.end` (graph-advance funnel) | Terminal write + advance | **UNSAFE even if lock worked** (no re-read inside) | `findAndModify(running→completed)` pre-image; winner-only advance = reconcile | Duplicate advance converges instead of duplicating |
| 6 | Lock around `finishWorkflow` | One completion + CloudEvent | Double event under race | CAS + transition-keyed outbox | Exactly-once per transition |
| 7 | Lock around approval-flag save | `isAwaitingApproval` | Full-doc replace; one save outside any lock | Single-field `$set` (level-triggered recompute) | Atomic single field |
| 8 | Lock around `saveWorkflowParam` | Result append | Double-appends even sequentially | Keyed merge by name (shape: CF-6) | Idempotent under re-call |
| 9 | Agent queue find-then-bulk-update | Dispatch ownership | **Not a claim** — loser still dispatches; terminal runs redelivered every poll | Per-doc `findAndModify` claim + `claimedBy/claimedAt/leaseExpiresAt/claimEpoch` (Q-129) | Atomic single-doc claim; dispatch = claim results; ownership enables recovery + fencing |
| 10 | In-memory task-timeout futures | Task timeout | Lost on crash — recovery = ∞ | Deleted → watcher sweep, per-class budgets + fencing | Durable, level-triggered, instance-agnostic |
| 11 | JobRunr workflow-timeout job (`jr_`) | Workflow timeout | Sound but a second mechanism; ±5s inconsistency; no submit validation | Redundant once sweep is the single path; deleted with JobRunr (Q-227) | One durable mechanism; validated grace |
| 12 | JobRunr storage coordination (`jr_`+`sch_`) | Schedule dedup, job retry | Works; unexamined at N; two storages; retry-semantics overlap | Claim-based due-work docs swept by the watcher; schedules re-register from `workflow_schedules` | One coordination model to test |
| 13 | JGraphT relationship singleton | The entire authz decision surface | **Authz bug under N** (per-replica staleness); O(N) rebuild per write | Direct-query anchored walk (Q-132); memoisation; `$graphLookup` escalation | Correct by construction — reads current DB; CHEER-proven |
| 14 | Thread pools + 100k in-memory queues as implicit serialization | Ordering/backpressure (one JVM only) | Invisible unbounded state, lost on crash; head-of-line blocking; sleep pins threads | Bounded Mongo-resident claims; per-type caps + kill switch; VT; durable sleep | Restart = claim churn, not incident |
| 15 | (Absence) zero `@Version`; `save()` = last-writer-wins (F1) | Nothing | Concurrent writers lose whole documents | CAS transitions + field-scoped writes; `@Version` tripwire only (CF-2) | ARCHIE shipped invariant; sweep repairs the rest |

**Residual after v5:** only #1 (redesigned as TTL-lease docs). Library, futures, singleton,
and (per Q-227) JobRunr all deleted. `locks` collection retained until the POST-MERGE drop.

---

## 2. Master Gap List (Q-226)

Tags: **BEFORE-MERGE** ≈ migration steps 1–8 · **WITH-MERGE** ≈ steps 9–11 ·
**POST-MERGE** ≈ step 12 + major-boundary breaks. Severity: C/H/M/L.

### A. Security / Authorization
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| A1 | SecurityInterceptor returns true on permission mismatch; tokens never validated by prod traffic | C | Shadow-logging → token audit/backfill → gated flag (default off) | BEFORE (2–3) |
| A2 | Enforcement default-on flip — riskiest flip in v5 | C | Flip at the major, after shadow data | POST |
| A3 | Agent endpoints unauthenticated | C | Static bearer token filter, all modes; ships with protocol v2 | BEFORE (8); default-on POST |
| A4 | `/internal/token` debug hole | H | Lock now; `InternalController` dissolves at merge | BEFORE + WITH |
| A5 | `flow.auth.enabled` vs `flow.authorization.enabled` | M | Unify; default derives from `flow.mode` | WITH (earlier OK) |
| A6 | `Permission(String)` parses but never assigns | M | Fix in A1 work (working tree shows local edits — verify in-flight) | BEFORE |
| A7 | Engine `permitAll()`; EY protection implicit | H | Explicit network-only + optional token (A3 filter); mode contract | BEFORE + WITH |
| A8 | `checkPermissions` result ignored by caller | H | Enforce with relationship rewrite | BEFORE (7) |
| A9 | TASK-granted-to-all ROOT special case | M | Retire with rewrite | BEFORE (7) |

### B. Claiming / Idempotency (the Phase 3 gate — alturkovic deletion blocked on B1–B10)
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| B1 | `end` — no re-read in lock; both racers advance graph | C | Completion CAS pre-image; winner-only advance = reconcile | BEFORE (5) |
| B2 | Agent claim loser still dispatches; terminal runs redelivered. **E0 addendum (2026-07-23, demonstrated by test):** the bulk `updatePhaseAndAgentRef` also claims EVERY matching ready TaskRun of the agent's types — not just the returned page — an over-claim beyond the find/update race | C | Per-doc findAndModify claim + ownership fields (fixes the over-claim too) | BEFORE (5) |
| B3 | Task admission unguarded (queue/execute) | C | Admission + execution-entry CAS; re-read by id | BEFORE (5) |
| B4 | Workflow start race | H | Start CAS winner-only | BEFORE (5) |
| B5 | Duplicate retry runs (timeout race); stale retry-count | H | CAS + winner-only retry + `$inc`; unique (retryOf, attempt) | BEFORE (5) |
| B6 | createActionTask / runWorkflow / runScheduledWorkflow duplicate side effects | H | Idempotency keys + unique indexes | BEFORE (4–5) |
| B7 | Append duplication (params, event results, workspaces) | M | Keyed merges / event-id dedup | BEFORE (5) |
| B8 | No unique `task_runs(workflowRunRef,name)` | H | Partial-live unique index (+ mapIndex); legacy check | BEFORE (4) |
| B9 | CloudEvent aspects double/phantom-fire | H | Transition-keyed outbox (CF-1: dedup outbox BEFORE, event-fed egress WITH) | BEFORE (5) + WITH |
| B10 | cancel/timeout stomp succeeded runs | H | CAS preconditions | BEFORE (5) |
| B11 | eventwait re-arm race → wedged run (**omitted from audit's own ranking**) | H | Arming CAS | BEFORE (5) |
| B12 | Agent re-register duplicates (omitted from ranking) | M | Upsert unique (name,host) | BEFORE (4) |
| B13 | No request idempotency key on submission (omitted; prerequisite for async ingress) | M→H | Key + unique index; enforced at submit | BEFORE (4) |
| B14 | Stale entities across @Async boundaries | H | Pass ids; re-read at entry (fixes rule 1 everywhere) | BEFORE (5, first) |
| B15 | Zero @Version; save = last-writer-wins (F1) | H | Field-scoped writes; @Version tripwire only (CF-2) | BEFORE (5) |
| B16 | No fencing at handler entry | C | claimedBy/epoch checked at start/end; stale endTaskRun rejected | BEFORE (5/8) |
| B17 | F2 broken lock | C | Do NOT fix — delete library after B1–B10; `locks` kept for rollback | BEFORE (5); drop POST |
| B18 | Duplicate audit rows | L | Falls out of CAS-everywhere | free |

### C. Queueing / Scheduling / Recovery
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| C1 | No recovery sweep; task crash recovery = ∞ | C | Watcher sweep, per-class timeouts, any instance | BEFORE (5) |
| C2 | Claim starvation-unsafe (no FIFO/backoff/exclusion) | H | FIFO compound index; retryAfter + pause in query | BEFORE (4–5) |
| C3 | One-size retry | H | Three failure classes | BEFORE (5) |
| C4 | No per-type caps/kill switch | H | Per-type pollers + caps + switch; semaphores where apt (L-07) | BEFORE (5) |
| C5 | JobRunr at N unexamined; two storages | M | Claim-based due-work; re-register from entities; drain; late drop | BEFORE (6); drop POST |
| C6 | Pause doesn't exist (committed feature) | H | pauseRequestedAt + three chokepoints; resume = clear + reconcile | BEFORE (5) |
| C7 | findFirst non-deterministic under supersede (Q-115) | H | Supersede fields + live-partial index; find-live-by-name | BEFORE (5) |
| C8 | Missing dep = satisfied (`canExecuteTask`) | H | Invert — **before any supersede code** | BEFORE (5, first) |
| C9 | O(N²) graph evaluation reads | M | Pure-function reconciler, one batched fetch | BEFORE (5) |
| C10 | Event duplicate-processing at N | H | Idempotency not partitioning: outbox + dedup + B13 | BEFORE + WITH |
| C11 | N concurrent sweepers | M | No leader election; idempotent level-triggered sweeps | BEFORE (5) |
| C12 | sleep = Thread.sleep (zombie completions) | M | Durable scheduled resume, capped | BEFORE (5) |
| C13 | No dynamic fan-out mechanism (AI tasks) | M | Placeholder+expand, capped, (name,mapIndex) | BEFORE (5) design |

### D. Timeouts / Transport
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| D1 | 3 of 4 RestTemplates: no read timeout; infinite pool-lease; dead MAX_VALUE constants | C | Connect 5–10s / read 30–60s; lease ~10s; delete constants | BEFORE (early, config-only) |
| D2 | Engine reaps healthy Tekton work (T vs T+10 grace) | H | Grace composes downward; validated at submit | BEFORE (5) |
| D3 | DAGUtility discards per-task timeouts (bug) | H | ✅ Fixed (E2, 2026-07-23) — **ceiling semantics per maintainer** (annotation = platform bound, task value applies when under it; mirrors the workflow-level quota pattern). The fixed case: annotation absent → explicit task timeout now applies instead of being discarded | BEFORE (early) |
| D4 | Log streams die at 30s on flow/engine | M | Streaming client; async timeouts ≥600s | BEFORE (early) |
| D5 | Long-poll blocks Tomcat threads; implicit invariant pair | M | DeferredResult; named pair (hold = read/2) | BEFORE (8) |
| D6 | Mongo: no timeouts — sweeper can hang | M | socketTimeoutMS + maxTimeMS | BEFORE (5) |
| D7 | Engine-direct submits may lack any timeout | M | Default/clamp at submit; T ≥ critical path | BEFORE (5) |
| D8 | acquirelock unbounded wait | M | Bounded attempts in TTL-lease redesign | BEFORE (5) |
| D9 | Stale guards can reap next attempt | H | Attempt/fencing epoch on sweep records | BEFORE (5) |
| D10 | ±5s boundary inconsistency | L | Unify in sweep | BEFORE (5) |

### E. Data Lifecycle / Deletion / Retention
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| E1 | **Workflow delete cascades with no in-flight guard — data loss** | C | Stopgap: refuse while unfinalised runs exist. End state (ruled): tombstone + watcher cancel + retention sweep + orphan backstop (CF-4) | BEFORE — stopgap NOW; full with (5) |
| E2 | Task-template delete unguarded; latest-resolution hazard | H | Guard referenced versions; supersede copies spec, never re-resolves | BEFORE (stopgap + 5) |
| E3 | Revision retention unenforced | H | Immutability = law; retention sweep | BEFORE (5) |
| E4 | Attempt-history pruning undefined | L | TTL vs archive — Phase 3 spec | design |
| E5 | Result/param payload bloat | M | Claim-check threshold; index-covered queries | BEFORE (5) |
| E6 | Dependencies duplicated (revision vs TaskRun copy) | L | Revision authoritative; copy = cache | BEFORE (5) |

### F. Composition / Eventing
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| F1 | runworkflow: no parentRef/cascade/depth; succeeds on submission | H | Design parentRef + cascade (incl. pause) + depth + wait-for-child | design BEFORE; impl BEFORE/WITH |
| F2 | Engine→flow callbacks (linkage, schedules) | H | Invert to events; engine mode: fail-fast (I2) | WITH (10) |
| F3 | Dead submit callback | L | Delete | WITH (10) |
| F4 | Ingress needs topic correlation (I5) | M | Topic + key design settled before embedders | design BEFORE; impl WITH |
| F5 | Configurable join (unruled — do not build speculatively) | L | Data-model addition if ruled in | POST (if ruled) |

### G. Relationship Layer
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| G1 | ✅ **FIXED (E6, 2026-07-23):** JGraphT singleton deleted; anchored direct-query walk (3–7 indexed queries/decision, zero rebuilds); 24 signatures preserved; two-instance parity proven by test; flow 17/0/0 | C | done | ✅ |
| G2 | ✅ Retired — every resolve type-scoped; cross-type slug-collision regression test | H | done | ✅ |
| G3 | ✅ Request-scoped memo (no-op on scheduler threads; invalidated on mutations) | M | done | ✅ |
| G4 | ✅ All mutation sites audited domain-write-first; ordering rule in the service header | M | done | ✅ |
| G5 | Hot data traverses graph | M | workspaceId-direct rubric | BEFORE (7) |
| G6 | Edge role data unverified | L | Pre-cutover check | BEFORE (7 gate) |

### H. Merge Mechanics / API / Product
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| H1 | Model-extends-entity (TaskRun etc.) | H | Flatten with AgentProtocol split (J7/I6) | BEFORE (8) |
| H2 | phase in public API (frontend: 2 read sites) | H | Frontend fix ahead; removal at major | BEFORE + POST |
| H3 | Agent protocol v1 retirement | M | Dual-serve v1+v2; retire at major | BEFORE (8) + POST |
| H4 | lib-common disposition + dead code | H | Execute the 72-class table | WITH (9) |
| H5 | Six dependency cycles C1–C6 | H | Directed fixes | WITH (9) |
| H6 | No mode gating / boot tests | H | Controllers into modules; @ConditionalOnFlowMode; per-mode boot CI | WITH (9) |
| H7 | Workspace seam | H | RunScopeResolver at submit | WITH (9–10) |
| H8 | Ruled restructurings J2–J6 | M | Per rulings | WITH (9) |
| H9 | **F1 load test — the merge gate** | C | Before cutover; abort on failure | WITH (11 gate) |
| H10 | Helm chart-major + alias images | H | Consolidated chart; both aliases | WITH + POST |
| H11 | Collection drops (locks, jr_, sch_) | M | Separate later loader release | POST |
| H12 | Unified versioning + CI rework | M | DD-03 execution | POST (12) |
| H13 | Team→Workspace rename | M | DD-01 at major with frontend re-baseline | POST |
| H14 | Frontend fold-in | M | DD-04 after merged image | POST |
| H15 | **Standing constraint**: no PAUSED/SUPERSEDED statuses; superseded excluded from default responses | guard | Enforce in review | BEFORE (standing) |
| H16 | Standalone enablers (compose, Docker agent, no-op relationships) | M | Per J8/I3 | POST (Phase 4) |
| H18 | **Live bug (found by Q-403, 2026-07-23):** the `workflowRun`-scoped workspace lifecycle filters on the typo `"workfowRun"` (`WorkflowService.java:45,96`, `WorkspaceService.java:51` in service-agent) — per-run workspace PVCs are never provisioned or cleaned; feature silently inert in production. Also noted: `TaskWatcher` calls `System.exit(1)` on watch error (kills the whole dispatcher for one task's watch failure) | M | Decide: fix the typo (activates a long-dormant feature — behaviour change!) or formally retire per-run workspaces with the Phase 4 storage design; replace the exit(1) with per-task failure. Ruling belongs to E7/Phase 4 | E7/Phase 4 |
| H17 | ✅ **FIXED (2026-07-23):** TektonConverter params CCE — `convertValue` + TypeReference; quarantined tests re-enabled (4/4 green). Residual notes: (a) adjacent `(Integer)` cast on `boomerang.io/version` would CCE on string YAML values (unexercised); (b) legacy v3 `key:`-style annotation params import with default metadata (lossless v3-YAML import would be a product decision) | M | done | ✅ |

### I. Documentation
| ID | Gap | Sev | Fix | Tag |
|---|---|---|---|---|
| I1 | Community docs v3-era; ADR002 broken; RelationshipGraph.md recommends rejected design | L | Docs deliverable per phase gate | ongoing |
| I2 | CLAUDE.md drift (largely fixed 2026-07-22; re-check at each phase) | L | Keep current | ongoing |
| I3 | UI contract (full-graph-from-TaskRuns) undocumented | L | Record as invariant | BEFORE (docs) |

### Cross-document conflicts (resolved or explicitly open)
- **CF-1** Outbox timing: dedup-keyed outbox BEFORE-MERGE (satisfies gating item 9);
  ApplicationEvent-fed egress + aspect retirement WITH-MERGE.
- **CF-2** `@Version`: CAS is the safety mechanism; `@Version` tripwire-only on residual
  multi-field writes (note: multi-instance-model W4 goes further — CAS-only, no @Version
  at all; **maintainer to pick**).
- **CF-3** Timeout substrate: sweep single-path ruled; step 5 runs beside JobRunr, step 6
  retires it; Q-227 closes when due-work is validated at N.
- **CF-4** Delete fix: guard = BEFORE-MERGE stopgap only; tombstone+watcher = end state.
- **CF-5** Engine-mode surface: J1 (one v2 surface) is authoritative over proposal §5(c).
- **CF-6** saveWorkflowParam shape: decide at implementation; acceptance criterion fixed.
- **CF-7** ARCHIE facts corrected in master spec (9 anchors etc.).
- **CF-8** Deliberately open: pause-exclusion benchmark, configurable join (F5), I5 detail
  design, attempt-history retention (E4), Q-227 final closure.

---

## 3. Phase 3 Work-Order Skeleton

Gating: **E0 blocks structural work; E4 is the centre of gravity; merge train (E8–E10)
starts only when E4–E7 green; POST-MERGE only after the E10/F1 gate.**

### Standing phase gates (maintainer-mandated, 2026-07-23)

**Every epic STOPS at its start** and produces two artifacts for maintainer review
BEFORE implementation proceeds:

- **G1 — Core-execution touch analysis.** Does this phase modify `DAGUtility` or
  `TaskExecutionService` (or their post-refactor successors — the DAG walk and the task
  lifecycle core)? If yes: enumerate the exact methods and execution semantics affected,
  and the subset of E0's 12 test scenarios that covers them must be green before the
  phase merges. These two files are the blast-radius centre of the entire engine — no
  silent touches.
- **G2 — Data-model proposal.** Every schema change the phase introduces — new fields
  (with absent-value semantics), indexes, collections, and value migrations — presented
  as a short discussion document (shape, defaults, loader changeset, rollback) and
  discussed with the maintainer before any of it is implemented.

**Prospective gate table** (pre-filled from the Phase 2B designs; each phase-start
review confirms or corrects its row — the row is the *starting point* of the G1/G2
discussion, not its replacement):

| Epic | G1: DAGUtility | G1: TaskExecutionService | G2: expected data-model changes |
|---|---|---|---|
| E0 baseline/tests | ⚠️ incidental only (compile-level fixes from dependency upgrades — no semantic change permitted) | ⚠️ incidental only (same rule) | None |
| E1 security shadow | No | No | No schema; one **data content** change: tokens-collection permission backfill (step 3) — G2 discussion covers the repair rules |
| E2 hazard stopgaps | **YES — surgical**: the timeout-merge bugfix (`DAGUtility:187-199`, D3) | No (delete guards land in `WorkflowService`) | None |
| E3 schema/indexes | No (loader only) | No (loader only) | **THE major G2 discussion**: claim block (`claimedBy/claimedAt/leaseExpiresAt/claimEpoch`), `retryAfter/retryCount/retryClass`, `timeoutAt/waitUntil`, `attempt/supersededAt/By`, `pauseRequestedAt`; unique indexes (generation, actions, agents, idempotency keys); the claim-page + sweep indexes; the one bounded generation-dedupe backfill |
| E4 execution rebuild | **YES — major**: the pure-function reconciler replaces `canRunTask`/`updateTaskInGraph`/`allDependenciesValid` walk; `createTaskList` becomes the reconcile create-missing half | **YES — major**: admission/execution/completion CAS gates rewrite `queue`/`start`/`execute`/`end`; sleep/eventwait/action/param paths all reworked per the audit | `transitionSeq` + `lastOutboxedSeq` on runs; `events_outbox` + `events_ingress` collections; `task_locks` collection; `tombstonedAt` on workflows; any field shape corrections discovered from E3 |
| E5 scheduling | No | Light — `runScheduledWorkflow` path if the deferred substrate decision lands here | **Deferred-decision G2**: `nextFireAt/lastFiredAt/firing/misfirePolicy` on schedules + fire-dedupe fields on runs (only if due-work wins the deferred Q-227 call — this G2 IS that decision point) |
| E6 relationship rewrite | No | No | None by design (schema unchanged is the point); G2 confirms index sufficiency + the role-data pre-cutover check |
| E7 agent contract v2 | No | ⚠️ light: fencing validation at `start`/`end` entry (if not already landed in E4) | Wire-only (`failureClass` on TaskRunEndRequest, v2 handshake fields) — G2 confirms nothing new persists beyond E3/E4 fields |
| E8 Modulith boundaries | ⚠️ relocation only (files move modules; zero semantic change permitted — G1 review verifies the diff is move-only) | ⚠️ relocation only (same rule) | None |
| E9 callback inversion | No | **YES — targeted**: `runWorkflow`/`runScheduledWorkflow` switch from HTTP callbacks to published events | `parentRef` on child WorkflowRuns + `createdByTaskRunRef` promotion (the F1 composition design lands here) — G2 discussion |
| E10 merge | No | No | None (config/packaging only) |
| E11 post-merge | No | No | DD-01 value migrations (`RelationshipType`/`AuthScope` team→workspace changeset); the `locks`/`jr_*`/`sch_*` drop changesets — each its own G2 |

- **E0 — Phase 0 baseline + Testcontainers safety net** (blocks all): version matrix,
  PR order per §3, VT with load comparison. **The 12 scenarios:** (1) baseline green path;
  (2) claim race — one winner; (3) duplicate-dispatch tolerance; (4) graph-advance race —
  join queued once; (5) crash-mid-execution — sweep recovers ≤ interval; (6) fencing
  rejection — stale epoch rejected, guard N can't reap N+1; (7) per-class timeout reaping
  — healthy in-budget work NOT reaped, durable sleep, approval excluded; (8) pause
  exclusion + resume reconcile + approval interplay; (9) delete tombstone → watcher cancel
  → retention prune → orphan backstop; (10) event dedup — single append/end, exactly-once
  outbox, idempotent submission; (11) terminal-status protection; (12) relationship parity
  across 2 instances.
- **E1 — Security observability → gated enforcement** (steps 2–3): A1, A6, A4-lock.
- **E2 — Hazard stopgaps** (immediate, parallel): D1, D3, D4, D6, E1/E2-data delete
  guards.
- **E3 — Additive schema/indexes via loader** (step 4): claim/pause/supersede fields
  absent-as-eligible; unique indexes B8/B6/B12/B13; FIFO claim index.
- **E4 — Execution-model rebuild** (step 5, the centre): ordered internally B14 → C8 →
  B1 → B2/B16 → B3/B4 → C1+timeout classes → B5/B6/B7/B10/B11/B15 → B9 outbox → C6
  pause + C2/C3/C4 queue → C7 supersede + C13 fan-out + E5 payload → lock-task TTL-lease
  + **delete alturkovic** → E1-data tombstone/watcher/retention. Constraint: H15.
- **E5 — JobRunr retirement** (step 6): due-work docs; drain; Q-221/Q-227 close.
- **E6 — Relationship rewrite** (step 7, parallelisable): G1–G6, A8, A9.
- **E7 — Agent contract split + protocol v2** (step 8): H1, I6, A3, D5, H2-frontend.
- **E8 — Modulith boundaries** (step 9): nine modules, H4–H8, A5; engine module bootable;
  timeboxed to merge within 1–2 releases.
- **E9 — Callback inversion** (step 10): F2/F3/F4, A4-dissolve, C10 bindings, B9 stage 2.
- **E10 — Merge deployables** (step 11): aliases, chart-major, **F1 gate**.
- **E11 — POST-MERGE program**: H12 versioning → A2/A3-default-on + H3/H2 major breaks →
  H11 drops → H13 rename + H14 frontend → alias windows expire → H16 Phase 4 track + F5
  if ruled → I1 docs close.

**Cross-check:** all audit ranked+cross-cutting items, all timeout constraints, all
reconciler §4/§5 points, all proposal hard breaks, all review security/relationship
findings are mapped — omissions from source rankings carried explicitly.

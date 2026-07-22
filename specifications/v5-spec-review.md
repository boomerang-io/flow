# v5 Enhancement Spec — Deep Review (2026-07-22)

**Subject:** `specifications/v5-enhancemnet.md`
**Evidence base:** five parallel research streams — (1) service-engine execution internals,
(2) service-flow call graph / relationships / security, (3) ARCHIE shipped WorkflowWatcher
design (`/Users/tysonlawrie/Workspaces/tlawrie/asdr`), (4) CHEER relationship + queue designs
(`/Users/tysonlawrie/Workspaces/walkaboutdev/cheer.dev`), (5) public architecture docs
(`github.com/boomerang-io/community/architecture/flow`).
**Framing constraint honoured:** Flow is more complex than either reference — the review
evaluates *patterns* for adoption, not implementations for transplant.

---

## Verdict

The spec's strategic direction is **sound and now strongly evidenced**: every ARCHIE-pattern
gap it targets is real in the codebase (no atomic claiming, no recovery sweep, no `@Version`,
no pause, lock-guarded transitions), and the reference codebases confirm the target patterns
work in production. However the spec contains **stale premises** (Quartz, `@Version`
assumptions about ARCHIE), **understates two critical current-state problems** (the
relationship graph singleton; authorization soft-fail), and is **missing several questions**
the evidence now forces. Detailed findings below; proposed amendments in §5.

---

## 1. Premises CONFIRMED (spec is right, now with evidence)

| Spec premise | Evidence |
| --- | --- |
| `alturkovic/distributed-lock` is load-bearing and a deletion candidate (L-01, Q-120) | Engine-only: `distributed-lock-mongo:1.4.3` in `service-engine/pom.xml` L77-80; `@EnableMongoDistributedLock` on `Application`; 8 usage sites in `LockManager`/`WorkflowExecutionService`/`TaskExecutionService` (incl. the user-facing `acquirelock`/`releaselock` task types — deletion must preserve those semantics). `LockManager` is itself unsafe: exists-check-then-acquire is non-atomic, 100 retry attempts @2s, in-code `TODO - perform testing of the locks`. |
| No atomic claiming; agent dispatch races (L-01, L-12, Q-128) | `AgentService` queue endpoints: `find` then separate bulk `updatePhaseAndAgentRef` — two agents can both receive the same runs; in-code TODO "figure out how to have multiple agents, probably a centralised lock". **Zero `findAndModify` in the codebase** outside `CosmosDBMongoLock`. Worker identity = bare `agentRef` (no claimedAt/lease/epoch). Agent endpoints have no authentication (TODO in code). |
| No recovery sweep; crash recovery is a gap (L-02, Q-121) | **No `@Scheduled` recovery poller exists.** Task timeout is an in-memory `CompletableFuture.delayedExecutor` (in-code TODO: "migrate... so that it works across horizontal scaling") — lost on crash. `sleep` tasks block a thread (`Thread.sleep`). Workflow timeout IS durable (JobRunr job at start, idempotent guard, any instance can run it) — see §3.4. |
| No starvation safety / typed caps / backoff (L-03/04/06, Q-122/123/125) | Claim queries have no sort (no FIFO), no backoff fields on runs, no per-type caps (only global thread pools: 200/100 threads, 100k queues). Typed routing exists coarsely (agent registers `taskTypes`; query filters `typeIn`). Retry is clone-and-requeue from workflow timeout only. |
| Flow↔engine is synchronous HTTP; async decoupling needed (Q-201) | One `EngineClient` (~35 URL props), all blocking RestTemplate, no retry/circuit-breaker, all errors → generic 500. **No `WorkflowRunRequested` event exists.** CloudEvents = optional outbound status sink, `false` by default, fire-and-forget, dead-letter save commented out. |
| "Flow is harder" — data-defined DAG generalisation (§2) | Confirmed: 17 `TaskType` values; regex-matched decision branches; AND-join with an explicit code comment where OR would be gated; skip cascades node-by-node via path-reachability; params in 5 flattened layers with live DB reads for task-result references. |
| lib-common needs a split (Q-202) | Engine-only entities (7), flow-only entities (Schedule/Action), engine keeps its own `ActionRepository` copy, `util/` duplicated between lib-common and service-engine. |
| ARCHIE patterns are production-proven and portable *as patterns* | ASDR study: page-then-CAS claim (backoff + paused exclusion in the query, `retryAfter` re-guard in the claim), 60s level-triggered watcher with no leader election (idempotency-tolerated duplicate sweeps), pause as `pauseRequestedAt` with three-chokepoint discipline, atomic `updateMulti` supersede, ghost-thread completion guard. CHEER independently re-implements the same stack **with ownership metadata** (`agentRef` + `agentClaimTime` stamped in the claim) — i.e. the L-12 divergence the spec calls for is already validated in shipped code. |

---

## 2. Premises STALE or WRONG (spec must be corrected)

### 2.1 Quartz is gone — JobRunr 7.4.1 is the scheduler
Flow schedules (`ScheduleService` → `jobScheduler.scheduleRecurrently/schedule`, storage
prefix `sch_`) and engine workflow-timeout jobs (prefix `jr_`) both run on JobRunr with
Mongo storage. Quartz survives only as a transitive jar via `lib-scheduling:3.0.3` and a
dead `org.quartz.*` import (`SchedulerException` in two signatures). **Q-221 must be
rewritten**: not "Quartz clustering" but "JobRunr under N instances" — storage-based
coordination semantics, recurring-job dedup, the two-separate-storages question (§4.3),
and JobRunr's own `default-number-of-retries=3` interaction with run-level retries.
CLAUDE.md's Technology Stack table ("Quartz + MongoDB job store") is also stale.

### 2.2 ARCHIE lesson details need correction (Phase 1 annex + §4.2)
- **Restart anchors: 9 shipped `WorkflowAction` variants**, not 5 (nor 8). The "5" counts
  only re-run anchors vs checkpoint-continues. The meta-lesson (don't over-abstract) stands.
- **L-12 sharpened**: ARCHIE *does* write `claimedBy` (per-boot UUID) on every claim — but
  **never reads it**. Recovery is purely time-based. Correct statement: *identity is recorded
  for diagnostics but carries no semantics*. Flow's task is to add semantics (lease expiry,
  renewal, fencing epoch), not to add the field.
- **`@Version` is NOT how ARCHIE serialises contended transitions.** Conflicts from plain
  `save()` are logged-and-swallowed; the watcher re-drives. The genuinely contended fan-in
  uses an explicit **status-CAS `findAndModify`** (`tryTransition(expected → target)`), which
  bypasses `@Version` entirely. The shipped invariant is: **CAS for contended transitions +
  level-triggered sweep as recovery; `@Version` as tripwire only.** Spec sections that say
  "workflow-level transition safety from `@Version` optimistic writes" (and Q-223's framing)
  should be reworded to this.
- **Three failure classes, not two** (L-04): generic backoff (3s base, cap 2), rate-limit
  (20s base, cap 5), and **deterministic-terminal bypass** (no retries — e.g. validation
  errors). The third class must be in the v5 retry design.
- Additional shipped lessons worth capturing: count-don't-load sweep guards (completed runs
  carry multi-hundred-KB payloads); rate-limited user-visible recovery events; backoff stored
  as `retryAfter` so it's a claim-eligibility property, not a timer.

### 2.3 The "status-only externally" invariant is currently violated
`lib-common/model/WorkflowRun` exposes `phase` (in `@JsonPropertyOrder`); `TaskRun` **extends
`TaskRunEntity`** so phase serialises too; these are the response models of the V1 controllers
— and **the agent protocol depends on receiving phase** (`QueueService` reads it). The
invariant is aspirational, not current state. v5 needs an explicit task: **separate the agent
wire contract from the public API models** before the invariant can be enforced.

### 2.4 `@Version` coverage is zero (Q-224 answered)
No entity in any module has `@Version`. Hot unversioned concurrent writers:
`WorkflowRunEntity` (approval-flag save outside any lock, `saveWorkflowParam`, timeout job,
agent bulk update) and `TaskRunEntity` (queue/start/end paths + agent claim update).

### 2.5 Distributed lock is engine-only
Phase 2B's lock inventory can skip flow/agent modules: flow has **no** concurrency control on
its writes at all (which is its own finding — e.g. relationship rebuilds, below).

---

## 3. NEW FINDINGS the spec must absorb

### 3.1 The relationship layer is the design CHEER already abandoned (Q-130/131 largely answered)
Flow's `RelationshipService` + `RelationshipGraph` **is** RelationshipGraph.md "Model 4":
a process-wide JGraphT singleton loaded from full `rel_nodes`/`rel_edges` collection scans,
**rebuilt with two `findAll()`s on every mutation**, with `BFSShortestPath` over a linear
scan of the vertex set per access check (O(V·E) worst case). Under N instances the cache is
**incoherent — an authz-correctness bug**, not just a perf issue. CHEER shipped exactly this
design and replaced it (2026-07-21, with a written fitness analysis) with **direct indexed
Mongo queries**: anchored ≤4-level BFS walk, 3–9 indexed queries per decision, **no cache at
all**, ~165 call sites preserved by keeping method signatures.

Decisive facts for the verdict:
- Flow's schema is already CHEER's schema (same collections `rel_nodes`/`rel_edges`, same
  `type:ref` node ids, same index shapes, role-on-edge `data`). **Q-134 (migration): little
  to no data migration — the rewrite is resolution-strategy only.**
- The community doc (Model 4) points at the in-memory graph as the aim; the evidence now
  says: **adopt CHEER's model-and-walk, explicitly reject the in-memory materialisation.**
- CHEER's rubric to adopt with it: hot operational data (audit, notifications) carries a
  plain `workspaceId`/`teamId` and skips the graph.
- Known defects in the current implementation to fix-or-retire with the rewrite:
  `getNodeFromGraph` operator-precedence bug (slug match ignores type filter);
  `checkPermissions` result **ignored by its caller**; TASK granted to all users via ROOT
  special case; constructor TODO on graph init.
- CHEER weaknesses to design around, not inherit: no per-request memoisation (10+ graph
  queries on some paths), no transactionality between domain write and node/edge write.

### 3.2 Authorization currently soft-fails (pre-v5 hardening needed)
`SecurityInterceptor`'s permission-mismatch branch **returns `true`** (`// TODO set this to
return false`) — fine-grained permission denial is logged, not enforced; only token-scope
mismatch 401s. Related: agent register/queue endpoints unauthenticated; `/internal/token`
debug endpoint with TODO; **two different gate properties** (`flow.auth.enabled` vs
`flow.authorization.enabled`) control different halves of security; `Permission(String)`
constructor parses but never assigns. These predate v5 but any Phase 2A module-boundary or
engine-mode work builds on this surface — the spec should add a **security hardening
workstream** (small, immediate) rather than let these ride to Phase 3.

### 3.3 Flow materialises ALL TaskRuns upfront — a real reconciler design fork
`DAGUtility.createTaskList` pre-creates every TaskRun (`notstarted/pending`) at queue time;
the walk then gates them (`canExecuteTask` = all deps completed; skip = no surviving path).
ARCHIE creates runs as the walk advances. The spec's generic `reconcile()` implicitly assumes
create-missing semantics. **The reconciler design must choose**: keep materialise-all (then
"reconcile" = re-gate + supersede, and "missing vs legitimately-skipped" (Q-114) is largely
answered by the existing explicit `skipped` status) or move to create-on-walk (ARCHIE-style,
bigger change, smaller documents). This belongs in Phase 1 §4.1 as an explicit inventory
item and a new question (Q-117 proposed below).

### 3.4 Two timeout models already coexist — the sweep decision has a new input
The engine already uses **durable JobRunr jobs** for workflow timeout (survives crashes, any
instance executes, idempotent guard) while task timeout is in-memory. L-02 ("timeouts as
reconciliation, no per-run timers") now has a real alternative in-house: per-run **durable**
timers. The v5 decision is no longer sweep-vs-in-memory but **sweep vs durable-job-per-run vs
hybrid** (e.g. JobRunr for coarse workflow timeout + sweep for claim-lease recovery). Needs
its own question (Q-227 proposed below).

### 3.5 Composition semantics are thinner than the spec assumes (Q-116 answered)
`runworkflow` submits the child in-process and the parent task **succeeds immediately** —
there is **no parent field on the child WorkflowRun** (linkage = a RunResult on the parent
task + a flow-side relationship edge created via an engine→flow HTTP callback), **no
cancel/retry cascade**, and no recursion limit. `runscheduledworkflow` creates a runOnce
schedule via another engine→flow callback. Target semantics (fire-and-forget vs
wait-for-child, cascade rules, depth limits) must be *designed*, not just inventoried.

### 3.6 The decoupling map must include engine→flow callbacks
Three exist (`WorkflowClient`): create-schedule (runscheduledworkflow), submit (commented
out), and **create-WorkflowRunRelationship** — the engine cannot currently complete a
runworkflow task without flow being up. Q-201's call graph and the engine-mode analysis
(Q-206..Q-209) must cover this direction; the relationship-creation callback is also a seam
argument for where relationship writes belong (Q-132).

### 3.7 Public architecture docs have drifted badly
`Eventing.md` + all of `architecture.drawio` describe the v3 Listener/Controller/NATS
topology (2021); ADR002's decision section is an accidental copy of ADR001 (the start/end
node decision is unrecorded); `Integrations.md` is a stub; nothing documents the v4 agent
queues, JobRunr, or the actual shipped topology beyond one PNG. The most current doc
(RelationshipGraph.md Model 4) recommends the design §3.1 now argues against. **v5 should
add a documentation deliverable**: update the community architecture docs at each phase
gate, and correct RelationshipGraph.md with the Model-4-in-production findings.

---

## 4. Q-register: early answers now available

> ⚠️ **Status: PROPOSED, not confirmed.** These are evidence-backed candidate answers.
> The maintainer will walk the register one-by-one before any answer is recorded as
> final in the master spec. Do not copy these into `v5-enhancemnet.md` §8 as settled.

| Q | Status | Evidence (this review) |
| --- | --- | --- |
| Q-110..Q-112 | Substantially answered | §1 last row + §3.3 (17 task types; always/success/failure edges; regex decisions; AND-join with OR gate comment) |
| Q-113 | Answered | `workflowRef`+`workflowRevisionRef`+denorm `workflowVersion` pinned at submit; latest-or-requested resolution |
| Q-114 | Largely answered | explicit `skipped` status + path-reachability; remaining work is reconciler treatment (§3.3) |
| Q-115 | Open | result resolution is a live `findFirstByNameAndWorkflowRunRef` read — supersede behaviour unverified (no supersede exists yet) |
| Q-116 | Answered (current state) | §3.5 — no cascade, no parent field; target semantics to design |
| Q-120 | Substantially answered | 8 lock sites incl. user-facing lock task types; all single-document-claimable except `acquirelock`/`releaselock` semantics |
| Q-121 | Answered | §3.4 — durable workflow timeout, in-memory task timeout, no sweep |
| Q-122/123/125 | Answered | no FIFO, no backoff fields, no per-type caps; retry = clone-and-requeue on workflow timeout only |
| Q-124 | Open | needs timeout-vs-transport measurement |
| Q-126 | N/A today | no pause exists at all (nearest: `waiting` status, `awaitingApproval` flag) — design fresh per L-08 |
| Q-127 | Partial | transitions are lock-guarded not idempotent; full audit still needed |
| Q-128 | Answered | bare `agentRef` on both run entities + `AgentEntity` registration; no claimedAt/lease/epoch/token |
| Q-130/131/134 | Substantially answered | §3.1 |
| Q-201 | Substantially answered | §1 call-graph row + §3.6; async candidates: webhook submit, `eventWorkflowRun`, approval `endTaskRun` |
| Q-202 | Substantially answered | lib-common classification done at package level |
| Q-221 | **Needs rewrite** | §2.1 — JobRunr, not Quartz |
| Q-223/224 | Answered / reframed | zero `@Version`; ARCHIE evidence says CAS + sweep, not @Version-retry (§2.2) |

---

## 5. Proposed spec amendments

1. **Correct §2/§4.2 ARCHIE facts**: 9 action variants; claimedBy written-never-read; CAS
   (not `@Version`) for contended transitions; add the third failure class; add the
   operational lessons (count-don't-load, retryAfter-as-eligibility, recovery-event
   rate-limiting).
2. **Rewrite Q-221** for JobRunr **and widen it**: JobRunr is not presumed for v5 — the
   question is which scheduling substrate horizontally scales 1..n instances with the
   fewest moving parts. Candidate: once atomic claiming + the recovery sweep exist, cron
   schedules can become due-time documents claimed atomically by the same watcher pattern
   (the CHEER/ARCHIE approach — neither uses a job-scheduler library at all), making
   JobRunr deletable alongside the distributed lock. Evaluate: JobRunr-as-is (two
   storages?), JobRunr consolidated, or claim-based scheduling with no scheduler library.
   Add **Q-227**: timeout architecture — **watcher/sweep-based is the leading candidate**
   (maintainer direction); durable per-run jobs (today's JobRunr workflow timeout) the
   alternative. Design constraint either way: **timeout enforcement must NOT be bound to
   the instance that claimed the run** — any surviving instance must be able to reap a
   crashed claimant's runs (this is the crash-recovery property; lease/fencing from L-12
   is what makes cross-instance reaping safe).
3. **Add Q-117**: reconciler model — keep materialise-all-TaskRuns (re-gate + supersede) or
   move to create-on-walk; consequences for Q-114/Q-115 and document size. **Include an
   industry analysis** (maintainer direction): Tekton (create-on-walk, level-triggered
   controller reconcile — the closest analogue), Temporal (event-sourced history replay,
   no materialisation), Airflow (pre-materialises all TaskInstances per DagRun), Argo
   Workflows (all node statuses in one document — hit size limits, added offloading; a
   cautionary input for materialise-all).
4. **Add Q-135** (or fold into Q-132): relationship rewrite direction = CHEER's direct-query
   anchored walk on the existing schema; in-memory JGraphT cache explicitly rejected
   (per-replica staleness = authz bug); adopt the workspaceId-for-hot-data rubric; define
   memoisation and write-transactionality answers CHEER left open. **Maintainer direction:**
   adopt-if-it-works, with the caveat that Flow's traversals may be deeper/heavier than
   CHEER's ≤4 levels — evaluate MongoDB **`$graphLookup`** (server-side traversal, one
   round trip) as the escalation path for deeper walks, noting its constraints (memory
   limits, single-collection edges, index on the connect fields) vs CHEER's deliberate
   level-by-level indexed queries.
5. **Add a security-hardening workstream** (pre-Phase 3, independent of consolidation):
   enforce the SecurityInterceptor permission branch; authenticate agent endpoints; unify
   `flow.auth.enabled`/`flow.authorization.enabled`; remove/lock `/internal/token`.
6. **Add the agent-wire-contract task** (§2.3): split agent protocol models from public API
   models so the status-only invariant can actually be enforced.
7. **Extend Q-201/engine-mode tasks** to the engine→flow callback direction (§3.6).
   **Maintainer direction:** the callbacks' resolution is contingent on Q-211 — if engine
   merges back as a module, they become ApplicationEvents/module calls; if it stays
   separate (and in `engine` mode regardless), the dependency must invert: engine emits an
   event (e.g. child-run-created), the flow side consumes and writes the relationship —
   the engine must never synchronously require flow to be up.
8. **Add a documentation deliverable**: community architecture docs updated per phase gate;
   RelationshipGraph.md corrected with production findings; ADR002 repaired.
9. Update CLAUDE.md Technology Stack (JobRunr; distributed-lock engine-only pending
   deletion) and the release-tag format (`<svc>@<semver>`, per Q-213 evidence).

---

## 5a. Maintainer direction (recorded 2026-07-22)

1. **JobRunr is not a given** — any mechanism that horizontally scales 1..n instances
   qualifies; evaluate deleting the scheduler library entirely via claim-based due-work
   (folded into amendment 2).
2. **Relationship layer**: adopt the CHEER model if it works for Flow's (potentially
   deeper) graph; evaluate `$graphLookup` support for heavier walks (amendment 4).
3. **Reconciler fork (Q-117)** requires a deep dive including how Tekton / Temporal /
   Airflow / Argo handle DAG state materialisation (amendment 3).
4. **Timeouts**: preference for WorkflowWatcher/sweep-based handling over JobRunr; the
   claiming-instance question is resolved by the design constraint that reaping must be
   instance-agnostic (amendment 2 / Q-227).
5. **Engine→flow callbacks**: contingent on the Q-211 merge decision; dependency direction
   inverts either way (amendment 7).
6. **Q-register process**: proposed answers in §4 are to be reviewed one-by-one with the
   maintainer before being recorded as final in the master spec.

## 6. What NOT to take from the references (per the "patterns only" constraint)

- **ARCHIE**: the graph-as-code (hand-ordered reconcile blocks, switch-on-RunType dispatch,
  hardcoded supersede-downstream sets incl. the buried "agents ⇒ chair+diagrams" closure),
  WAITING-parent type-pair fan-ins, string-match rate-limit detection, thread-interrupt
  recovery (process-local; invalid for remote agents), domain persistence inside the queue
  service. ARCHIE's own javadoc concedes its WorkflowDefinition layer is what Flow's
  data-driven DAG replaces.
- **CHEER**: the shallow fixed hierarchy assumption (Flow's graph has more node types and
  team-scoped semantics); services-call-check-directly without per-request memoisation;
  stringly-typed role data on edges (consider typed roles); its coarse `type/**` permission
  matching (Flow's finer resource/action model should gate, once the interceptor enforces).
- **Community docs**: Model 4's in-memory materialisation (§3.1); everything v3-era.

# Boomerang Flow Services — Claude Code Context

## What This Repository Is

Boomerang Flow is an open-source, cloud-native, low-code/no-code workflow automation platform.
Workflows execute as Directed Acyclic Graphs (DAGs). Apache-2.0 licensed.

This is a **Java 25 / Spring Boot 4 monorepo** (plus one pnpm/Vite frontend). Current code state
(post-merge — `service-flow` + `service-engine` were merged into `service-core` at E8, per DD-02):

| Module           | Role                                                                                     |
| ---------------- | ---------------------------------------------------------------------------------------- |
| `service-core`   | The merged deployable: v2 REST API, auth/authz, workspaces, workflows, AND the DAG execution engine. Runs as `flow.mode = standalone \| engine`. Nine flat feature packages: `io.boomerang.{core,workspace,workflow,engine,dispatcher,schedule,event,integrations,api}`. |
| `service-agent`  | Pluggable execution worker (→ `dispatcher` per DD-06). Default implementation: Tekton on Kubernetes. |
| `service-loader`  | Flamingock migrations + bootstrap seeding, run as a pre-deploy Job (DD-07).              |
| `lib-common`     | Shared domain model, entities, enums, error handling. (Being dissolved per Q-202.)       |
| `client-web`     | The React 17 + Carbon v11 webapp (DD-04), folded in from `flow.client.web` with full history at the v4 line. Its own image; served only in `standalone` mode. |

## You Are Working On: v5

v3 is legacy (IBM maintain a fork — do not regress to v3 patterns). v4 split flow/engine as
separate deployables. **v5 reverses that split — see the confirmed decisions below.**

### Confirmed v5 Decisions (master spec §10 — do not re-litigate; re-open only with new evidence)

| DD    | Decision                                                                                                                                                                          |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DD-01 | **Rename Team → Workspace** at the v5 major (API paths aliased for deprecation; module named `workspace`; loader migration for RelationshipType/AuthScope values).                  |
| DD-02 | **MERGE `service-flow` + `service-engine`** into ONE deployable with **`flow.mode = standalone \| engine`** (AM-7, 2026-08-15: FULL collapsed into STANDALONE — "standalone" = the complete self-contained product and the default; "engine" = embedded headless; the laptop case = the product with security off). Agent stays separate. Falsifiability F1–F5 stays live. **AM-1 (2026-08-14): no Spring Modulith, no ArchUnit — boundaries by convention (ARCHIE flat-feature-package style). See `specifications/merge-execution-plan.md`.** |
| DD-03 | **Unified product versioning** — one tag builds the compatible image set; no independent engine version line (`engine@` alias tags during the embedder deprecation window).         |
| DD-04 | **Frontend (`flow.client.web`) joins this monorepo** — after the merged image ships; continues from the **v4 line** (`main`, at tag `4.0.0`+3; the `3.12.0` in package.json is a stale field — releases are tag-driven, as on the backend), joining the DD-03 unified product version at 5.x; webapp served only in `standalone` mode (AM-7).           |
| DD-05 | **Merged deployable module = `service-core`** (executed at E8; ARCHIE/CHEER convention; "engine" stays an internal module + mode name only).                                        |
| DD-06 | **Worker tier renamed `agent` → `dispatcher` at E7/E8** (`DispatcherProtocol`, `dispatcherRef`, `dispatcher-tekton`/`dispatcher-docker`; v1 wire keeps `/agent/` until retirement). "Agent" is reserved for the AI task types. |
| DD-07 | **Migrations = `service-loader` module in this monorepo on Flamingock**, run as a pre-deploy Job (one execution per deploy); baseline changeunit covers live instances; E3's schema ships as its first changeunits. In-app-at-boot re-decided after the merge. |
| DD-08 | **Control/execution state = typed fields; annotations/labels = non-identifying metadata only** (UI, catalog, user tags). Anything the engine reads to decide or queries/indexes on MUST be a typed field — never a `boomerang.io/*` annotation. Anchored by the industry norm (K8s spec/status vs annotations; Tekton typed `retriesStatus` + timeout `reason` enum; Argo `status.nodes`; Temporal Memo-vs-typed; Airflow/Prefect/n8n/Langflow typed columns; n8n `retryOf`). Migration is incremental: category A (`retry-of`→`initiatedByRef`+`trigger`, `retry-count`→field, `timeout-cause`→enum) rides E5; executor-config (`task-*`) and param-context (`*-params`) annotations are later cleanups. `RunStatus` stays a closed enum — pause/supersede stay orthogonal fields (H15). |

The full architecture record — nine-module layout, interaction classification, mode matrix,
embedded-engine contract, 14 ruled judgement calls, migration plan — is
**`specifications/consolidation-proposal.md`** (approved 2026-07-22).

## v5 Phase Sequencing

| Phase       | Work                                                                                              | Status                                                                    |
| ----------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **Phase 0** | Framework baseline upgrade (Java/Boot/deps current stable; Testcontainers safety net FIRST).       | ✅ **COMPLETE (2026-07-23, PRs 1–5 on feat-v5)**: Boot 4.1.0 + Java 25, Jackson 3, fabric8 7.8 + Tekton v1, springdoc 3, Testcontainers 2.0.5 safety net, Micrometer Tracing, JaCoCo working; Quartz/flapdoodle/OpenTracing fully removed. Forced bumps: JobRunr 8.7.1 (⚠️ Mongo schema migration on first boot), distributed-lock 3.0.0. Q-005 (virtual-thread measurement) deferred to E4 — measure on the NEW claim pollers, not the ones being deleted. |
| **Phase 1** | DAG semantics inventory + ARCHIE lessons + relationship review (vs CHEER).                          | ✅ **COMPLETE** — all questions answered/ruled incl. Q-117 (reconciler: materialise-all + supersede generations + placeholder-expand fan-out; reference+retention for definitions), Q-124 (timeout audit — invariant violated 4/6 classes), Q-127 (idempotency audit — the ranked Phase 3 gating list). Lessons Verdicts table filled. |
| **Phase 2** | Consolidation analysis (2A) + scaling/locking/queueing (2B).                                        | ✅ **BOTH COMPLETE.** 2A decided (DD-02, proposal approved). 2B ruled 2026-07-23: queue design (`queue-design.md` — 4 classes, page-then-CAS, lease≠timeout, typed failureClass), multi-instance model (`multi-instance-model.md` — CAS-only/no-@Version, outbox, no broker, no partitioning, no leader), gap register + Phase 3 backlog (`gap-register.md`). Deferred to implementation: schedule-firing substrate (JobRunr vs due-work) and outbox transactions — defaults documented. |
| **Phase 3** | Core engine implementation (claims, sweep, pause, generic reconciler, supersede, typed queues), then the merge itself. | Blocked on Phase 0 + remaining Phase 1/2B items.                          |
| **Phase 4** | Task runtime evolution: AgentRuntime SPI; local Docker runtime (separate agent process for now); Tekton behind the SPI. | Open.                                                                     |

## v5 Execution-Model Direction (verified against ARCHIE/CHEER shipped code)

- **Atomic claiming via `findAndModify`** — no distributed locks. Claims carry `claim.by`/
  `claim.at`/`claim.seq`, and **fencing is genuinely validated on result writes**
  (`claimantIsValid` at start/end) — ARCHIE writes `claimedBy` but never reads it, and Flow does
  add the semantics. `leaseExpiresAt` is declared and indexed but **written nowhere** (only ever
  unset), so leases are inert vocabulary: crash recovery is pure deadline-based reaping off
  `timeoutAt`. Do not describe leases as a working guard.
- **Contended transitions use status-CAS `findAndModify`** (`tryTransition(expected→target)`)
  — NOT `@Version` retries. The watcher/sweep re-drives after swallowed conflicts.
- **Timeouts & crash recovery = WorkflowWatcher recovery sweep** — instance-agnostic reaping
  (any survivor reaps a crashed claimant's runs; lease/fencing makes it safe). No per-run
  timers. Run timeout must be ≥ the transport timeout of the guarded work.
- **Retry: ONE generic backoff class is built** (`Backoff`, 10s base → 5m ceiling, stored as
  `retryAfter` = claim-eligibility). The designed rate-limit and deterministic-terminal classes
  were **never implemented** — there is no `retryClass` field, and an agent-reported *failure*
  (vs a timeout) is not retried at all. Ruled 2026-08-18: correct the spec rather than build them
  ahead of proven need.
- **Pause is a committed v5 feature** (`pauseRequestedAt: Instant` flag — NEVER a RunStatus
  value) enforced at a **single admission gate**: a paused run admits no new TaskRuns (the
  DAG advance/queue chokepoint at `TaskExecutionService.queue`), while work already in flight
  — claimed, running, or already `ready` — runs to completion and times out on its absolute
  deadline regardless of pause. Resume = clear flag + reconcile. (The earlier three-chokepoint
  design — claim-query exclusion + transition gate + sweep-skip — collapsed to the admission
  gate once claim-query exclusion proved redundant and needlessly held back in-flight work.)
- **Starvation-safe typed queues**: FIFO on a compound index (loader-managed — the entity index
  annotations are inert, `auto-index-creation=false`); backoff exclusion IN the query.
  **Per-type concurrency caps and the kill switch do not exist** — `findClaimable` filters only by
  the agent's registered task types. Load testing reopens this, not speculation.
- **Relationship layer**: keep the existing `rel_nodes`/`rel_edges` schema, adopt CHEER's
  direct-query anchored walk; the in-memory JGraphT singleton is **rejected** (per-replica
  staleness = authz bug). `$graphLookup` is the escalation path for deeper walks. Hot
  operational data carries `workspaceId` directly and skips the graph.
- **Do not over-abstract ahead of proven need** (ARCHIE meta-lesson).

## Deployment Constraint — Custom HTTP Client Config Is a Product Requirement

Enterprises run Flow behind private networks, reverse proxies, and internal CAs. The
custom `RestConfig` (proxy host/port support, trust-all template for self-signed internal
certs, explicit per-template timeouts, dedicated streaming template) is **a product
requirement, not incidental plumbing** — every framework upgrade (including any
RestTemplate → RestClient migration in Boot 4+) must preserve these knobs with equivalent
behaviour, never "simplify" onto framework defaults.

## Architecture Invariants — Do Not Violate

- **Status is the only external-facing field.** Phase is internal orchestration state and is
  never exposed in public API responses. (Currently violated — `phase` serialises in
  `WorkflowRun`/`TaskRun` models and the agent protocol depends on it; fixed via the
  model-entity flattening + `AgentProtocol` v2 split. Phase stays on the agent wire only.)
- **WorkflowRun is the execution record. Domain entities are domain artefacts.** No
  execution state on domain entities; two-pointer pattern for re-runs.
- **Transition handlers must be idempotent** — re-read state, check the transition hasn't
  happened, versioned/CAS writes, never create a step's TaskRun if a non-SUPERSEDED
  SUCCEEDED one exists.
- **The engine never synchronously requires the flow side** — engine→flow interactions are
  events (post-merge: ApplicationEvents; the old HTTP callbacks are being deleted).
- **Never add `PAUSED` or `SUPERSEDED` as `RunStatus` values** — the frontend enum is
  closed; use `pauseRequestedAt` and supersede flags/exclusions.
- **No new synchronous HTTP between service-flow and service-engine** during the transition.

## Known Current-State Hazards (pre-v5 fixes in flight)

- `SecurityInterceptor` **soft-fails permission checks** (logs and returns `true`) — only
  token-scope mismatch is enforced. Enforcement flips via shadow-logging → token backfill →
  flag → default-on at the major. The riskiest flip in v5. **Shadow telemetry is LIVE
  (E1/E6, 2026-07-23)**: `flow.security.would.deny` counts both the interceptor layer and
  the relationship layer (`layer=relationship` tag) — watch these before the A2 flip.
- ~~The relationship JGraphT singleton (authz bug under N instances)~~ **FIXED (E6,
  2026-07-23)**: direct-query anchored walk, replica-parity proven by test.
- Agent endpoints (`AgentControllerV1`) are **unauthenticated**; engine is `permitAll()`.
- Two properties gate security halves: `flow.auth.enabled` AND `flow.authorization.enabled`
  (to be unified; mode-derived default).
- Agent queue claiming is non-atomic (find-then-update race) until Phase 3 lands — worse,
  the claim *loser still dispatches* (the queue returns the find result, not the claimed
  set), and terminal-phase runs are redelivered to every agent on every poll.
- **The distributed locks are not actually mutually exclusive** (deterministic token:
  racing acquirers with the same key both succeed; `releaseLock` deletes anyone's lock) —
  the engine runs on best-effort locking today. See `specifications/idempotency-audit.md`
  (F2) and its ranked Phase 3 gating list (~20 of 31 handlers unsafe).
- **Workflow delete is a data-loss bug**: it cascades actions → task_runs → workflow_runs
  → revisions with NO in-flight-run guard — running executions (incl. agent-side Tekton
  work) are silently orphaned. Task-template delete equally unguarded. **Decided fix
  (maintainer direction)**: delete = tombstone; the WorkflowWatcher cancels in-flight
  runs of tombstoned workflows via the normal cancel path; hard pruning = a separate
  retention sweep once runs finalise (`specifications/reconciler-analysis.md` §4).
- **Timeout invariant violated in 4 of 6 work classes** (`specifications/timeout-audit.md`):
  engine reaps healthy Tekton tasks (T vs the agent's T+10 grace); a `DAGUtility` bug
  discards per-task timeouts when the annotation is absent; three of four RestTemplates
  have NO read timeout; log streams die at flow/engine's 30s async default.

## Technology Stack (current code state)

| Concern             | Technology                                                                                     |
| ------------------- | ----------------------------------------------------------------------------------------------- |
| Language / Framework| Java 21, Spring Boot 3.4.x (Maven monorepo; parent pom + module poms)                           |
| Database            | MongoDB (SpEL-prefixed collections via `flow.mongo.collection.prefix`)                          |
| Scheduling          | **JobRunr 7.4.1** (flow schedules `_sch_` prefix; engine timeout jobs `jr_`). Quartz is gone — only a transitive remnant via `lib-scheduling`. JobRunr itself may be deleted for claim-based due-work (Q-221/Q-227). |
| Events              | CloudEvents (inbound webhooks/events in flow; optional outbound status sink in engine, off by default) |
| Distributed lock    | `alturkovic/distributed-lock` — **engine-only, slated for deletion** in Phase 3                 |
| Execution runtime   | Tekton on Kubernetes (default agent); local Docker agent planned (Phase 4)                      |
| Frontend (separate repo until DD-04) | `flow.client.web` — React 17 + IBM Carbon v11 + `@boomerang-io/carbon-addons-boomerang-react` (see `specifications/design-system.md`) |

## Running Locally

```bash
docker run --name local-mongo -d mongo:latest

# Seed data and indexes
docker run -e JAVA_OPTS="-Dspring.data.mongodb.uri=mongodb://localhost:27017/boomerang \
  -Dflow.mongo.collection.prefix=flow \
  -Dspring.profiles.active=flow" \
  --network host --platform linux/amd64 boomerangio/flow-loader:latest

mvn clean install
mvn spring-boot:run -pl service-core
```

The webapp runs separately against it (`standalone` mode only):

```bash
cd client-web && pnpm install && pnpm start   # Vite dev server; BASE_URL ← PRODUCT_SERVICE_ENV_URL
```

Security can be disabled for local development (one property; default derives from `flow.mode`):

```properties
flow.security.enabled=false
```

## Error Response Format

All API errors use `io.boomerang.error.ErrorDetail`:

```json
{ "timestamp": "...", "code": 1001, "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(status) have been provided.", "status": "400 BAD_REQUEST" }
```

Known codes: `io.boomerang.error.BoomerangError`; messages in `messages.properties`.

## Releasing

Container images build when a tag matching **`<svc>@<semver>`** is pushed (the CI truth —
`.github/workflows/ci-*.yml` trigger on `flow@**`, `engine@**`, `agent@**`):

```
flow@4.0.1
engine@1.0.0-beta.111
```

Use the `/release` skill. DD-03 (unified product version) replaces this scheme when the
merge ships. An SBOM/CVE pipeline exists (`.github/workflows/sbom.yml`, `/cve-review` skill).

## Specifications Index

| Specification                             | Status                       | Description                                                                    |
| ----------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------ |
| `specifications/v5-enhancemnet.md`        | 🔴 **MASTER — start here**   | The authoritative v5 spec: phases, Q-register (answers recorded in place), Living Sections, §10 Decisions (DD-01…DD-04). Note the filename typo is historical — keep it. |
| `specifications/consolidation-proposal.md`| ✅ Approved (2026-07-22)     | Phase 2A architecture record: modules, interactions, mode matrix, embedded-engine contract, ruled judgement calls, migration plan. |
| `specifications/v5-spec-review.md`        | 📎 Evidence record           | The deep review that grounded the Q-register answers (codebase + ARCHIE + CHEER + community docs). |
| `specifications/reconciler-analysis.md`   | ✅ Ruled (2026-07-22)        | Q-117: materialise-all + extensions confirmed; industry study (Tekton/Temporal/Airflow/Argo) preserved incl. the create-on-walk analysis for future reference. |
| `specifications/idempotency-audit.md`     | ✅ Audit (Phase 3 gate)      | Q-127: 31 handlers audited, ranked must-fix-before-lock-deletion list.        |
| `specifications/timeout-audit.md`         | ✅ Audit                     | Q-124: full timeout inventory, per-class invariant verdicts, sweep-design constraints. |
| `specifications/queue-design.md`          | ✅ Ruled (2026-07-23)        | Q-225: claim/queue design (4 classes), WorkflowWatcher spec (6 sweeps), scheduling-substrate comparison (decision deferred). |
| `specifications/multi-instance-model.md`  | ✅ Ruled (2026-07-23)        | Q-222/223/224: write discipline (CAS-only), eventing (outbox/no broker/no partitioning), reconciler convergence. |
| `specifications/gap-register.md`          | ✅ Delivered                 | Q-220/226: lock inventory, master gap list (BEFORE/WITH/POST-MERGE), Phase 3 backlog E0–E11 + the 12 Phase 0 test scenarios. |
| `specifications/phase2b-decisions.md`     | ✅ Decision record           | The 14 Phase 2B rulings with current-vs-proposed detail (11 ruled, 3 deferred). |
| `specifications/service-consolidation.md` | 📎 Annex (superseded by proposal) | Original Phase 2A brief.                                                  |
| `specifications/scaling.md`               | 📎 Annex                     | Phase 2B locking/queueing brief.                                               |
| `specifications/design-system.md`         | 📎 Reference                 | IBM Carbon + Boomerang theme design system (source of truth: `flow.client.web`). |
| `specifications/e4-review-findings.md`    | 📋 Captured (2026-07-25)     | Four-way critical review of the E4 code (perf/structure/duplication/maintenance + correctness bugs). **Not actioned:** sequenced E5 → critical re-review → fixes. |
| `specifications/merge-execution-plan.md`  | 🔵 **ACTIVE (2026-08-14)**   | T4 execution sequence for the DD-02 merge: E8–E11 slices, gate pre-fills, and the 5 amendments (AM-1 no-Modulith/no-ArchUnit boundaries-by-convention, AM-2 H3 moot, AM-3 leases deferred, AM-4 E9 G2 lineage via `initiatedByRef`, AM-5 dispatcher token reuse). |
| `specifications/api-contract-trace.md`     | 🔵 **ACTIVE (2026-08-18)**   | End-to-end webapp↔service-core contract trace (call site → route → service → serialised fields). Live defects, blocked capability, the permissions/auth findings that gate the frontend work, and the maintainer decisions outstanding. |
| `specifications/repo-insights-engagement-inputs.md` | 🟡 Inputs — proposed (2026-08-09) | Client-engagement requirements for a future v5 phase: pull-based **executor SPI** (zone queues, payload cap), **evidence/custody ledger** in the task-result contract, **executor portfolio** (K8s Jobs default, VM/MicroVM, CoCo flag), workspace non-retention guarantee, thin LLM task type + **propose/dispose** governed agency, **Embabel** spike. Not ruled — proposed→confirmed when the phase is worked. |

Reference codebases (patterns only — Flow is more complex; adopt the pattern, not the code):
ARCHIE = `/Users/tysonlawrie/Workspaces/tlawrie/asdr` · CHEER =
`/Users/tysonlawrie/Workspaces/walkaboutdev/cheer.dev` · Frontend =
`/Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web`.

## What To Work On First

**All analysis phases are complete.** The implementation program is
`specifications/gap-register.md` §3 (epics E0–E11). **Two standing gates apply to EVERY
epic (maintainer-mandated): stop at phase start and (G1) declare whether the phase
touches `DAGUtility` or `TaskExecutionService` — if yes, enumerate the exact
methods/semantics and get review; (G2) present the phase's data-model changes (fields,
indexes, collections, migrations) for discussion BEFORE implementing them.** The
prospective gate table in gap-register §3 pre-fills both for all epics. If you have no other instruction,
start **E0 — Phase 0**: the Testcontainers safety net (the 12 scenarios listed in the
gap register) FIRST, then the baseline-upgrade PR sequence. **E1 (security
shadow-enforcement) and E2 (hazard stopgaps: transport timeouts, DAGUtility timeout
bugfix, delete guards, streaming client) can land immediately in parallel** — they have
no structural dependencies. Do not start E4 (the execution-model rebuild) until E0's
safety net is green. All decisions (DD-01…DD-04, Q-117, the Phase 2B rulings in
`phase2b-decisions.md`) are settled — build toward them; two deferred decisions
(schedule-firing substrate, outbox transactions) are decided at their implementation
step with defaults documented.

**Current state (2026-08-09) — the "where are we" a fresh session should read first:**

SHIPPED on `feat-v5` (the integration branch): Phase 0, E1/E2/E3/E6, **E4** (execution-model
rebuild, slices A–F: claims/CAS, WorkflowWatcher sweeps, pause, outbox, lock-free, tombstone;
repository Compare-And-Set ops live IN the services), and **E5** (JobRunr FULLY retired —
grep-clean; engine timeout job deleted, claim-based `ScheduleWatcher` in service-flow fires
cron via a `nextFireAt`-advance Compare-And-Set with a bounded `retryCount` retry; team
resolved from the relationship graph; forward calendar unchanged/cron-utils). Merged via **PR
#311**. The behaviour-preserving part of the E4 review also shipped (dead code, DAG hot-path
reads, `Backoff`/`SweepRunner`/`findAndModifyPreImage`/`RunTimeouts` dedup, two latent bug
fixes A1/A4).

SHIPPED (Track 1, PR #311→#312 merged to `feat-v5`): P-A1 (ParameterManager dead code), P-A2
(param memoization), **A2 claim-payload bug fix** (agents get post-claim `phase`/`agentRef`),
**P-A3** (`resolveParam` kept as the single dispatch, shape branches inlined; characterization
harness), **P-B reverted → RULE**: entities stay Lombok-only, no `Fields` constant classes.
CI cleanup (`--also-make` on test jobs, push/PR trigger scoping, concurrency groups). **A3 is
NOT a bug** (the weak `tryComplete` fencing is load-bearing for cancel/timeout of claimed
tasks → Phase 3).

SHIPPED (Track 2, on `feat-v5`): **D5** approval-recompute gated to `approval`/`manual`
completions + `existsBy`; **D7 resolved by design** — pause is now a **single admission gate**
at `TaskExecutionService.queue` (in-flight/already-`ready` tasks run to completion; the
`excludePausedRuns` two-step join is deleted, the three-chokepoint discipline collapses to one
gate); minor hardening (`register` atomic upsert, workspace unique-merge). The idempotency-audit
reconciliation showed **E4/E5 already closed the concurrency core** (6/10 ranked items fully
fixed; the still-open residue is caller-level run-creation idempotency keys, which the "dropped
run-creation dedup" decision deliberately skips). D11/Q-005 (agent poller) stays a measurement step.

SHIPPED (Track 3 / E7, on `feat-v5`): **D11/Q-005** measured (claim path does not pin virtual
threads on Java 25 + driver 5.8.0) → **`spring.threads.virtual.enabled=true`** on the engine.
**E7-1** agent dispatches claimed runs on `queued` (fixed the A2-exposed dispatch break) + a
cross-module contract test. **E7-2** public/agent model split — `TaskRun` flattened off the
entity (drops the `agentRef` leak, keeps `phase`), `WorkflowRun` drops `pauseRequestedAt` (keeps
`phase`); **no bespoke wire model** — the worker gets the plain run (a `WorkflowRunClaim` attempt
was collapsed back). **E7-4** dispatcher protocol — `agent`→`dispatcher` rename, `/api/v1/agent`
replaced by `/api/v1/dispatcher` (no dual-serve), plain `TaskRun`/`WorkflowRun` on the wire; A3
**interim static bearer-token auth** on `/api/v1/dispatcher/**` (`DispatcherAuthFilter`). A
dispatch-envelope + worker `claimSeq` fencing + lease-at-claim were built then **stripped** (the
engine already fences via `claim.seq` CAS; nothing reads `leaseExpiresAt` yet — deferred).
**DEFERRED to post-merge/E8**: E7-5 persisted `agentRef`/`agents` rename + migration; the
first-class Flow dispatcher token (`AuthScope.dispatcher` + `bfd` prefix — check ARCHIE first,
gap-register A3); worker leases + fencing.

REMAINING WORK is organised as **Tracks 1–6** (the roadmap): T1 review-refactor remainder
(P-A3✅, P-B✅, A2✅); T2 Phase-3 hardening (D5✅, D7✅ via single admission gate; REMAINING:
the caller-level idempotency-key residue #23/#15/#16 is deferred by the "dropped run-creation
dedup" decision unless re-opened; **D11/Q-005 agent-poller is re-sequenced INTO T3/E7** as its
opening measurement, not a standalone T2 item); T3 E7 worker/dispatcher — **D11/Q-005 poller
measurement DONE (2026-08-13): claim path does not pin virtual threads (Java 25 JEP 491 + driver
5.8.0), so `spring.threads.virtual.enabled=true` shipped on the engine, removing the ~200-agent
platform-thread ceiling; the residual idle busy-poll DB load is carried into protocol-v2 design
(async/event-driven poll, option (c))** — then DD-06 rename, protocol v2, worker leases via the
pre-provisioned `leaseExpiresAt`, @Audited port; T4 the DD-02 flow/engine merge (F1 god-class split, F2 CAS-out-of-services, F3 DI, F4
index authority, C5, the merge itself, E9 egress); T5 broader v5 DDs (DD-01 Team→Workspace,
DD-03 versioning, DD-04 frontend); T6 post-merge cleanups (drop `jr_`/`_sch_`/`locks`
collections); **T7 executor-SPI + governed-agency** (engagement-driven, 🟡 proposed —
pull-based zone-queue executor SPI, evidence/custody ledger, executor portfolio, thin LLM
task type + propose/dispose; see `specifications/repo-insights-engagement-inputs.md`). Item-level
detail + dispositions: `specifications/e4-review-findings.md`.

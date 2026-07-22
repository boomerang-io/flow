# Boomerang Flow Services — Claude Code Context

## What This Repository Is

Boomerang Flow is an open-source, cloud-native, low-code/no-code workflow automation platform.
Workflows execute as Directed Acyclic Graphs (DAGs). Apache-2.0 licensed.

This is a **Java 21 / Spring Boot 3 monorepo**. Current code state (pre-merge):

| Module           | Role                                                                                     |
| ---------------- | ---------------------------------------------------------------------------------------- |
| `service-flow`   | v2 RESTful API layer. CRUD for Workflows, Teams, Users, Tokens, Schedules. Auth/authz.   |
| `service-engine` | DAG execution backbone. WorkflowRun orchestration, TaskRun lifecycle, CloudEvents.       |
| `service-agent`  | Pluggable execution worker. Default implementation: Tekton on Kubernetes.                |
| `lib-common`     | Shared domain model, entities, enums, error handling. (Being dissolved per Q-202.)       |

## You Are Working On: v5

v3 is legacy (IBM maintain a fork — do not regress to v3 patterns). v4 split flow/engine as
separate deployables. **v5 reverses that split — see the confirmed decisions below.**

### Confirmed v5 Decisions (master spec §10 — do not re-litigate; re-open only with new evidence)

| DD    | Decision                                                                                                                                                                          |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DD-01 | **Rename Team → Workspace** at the v5 major (API paths aliased for deprecation; module named `workspace`; loader migration for RelationshipType/AuthScope values).                  |
| DD-02 | **MERGE `service-flow` + `service-engine`** into ONE Spring Modulith deployable with `flow.mode = full \| engine \| standalone`. Agent stays separate. Sequenced BEHIND the Phase 3 execution rebuild; falsifiability conditions F1–F5 stay live. |
| DD-03 | **Unified product versioning** — one tag builds the compatible image set; no independent engine version line (`engine@` alias tags during the embedder deprecation window).         |
| DD-04 | **Frontend (`flow.client.web`) joins this monorepo** — after the merged image ships; v5 re-baselines its 3.12.x history; webapp served only in `full`/`standalone` modes.           |

The full architecture record — nine-module layout, interaction classification, mode matrix,
embedded-engine contract, 14 ruled judgement calls, migration plan — is
**`specifications/consolidation-proposal.md`** (approved 2026-07-22).

## v5 Phase Sequencing

| Phase       | Work                                                                                              | Status                                                                    |
| ----------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| **Phase 0** | Framework baseline upgrade (Java/Boot/deps current stable; Testcontainers safety net FIRST).       | ⏳ Open — the immediately-implementable phase.                             |
| **Phase 1** | DAG semantics inventory + ARCHIE lessons + relationship review (vs CHEER).                          | ✅ **COMPLETE** — all questions answered/ruled incl. Q-117 (reconciler: materialise-all + supersede generations + placeholder-expand fan-out; reference+retention for definitions), Q-124 (timeout audit — invariant violated 4/6 classes), Q-127 (idempotency audit — the ranked Phase 3 gating list). Lessons Verdicts table filled. |
| **Phase 2** | Consolidation analysis (2A) + scaling/locking/queueing (2B).                                        | ✅ **BOTH COMPLETE.** 2A decided (DD-02, proposal approved). 2B ruled 2026-07-23: queue design (`queue-design.md` — 4 classes, page-then-CAS, lease≠timeout, typed failureClass), multi-instance model (`multi-instance-model.md` — CAS-only/no-@Version, outbox, no broker, no partitioning, no leader), gap register + Phase 3 backlog (`gap-register.md`). Deferred to implementation: schedule-firing substrate (JobRunr vs due-work) and outbox transactions — defaults documented. |
| **Phase 3** | Core engine implementation (claims, sweep, pause, generic reconciler, supersede, typed queues), then the merge itself. | Blocked on Phase 0 + remaining Phase 1/2B items.                          |
| **Phase 4** | Task runtime evolution: AgentRuntime SPI; local Docker runtime (separate agent process for now); Tekton behind the SPI. | Open.                                                                     |

## v5 Execution-Model Direction (verified against ARCHIE/CHEER shipped code)

- **Atomic claiming via `findAndModify`** — no distributed locks. Claims carry ownership
  metadata: `claimedBy`/`claimedAt`/`leaseExpiresAt`/`claimEpoch` (fencing validated on
  result writes). ARCHIE writes `claimedBy` but never reads it — Flow adds the semantics.
- **Contended transitions use status-CAS `findAndModify`** (`tryTransition(expected→target)`)
  — NOT `@Version` retries. The watcher/sweep re-drives after swallowed conflicts.
- **Timeouts & crash recovery = WorkflowWatcher recovery sweep** — instance-agnostic reaping
  (any survivor reaps a crashed claimant's runs; lease/fencing makes it safe). No per-run
  timers. Run timeout must be ≥ the transport timeout of the guarded work.
- **Three retry failure classes**: generic backoff, rate-limit (own base + higher cap),
  deterministic-terminal (no retry). Backoff stored as `retryAfter` = claim-eligibility.
- **Pause is a committed v5 feature** (`pauseRequestedAt: Instant` flag — NEVER a RunStatus
  value) with the three-chokepoint discipline: claim-query exclusion, single transition
  gate, recovery-sweep skip. Resume = clear flag + reconcile.
- **Starvation-safe typed queues**: FIFO on a compound index; backoff + paused exclusion IN
  the query; per-type concurrency caps; kill switch.
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
  flag → default-on at the major. The riskiest flip in v5.
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
mvn spring-boot:run -pl service-engine
```

Security can be disabled for local development (both properties, pending unification):

```properties
flow.authorization.enabled=false
flow.auth.enabled=false
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

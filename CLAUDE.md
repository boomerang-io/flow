# Boomerang Flow Services — Claude Code Context

## What This Repository Is

Boomerang Flow is an open-source, cloud-native, low-code/no-code workflow automation platform.
Workflows execute as Directed Acyclic Graphs (DAGs). Apache-2.0 licensed.

This is a **Java 25 / Spring Boot 4 monorepo** (plus one pnpm/Vite frontend). Current code state
(post-merge — `service-flow` + `service-engine` were merged into `service-core` at E8, per DD-02):

| Module           | Role                                                                                     |
| ---------------- | ---------------------------------------------------------------------------------------- |
| `service-core`   | The merged deployable: v2 REST API, auth/authz, workspaces, workflows, AND the DAG execution engine. Runs as `flow.mode = standalone \| engine`. Nine flat feature packages: `io.boomerang.{core,workspace,workflow,engine,dispatcher,schedule,event,integrations,api}`. |
| `service-agent`  | Pluggable execution worker (→ `dispatcher` per DD-06). Per-task runtime behind the `io.boomerang.executor.TaskExecutor` SPI, selected by `agent.executor`: `tekton` (default, `TektonServiceImpl`) or `kube-jobs` (`KubeJobsExecutor`, plain `batch/v1` Jobs, no Tekton). |
| `service-loader`  | Flamingock migrations + bootstrap seeding, run as a pre-deploy Job (DD-07).              |
| `lib-common`     | Shared domain model, entities, enums, error handling. (Being dissolved per Q-202.)       |
| `client-web`     | The React 18 + React Router 7 (framework mode, SSR) + Carbon v11 webapp (DD-04), folded in from `flow.client.web` with full history at the v4 line. Its own image; served only in `standalone` mode. |

## Response Style (BLUF, not caveman)

Applies to every answer, review, and report in this repo. Borrowed from BLUF (US Army AR 25-50),
Smart Brevity, MADR "considered options", RFC 2119, and Google's technical-writing guidance;
CAVEMAN's "drop articles / fragments OK" half is explicitly rejected — grammar carries nuance.

1. First sentence = the answer or decision. Never restate the question.
2. Add one "why it matters" line when the consequence or risk isn't obvious.
3. Two or more options → a table: `Option | Fits when | Cost/risk | Recommend`. One-sentence
   intro, cells ≤ 2 sentences, end with the pick.
4. Every complex item MUST carry one concrete example: a code snippet, a command, or a real
   file / field / route name.
5. Rules use RFC 2119 words (MUST / SHOULD / MAY); everything else is plain sentences.
6. Use the repo's own terms. MUST NOT coin labels or shorthand ("phase on the wire") — say the
   literal thing ("`phase` is serialised in `/api/v1/dispatcher` responses, not in the public
   API"). If a new term is unavoidable, define it once where it first appears.
7. No preamble, pleasantries, or hedging — but keep articles and full sentences; fragments
   are banned.
8. Numbers over adjectives ("3 of 6 handlers", not "several"); quote errors and paths verbatim;
   cite `file:line` or a commit hash for any claim about the code.
9. Length follows the question: one line for a fact; ≤ ~150 words plus one table for a design
   choice; tables over prose for anything with more than two dimensions.

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
| **Phase 3** | Core engine implementation (claims, sweep, pause, level-triggered advance, typed queues), then the merge itself. | ✅ **COMPLETE (2026-08-21)**. E0–E11 shipped: claims/CAS + `claim.seq` fencing, ten `WorkflowWatcher` sweeps, pause as a single admission gate, transactional outbox, lock-free (alturkovic deleted), tombstone delete, JobRunr retired, relationship direct-query walk, dispatcher protocol v2, DD-02 merge into `service-core`. **Two designed items ruled out of scope rather than built**: supersede generations + a distinct reconciler component (see `reconciler-analysis.md` implementation-status section), and `transitionSeq` for the outbox creation-loss window (see `entity-diff-v4-v5.md` §7). Both follow "do not build ahead of proven need". |
| **Phase 4** | Task runtime evolution: AgentRuntime SPI; local Docker runtime (separate agent process for now); Tekton behind the SPI. | 🔵 **STARTED (2026-08-21, `feat-v5-track8`)**: `TaskExecutor` SPI + `KubeJobsExecutor` (batch/v1 Jobs, no Tekton) + Tekton behind the SPI; agent bugfix slice (per-run PVC `"workfowRun"` typo, `(String)` param cast, `TaskWatcher` fall-through, fail-loud PVC/ConfigMap lookup, `PARAM_<NAME>` env vars). Param contract RULED + SHIPPED (2026-08-25, `runtime-contract.md` C2 + `task-contract-research.md`): engine-side `$(params.x)` substitution into spec fields at admission, `PARAM_<NAME>` + `PARAM_NAMES` env, `/params` ConfigMap and `PARAMS` JSON both removed, engine-enforced payload caps (`flow.engine.task.params.max-bytes`=16384 at admission via `tryInvalidate`, `flow.engine.task.results.max-bytes`=4096 at end). Isolation RULED 2026-08-25: one `agent.tasks.runtimeClassName` per agent deployment, NO per-task tier field (`task-contract-research.md` §6). Sensitive params: no new field — `type=password` + `DataAdapterUtil` is the marker; sensitive-upward/plain-downward trust model; the gap is that run payloads are NOT redacted (`filterRunParamValueByFieldType` has zero callers — §7). Sensitive-upward filtering CLOSED 2026-08-25 for run payloads AND the log stream (`WorkflowRunService.filterSensitiveValues` on the scoped v2 reads; `SecretScrubbingOutputStream` in `WorkspaceTaskRunService.streamLog`). **Open**: local Docker runtime; pass-by-reference blob staging; Tekton `podTemplate.runtimeClassName` parity; declared-params validation (node params ⊆ template-declared) definition-side; `@boomerang-io/task-core` env PR open (boomerang-io/tasks#13). |

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

- **Status is the target external-facing field; `phase` is deliberately exposed alongside it for
  now** (maintainer-ruled, 2026-08-18). The aspiration is that phase stays internal orchestration
  state, but **one model serves both wires**: `DispatcherControllerV1` and the public
  `/api/v2` controllers both return `io.boomerang.common.model.TaskRun`/`WorkflowRun`, and the
  dispatcher dispatches on `phase` (`TaskRun.java:23`). So `phase` serialises publicly today —
  verified, and pinned by a tripwire in `PublicRunModelSerialisationTest` that must be inverted
  when this is closed. Splitting it needs a dispatcher-specific wire model or a `@JsonView`/mixin;
  **re-open explicitly rather than re-attempting** — the earlier `WorkflowRunClaim` split was built
  and then deliberately collapsed back. **The rest of the invariant HOLDS and is enforced by test**:
  no execution-state field (`claim`, `timeoutAt`, `retry`, `waitUntil`, `pauseRequestedAt`,
  `agentRef`) appears in any public model.
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

## Known Current-State Hazards

**Post-Phase-3 (2026-08-21).** Most entries below are struck through — E2/E4/E7 fixed them. They are
kept rather than deleted because the strike-throughs record *how* each was fixed, and a fresh session
that reads a v4-era description as current will chase a bug that no longer exists. **Live hazards are
the two un-struck entries: the `SecurityInterceptor` soft-fail flip, and the security-off identity
decision.** Two accepted limitations sit outside this list: the outbox creation-loss window
(`entity-diff-v4-v5.md` §7) and worker leases (AM-3) — both deliberately deferred, not hazards.

- `SecurityInterceptor` **soft-fails permission checks** (logs and returns `true`) — only
  token-scope mismatch is enforced. Enforcement flips via shadow-logging → token backfill →
  flag → default-on at the major. The riskiest flip in v5. **Shadow telemetry is LIVE
  (E1/E6, 2026-07-23)**: `flow.security.would.deny` counts both the interceptor layer and
  the relationship layer (`layer=relationship` tag) — watch these before the A2 flip.
- ~~The relationship JGraphT singleton (authz bug under N instances)~~ **FIXED (E6,
  2026-07-23)**: direct-query anchored walk, replica-parity proven by test.
- **`check()` ignores the workspace path segment for `global`-scope tokens — NOT an authz hole**
  (raised 2026-08-21 as a security finding; **corrected by the maintainer 2026-08-24 — a global
  token doing everything is what global scope is for, so this is not a privilege escalation**).
  `check()` is `case global: return true;` (`RelationshipService.java:417-419`), returned before
  `hasNodes()`, so the `intermediateType`/`intermediateList` containment arguments are never
  evaluated; `filter()`'s `case global` (`:505-509`) anchors at `ROOT` and still walks. The
  practical effect is that `/workspace/{team}/workflowrun/{id}/...` ignores `{team}` for a global
  token — but the same caller could legitimately reach that run via its own workspace URL, so
  nothing is granted that was not already permitted.
  ~~**The one real defect**: `WorkspaceWorkflowRunService.retry` writes the `HAS_WORKFLOWRUN` edge
  from the **path** team rather than the run's owning workspace~~ **FIXED by F3** (`f46ede717`,
  `3ee5cbb49`): the owner is resolved from the run's own `HAS_WORKFLOWRUN` parent (falling back to
  the Workflow's `HAS_WORKFLOW` parent) **before** the retried run is created, so an unresolvable
  owner refuses with `TEAM_INVALID_REF` rather than throwing after the clone is already queued. The
  other six call sites read or mutate the run and write no ownership, so they were unaffected.
  **Two same-shaped items remain open, both pre-existing (confirmed against `feat-v5-track8`)**:
  `WorkspaceWorkflowService.submit` still writes the edge from the path team, and the engine's
  auto-retry (`WorkflowExecutionService:265`) writes no ownership edge at all — so an auto-retried
  run appears in `/query` (which filters on `workflowRef`) but fails `check()` on `GET /{id}`.
  **Historical note on why it waited**: these were pass-through guards in
  `api/WorkspaceWorkflowRunService`
  over `engine/WorkflowRunService`, and the F1/F2/F3 merge-cleanup collapses the two into one
  service — at which point `team` either becomes authoritative or disappears. Fix the edge-owner
  bug as part of that work rather than patching a line about to be deleted.
- ~~Agent endpoints (`AgentControllerV1`) are unauthenticated~~ **FIXED (E7-4)**: `/api/v1/agent`
  was replaced by `/api/v1/dispatcher` (no dual-serve) behind `DispatcherAuthFilter` — interim
  static bearer token. The first-class Flow dispatcher token (`AuthScope`/`TokenActorKind`,
  `bfd` prefix) shipped with T6-1.
- **`flow.security.enabled=false` — NPEs FIXED, the product decision is still open.**
  `IdentityService.getCurrentPrincipal()`/`getCurrentScope()` and
  `UserService.getCurrentUser()`/`isCurrentUserAdmin()` are now null-safe: they return `null` for
  the no-principal case rather than NPE-ing, mirroring `RelationshipService.check()`'s existing
  no-principal branch. **What remains open is the deliberate decision** — whether security-off
  should present an anonymous/default identity instead of a null one — which is still a
  propose→confirm item in `specifications/authentication.md`. The original diagnosis follows:
  `IdentityService.getCurrentPrincipal()`/`getCurrentScope()` call `token.getPrincipal()` on
  whatever `getCurrentIdentity()` returns without a null check, and `getCurrentIdentity()`
  returns `null` whenever `SecurityContextHolder`'s `Authentication.getDetails()` isn't a
  `Token` — exactly the case with security disabled, since `AuthenticationFilter` (the only
  thing that ever sets it) is `@ConditionalOnProperty(flow.security.enabled=true)`. `GET
  /api/v2/profile` and `/api/v2/context` (`UserService.getCurrentUser`/`isCurrentUserAdmin`)
  NPE as a result — and even a null-safe `IdentityService` alone doesn't fix it, because
  `getUserByID(null)` hits `userRepository.findById(null)`, which Spring Data throws on too.
  Both endpoints back `client-web`'s app-level bootstrap (`useAppContext()`, called from a
  layout loader on every route), so **the webapp cannot render any page** under this
  configuration until fixed — reproducing exactly the "blank page forever" failure mode
  `specifications/authentication.md` already documents for the unauthenticated case, just via
  a different code path. Direct API calls that don't resolve identity (workspace/workflow
  CRUD, run submission) are unaffected. Needs a deliberate decision (anonymous/default
  identity when security is off?), not a drive-by null-check — same propose→confirm treatment
  as the rest of `specifications/authentication.md`.
- ~~Two properties gate security halves~~ **DONE**: unified into a single `flow.security.enabled`,
  whose default derives from `flow.mode` (`STANDALONE`→on, `ENGINE`→off) unless set explicitly —
  see `FlowSecurityProperties`. It gates both `AuthenticationFilter` and the interceptor config.
  (`flow.authorization.basic.password` is unrelated — that's the Basic-auth password.)
- ~~Agent queue claiming is non-atomic (find-then-update race); the claim loser still dispatches~~
  **FIXED (E4-B)**: atomic per-document claim via `findAndModify`, `claim.seq` fencing validated on
  result writes, and the dispatcher receives the *claimed* set. E7-1 fixed the dispatch break so
  agents dispatch claimed runs on `queued`.
- ~~The distributed locks are not actually mutually exclusive~~ **FIXED (E4-F)**: the
  `alturkovic/distributed-lock` dependency is **gone from every pom** — `acquirelock`/`releaselock`
  now use the `task_locks` collection. The engine is lock-free; contended transitions use status-CAS
  `findAndModify`. `specifications/idempotency-audit.md` (F2) is a historical record: E4/E5 closed
  the concurrency core (6/10 ranked items fully fixed; the residue is caller-level run-creation
  idempotency keys, deliberately skipped by the "dropped run-creation dedup" decision).
- ~~Workflow delete is a data-loss bug (cascades with no in-flight guard)~~ **FIXED (E4-F)**:
  delete is a tombstone (`WorkflowStatus.deleted` via CAS in `WorkflowService`); the
  `WorkflowWatcher` sweeps `cancelDeletedWorkflowRuns` then `pruneDeletedWorkflows`, so in-flight
  runs are cancelled through the normal path before anything is pruned.
- ~~Timeout invariant violated in 4 of 6 work classes~~ **FIXED (E2)**: `RestConfig` now gives every
  template real transport timeouts (connect 10s, idle read 60s, pool-lease 10s; the streaming
  template gets minutes), and the `DAGUtility` merge bug is fixed — the per-task timeout is honoured
  when the annotation is absent (`DAGUtility:194-205`). `specifications/timeout-audit.md` is the
  historical analysis.

## Technology Stack (current code state)

| Concern             | Technology                                                                                     |
| ------------------- | ----------------------------------------------------------------------------------------------- |
| Language / Framework| **Java 25, Spring Boot 4.1.0** (Maven monorepo; parent pom + module poms). Virtual threads on (`spring.threads.virtual.enabled=true`). |
| Database            | MongoDB (SpEL-prefixed collections via `flow.mongo.collection.prefix`)                          |
| Scheduling          | **None — JobRunr is fully retired** (grep-clean, E5). Cron fires from the claim-based `ScheduleWatcher` via a `nextFireAt`-advance CAS with bounded `retryCount`; forward calendar unchanged (cron-utils). Quartz gone. |
| Events              | CloudEvents inbound; outbound via the **transactional outbox** (`events_outbox` + `OutboxDispatcher`), sink off by default. In-process = Spring `ApplicationEvent`. |
| Distributed lock    | **None — `alturkovic/distributed-lock` is deleted** (E4-F). Contended transitions use status-CAS `findAndModify`; `acquirelock`/`releaselock` tasks use the `task_locks` collection. |
| Execution runtime   | Tekton on Kubernetes (default dispatcher); local Docker dispatcher planned (Phase 4)            |
| Frontend (in this monorepo, DD-04) | `client-web` — **React 18 + React Router 7.18 (framework mode, SSR)** + IBM Carbon v11 (`@carbon/react` 1.75) + `@boomerang-io/carbon-addons-boomerang-react`. Tests: vitest + MSW (Mirage and Cypress both deleted). See `specifications/design-system.md`. |

## Running Locally

A `docker-compose.yml` at the repo root brings up the full product: Mongo, the one-shot
`service-loader` migration/seed Job (gated with `service_completed_successfully` so
`service-core` never boots against an unmigrated database), `service-core`, `client-web`, and
an `nginx` gateway that puts client-web and service-core behind one origin (service-core has
no CORS support — see `docker/gateway/nginx.conf`). `service-agent` is intentionally not part
of this stack — it drives Tekton on a real Kubernetes cluster; see the compose file's header
comment. Published `boomerangio/*` images are the v4 line and will not match this branch's
API — build locally instead:

**Java 25 is required — check this FIRST if Maven fails.** The default `java` on a dev machine may
still be 21. Maven then fails with `has been compiled by a more recent version of the Java Runtime
(class file version 69.0), this version ... only recognizes ... up to 65.0` (69 = Java 25, 65 = Java
21), which reads like a code error but is purely a toolchain mismatch:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS; verify with: java -version
```

```bash
# service-core and service-loader are Java services — Maven builds the jars, compose does not.
mvn -pl service-core,service-loader -am clean package -DskipTests

# client-web is built from its own tooling (see .github/workflows/ci-web.yml):
cd client-web && pnpm install && pnpm run build && cd ..

docker compose up --build
```

`service-core` is on `http://localhost:7700` directly, `client-web` on `http://localhost:3000`
directly, and the unified browser-facing origin (what E2E and manual UI testing should use) is
`http://localhost:8080`.

Security is off for this stack (`FLOW_SECURITY_ENABLED=false` in `docker-compose.yml`). This
is **deliberate and temporary**, not the target state: there is no login flow yet
(`specifications/authentication.md`), so a secured stack would just show a blank page. The
property still derives from `flow.mode` and defaults on for `standalone`, so it must be set
explicitly to disable it:

```properties
flow.security.enabled=false
```

**E2E**: `e2e/` (repo root, not under `client-web/` — it drives the real UI against a real
backend together, not the webapp in isolation) is a small Playwright suite. `cd e2e && npm ci
&& npx playwright test` once the compose stack above is up. `.github/workflows/ci-e2e.yml`
runs the same thing in CI. The retired Cypress suite under `client-web/cypress` ran against
the webapp's own mocked API (miragejs), was never wired into CI, and is not a substitute.

## Error Response Format

All API errors use `io.boomerang.common.error.RestErrorResponse` (lib-common). Note
`io.boomerang.error.model.ErrorDetail` still exists in `service-agent` only — it is not the API shape:

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
| `specifications/reconciler-analysis.md`   | ✅ Ruled (2026-07-22); implementation status appended (2026-08-21) | Q-117: materialise-all confirmed and **shipped**; industry study (Tekton/Temporal/Airflow/Argo) preserved. **Supersede generations and a distinct reconciler component were ruled OUT and never built** — see the implementation-status section before building to §3. |
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
| `specifications/authentication.md`         | 🔵 Proposed (2026-08-18)     | Unified token exchange for IDPZero + OAuth2-proxy: one convergence point, session token thereafter, ARCHIE's httpOnly-cookie model (sequences after the SSR migration). Mode selection ruled to a config flag — revisit per issue #314. |
| `specifications/entity-diff-v4-v5.md`      | ✅ Reviewed + actioned (2026-08-21) | Field-level v4→v5 entity diff (why each element was added, migration-written residue, anomaly dispositions). **§7 documents the accepted outbox creation-loss window** — no open G2 items remain. |
| `specifications/api-contract-trace.md`     | 🔵 **ACTIVE (2026-08-18)**   | End-to-end webapp↔service-core contract trace (call site → route → service → serialised fields). Live defects, blocked capability, the permissions/auth findings that gate the frontend work, and the maintainer decisions outstanding. |
| `specifications/task-contract-research.md` | 📎 Research record (2026-08-22) | Params-in/results-out across Tekton, Argo, GitHub Actions, GitLab, Airflow, KFP, Conductor, Dagger; executor side-by-side (Tekton/Jobs/Docker/ACA/Lambda); the channel options A–E debated. Inputs to `runtime-contract.md` C2/C3. |
| `specifications/repo-insights-engagement-inputs.md` | 🟡 Inputs — proposed (2026-08-09) | Client-engagement requirements for a future v5 phase: pull-based **executor SPI** (zone queues, payload cap), **evidence/custody ledger** in the task-result contract, **executor portfolio** (K8s Jobs default, VM/MicroVM, CoCo flag), workspace non-retention guarantee, thin LLM task type + **propose/dispose** governed agency, **Embabel** spike. Not ruled — proposed→confirmed when the phase is worked. |

Reference codebases (patterns only — Flow is more complex; adopt the pattern, not the code):
ARCHIE = `/Users/tysonlawrie/Workspaces/tlawrie/asdr` · CHEER =
`/Users/tysonlawrie/Workspaces/cheerdev/cheer.dev` · Frontend =
`/Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web`.

## What To Work On First

**Analysis phases AND Phase 3 are complete (2026-08-21).** Epics E0–E11 in
`specifications/gap-register.md` §3 have all shipped — that section is now a historical
work-order, not a to-do list.

**The two standing gates still apply to EVERY future epic (maintainer-mandated): stop at
phase start and (G1) declare whether the phase touches `DAGUtility` or
`TaskExecutionService` — if yes, enumerate the exact methods/semantics and get review;
(G2) present the phase's data-model changes (fields, indexes, collections, migrations)
for discussion BEFORE implementing them.**

If you have no other instruction, the open work is, in order:

1. **Finish Track 8** (`feat-v5-track8`, unmerged): Wave 5 — `Editor.tsx`'s query cluster is the
   last unconverted surface and the blocker for the schedules cluster, which currently runs
   loaders for reads and react-query for writes. Then merge T8 into `feat-v5`.
2. **Phase 4** — task runtime evolution: AgentRuntime/dispatcher SPI, local Docker runtime,
   Tekton behind the SPI. Nothing blocks it.
3. **The `SecurityInterceptor` enforcement flip** (shadow telemetry has been live since
   2026-07-23 — read `flow.security.would.deny` before flipping). The riskiest change in v5.
4. **DD-03 unified product versioning** — the last unshipped v5 DD.

**Do not re-open** the two accepted limitations unless the trigger conditions in their specs are
met: the outbox creation-loss window (`entity-diff-v4-v5.md` §7) and worker leases (AM-3).
Likewise `reconciler-analysis.md`'s supersede generations describe a capability the product does
not have — build them only if in-place partial re-run becomes a requirement.

**Current state (2026-08-21) — the "where are we" a fresh session should read first:**

**Phase 3 is COMPLETE.** Epics E0–E11 all shipped on `feat-v5`: Phase 0 baseline (Java 25 / Boot
4.1.0), E1 security shadow telemetry, E2 hazard stopgaps, E3 schema/indexes, **E4** execution-model
rebuild (claims/CAS + `claim.seq` fencing, ten `WorkflowWatcher` sweeps, pause as a single admission
gate, transactional outbox, lock-free, tombstone delete), **E5** (JobRunr fully retired; cron fires
from the claim-based `ScheduleWatcher`), **E6** relationship direct-query walk, **E7** dispatcher
contract v2 (DD-06 rename, `/api/v1/dispatcher`, `DispatcherAuthFilter`, virtual threads),
**E8/E10** the DD-02 merge into `service-core`, **E9** callback inversion (no `client` packages
remain; lineage on `initiatedByRef`), **E11/T6** post-merge cleanups.

**Two designed items were ruled OUT rather than built (2026-08-21)** — both follow the "do not build
ahead of proven need" precedent already applied to the retry classes and leases (AM-3):
- **Supersede generations + a distinct reconciler component.** Not built: `retry()` creates a new
  WorkflowRun (two-pointer), so no second live generation exists within a run to disambiguate, and
  "reconcile" is a property of the level-triggered DAG advance rather than a class. See the
  implementation-status section of `specifications/reconciler-analysis.md`.
- **`transitionSeq` / outbox exactly-once.** The outbox has a documented **creation-loss window**: no
  transaction spans the CAS commit and the outbox insert, so a crash between them loses that event.
  Delivery of rows that exist is sound. Blast radius is outbound CloudEvents only — the engine never
  reads the outbox to decide anything. Accepted and documented in `entity-diff-v4-v5.md` §7, which
  also records the design to build and the triggers that would reopen it.

**Track status.** T1 (review-refactor), T2 (D5 + D7 single admission gate), T3/E7 (dispatcher),
T4 (DD-02 merge, E9 egress), T6 (post-merge cleanups) and T7 (DD-04 frontend fold-in) are all
merged to `feat-v5`. **T5 is partial**: DD-01 Team→Workspace and DD-04 shipped; **DD-03 unified
product versioning is the last unshipped v5 DD**. F1 (the `TaskExecutionService` god-class split,
still 1136 lines) was **ruled out of Phase 3 scope** — a large move-only refactor of the most
sensitive class right after the execution model was rebuilt and pinned by new tests.

**T8 (`feat-v5-track8`) is IN PROGRESS and unmerged** — 81 commits ahead of `feat-v5`. The frontend
refactor: React Router 7 framework mode + SSR, MSW replacing MirageJS, Cypress deleted,
`@xyflow/react` v12, and ~10 route clusters moved from react-query onto server `loader`/`action`.
Gates on a quiet tree: **vitest 172 passed / 0 failed (51 files)** — up from 96 passed / 8 failed —
**tsc 27** (from 37), `pnpm build` exit 0, and the SSR bundle boundary verified
(`CORE_SERVICE_INTERNAL_ORIGIN` in `build/server/`, absent from `build/client/`).

**T8 still owes**: Wave 5 (`Editor.tsx`'s query cluster — the blocker for the schedules cluster,
which currently runs loaders for reads and react-query for writes); a sweep of **21 redundant
`revalidator.revalidate()` calls** (React Router already revalidates after a `useFetcher` action —
but exactly 4 of the 25 call sites genuinely need it because they are still on react-query, so a
naive sweep breaks them); and two open frontend defects — schedule labels cannot be set
(`ScheduleCreator`/`ScheduleEditor`, the submit-side block is commented out and would not work if
uncommented) and `WorkflowAdvancedDetail` rendering `boomerang.io/workflow-ref=undefined`.

**Two decisions are queued for the maintainer**, neither blocking: the `SecurityInterceptor`
enforcement flip (shadow telemetry live since 2026-07-23 — read `flow.security.would.deny` first),
and whether `flow.security.enabled=false` should present an anonymous identity (the NPEs are fixed;
the product decision is still propose→confirm in `specifications/authentication.md`). Separately,
`PATCH`/`DELETE /user/{userId}` are gated only on global `user/write`/`user/delete` with **no
self-scoping** — a backend authz gap relevant to the E1 flip.

Item-level detail + dispositions: `specifications/e4-review-findings.md`.

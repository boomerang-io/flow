# Boomerang Flow v5 — Enhancement Specification (Master)

**Status:** 🔴 Active — this is the single authoritative v5 specification  
**Owner:** Claude Code (execution) / Maintainers (review gates)  
**Supersedes:** `framework-baseline.md`, `archie-lessons.md`, `service-consolidation.md`,
`horizontal-scaling.md` — those files may be retained as deep-dive annexes, but this
document is the source of truth for scope, sequencing, and open questions.  
**Last updated:** —

---

## 1. Intent

v5 modernises Boomerang Flow across four dimensions:

1. **Baseline** — every framework and language version at current stable before any
   structural change lands.
2. **Execution engine** — absorb the production-proven ARCHIE queue/claiming/recovery
   patterns, _adapted for the fact that Flow is a far more complex, generic DAG engine_.
3. **Service architecture** — critically evaluate merging `service-flow` and
   `service-engine` under Spring Modulith with a `flow.mode` deployment profile
   (`full` / `engine` / `standalone`), where `engine` mode supports embedding the
   engine headless inside another product.
4. **Task runtime** — move away from Tekton as the default task processor toward a
   pluggable runtime interface with a local Docker implementation and serverless
   targets. Tekton becomes one implementation, not the engine's assumption.

Hard constraints throughout: no regression to v3 patterns; every phase gated on review;
specs reflect verified state, not planned state (deviations recorded honestly).

---

## 2. The ARCHIE Difference — Why Flow Is Harder

ARCHIE proved the execution patterns v5 will adopt, but ARCHIE's workflows are
**fixed pipelines defined in code**: a handful of `WorkflowDefinition` classes whose DAG
shape is known at compile time, whose handlers are hand-written per transition, and
whose restart anchors are a hardcoded list of five.

Boomerang Flow's workflows are **user-authored data**: arbitrary DAGs stored as
versioned workflow revisions, composed from a task catalogue, with user-defined
dependencies, conditional paths, decision nodes, human approval gates, wait-for-event
nodes, and workflow composition (workflows executing other workflows). Every ARCHIE
pattern must therefore be generalised from "code walks a known graph" to
"engine walks a data-defined graph it has never seen before."

Concretely, this changes:

| ARCHIE pattern                                                     | Flow generalisation required                                                                                                                                                                                                       |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `reconcile()` hand-written per WorkflowDefinition                  | ONE generic graph-walk reconciler over the stored DAG revision: compute expected next nodes from dependency edges + edge conditions + join semantics, compare against existing non-SUPERSEDED TaskRuns, create only what's missing |
| Idempotency check: "no non-SUPERSEDED SUCCEEDED run for that step" | Same check, but keyed on graph node id + workflow revision — and it must be correct under conditional edges (a node legitimately skipped is not "missing")                                                                         |
| 5 hardcoded restart anchors                                        | Engine-level primitive: `supersedeFrom(nodeRef)` computes the transitive downstream closure of any node in the user's DAG and supersedes it, then `reconcile()`. Anchors become "any node", surfaced generically in the UI         |
| Pause = flag, watcher skips                                        | Same flag, but must interplay correctly with node types that are ALREADY waiting (approval gates, wait-for-event) — pausing a workflow sitting at an approval must not double-block or lose the approval callback                  |
| Fixed fan-out logic                                                | Join semantics from data: a node with N dependencies needs an explicit all-vs-any completion rule; failure edges vs success edges vs always edges evaluated per the stored link conditions                                         |

**Phase 1 therefore includes a mandatory DAG-semantics inventory** (Q-110 series below)
before any engine change: Claude Code must extract from the codebase the actual set of
node types, edge/link condition semantics, join rules, parameter/result passing
mechanics, and revision-pinning behaviour — the generalised reconciler is designed
against that inventory, not against assumptions.

---

## 3. Phase 0 — Framework Baseline

**Gate: complete before any Phase 3+ implementation lands. Analyses may run in parallel.**

Sequenced as independent, individually-green PRs:

1. Inventory all current versions (parent pom + module poms) into the Version Matrix (§8).
2. Determine targets — **verify current stable at execution time via web/Maven Central;
   do not trust this document's guesses.** Java: latest LTS (virtual threads wanted for
   pollers). Spring Boot: latest stable **that has a compatible Spring Modulith
   release** — Modulith compatibility is the deciding constraint. Quartz, CloudEvents
   SDK, MongoDB driver: latest stable.
3. Do NOT invest upgrade effort in `alturkovic/distributed-lock` — it is expected to be
   deleted (Q-201). Upgrade only if the Boot bump forces it.
4. Produce the breaking-changes list from actual codebase usage (grep, not changelogs):
   jakarta stragglers, Spring Security config style, Spring Data MongoDB changes,
   Quartz job store schema.
5. Test safety net FIRST: green baseline recorded; Testcontainers-based Mongo
   integration tests for submission → claim → transition → completion paths before
   the upgrade, not after.
6. PR order: build tooling → test frameworks/safety net → Java → Boot/Framework/Data →
   remaining deps → virtual threads for pollers (with before/after load comparison).
7. CI matrix and Docker base images updated to match.

---

## 4. Phase 1 — Engine Exploration & Lessons

Two workstreams, both exploration-only (no implementation), verdicts recorded in §8.

### 4.1 DAG Semantics Inventory (new — the "Flow is harder" workstream)

Extract from the engine codebase and record as a living reference:

- **Node types**: full catalogue (templated task, custom task, decision/switch, manual
  approval, wait-for-event, run-workflow / run-scheduled-workflow, start/end, set-status,
  any others) and each type's lifecycle (what does "complete" mean for an approval node?).
- **Edge semantics**: link condition model — success/failure/always paths, decision-match
  expressions, how "skipped" propagates down a not-taken branch.
- **Join semantics**: node with multiple incoming edges — is completion all-dependencies,
  any-dependency, or configurable? How is this stored?
- **Parameter & result flow**: workflow params, task input resolution, task results
  referenced by downstream nodes; **what happens to results under supersede** — when a
  node is retried, which result do downstream consumers see (Q-115)?
- **Revisioning**: running instances pinned to a workflow revision; what happens to
  in-flight runs when a new revision is published?
- **Composition**: run-workflow nodes — parent/child WorkflowRun relationship; do
  pause/cancel/retry cascade to children (Q-116)? Recursion/depth limits?

### 4.2 ARCHIE Lessons (verdict each: ADOPT / ADAPT / REJECT)

Meta-lesson first: ARCHIE's shipped state diverged from its plans (5 restart anchors
not 8; one preload class not three strategies). Do not over-abstract ahead of proven
need; fold planning specs into current-state docs after shipping.

| #    | Lesson                                                                                                                                                                                                                                                                                                                                                             | Exploration task                                                                                                                                                                                                                                                                                                                                 |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| L-01 | **Atomic claiming via `findAndModify` — zero distributed locks.** Claim criteria include `retryAfter` null-or-elapsed; claim writes everything needed to start in one operation.                                                                                                                                                                                   | Map every use of `LockManagerImpl` / `alturkovic`. Can each be an atomic single-document claim? Residual cases decide whether the library survives.                                                                                                                                                                                              |
| L-02 | **Timeouts as reconciliation.** Recovery poller (60s) sweeps claimed-but-incomplete runs past timeout → requeue or fail. No per-run timers. Crashed instance's claims recovered by any survivor.                                                                                                                                                                   | Find how the engine does timeouts today; document today's crash behaviour and recovery latency; verdict on the sweep model.                                                                                                                                                                                                                      |
| L-03 | **Starvation-safe claim page.** FIFO by `createdAt` on compound index; backoff and paused-workflow runs excluded AT THE QUERY.                                                                                                                                                                                                                                     | Can one stuck/paused workflow starve the engine's task selection today? Query-level vs in-memory filtering?                                                                                                                                                                                                                                      |
| L-04 | **Per-failure-class retry.** Rate-limit errors: own backoff base (20s vs 3s) and higher cap (5 vs 2).                                                                                                                                                                                                                                                              | Is engine retry one-policy-fits-all? Needed for the AI Task node regardless.                                                                                                                                                                                                                                                                     |
| L-05 | **Timeout ≥ transport timeout invariant** or healthy long calls get reaped.                                                                                                                                                                                                                                                                                        | Check engine task timeout vs Tekton-poll/HTTP timeouts; document invariant regardless.                                                                                                                                                                                                                                                           |
| L-06 | **Typed queues, per-type concurrency caps, kill switch.** TaskType routing key independent of business run type.                                                                                                                                                                                                                                                   | Is dispatch type-aware? Can slow work head-of-line-block fast work?                                                                                                                                                                                                                                                                              |
| L-07 | **Not everything needs a queue** — semaphore where only concurrency-limiting is needed (ARCHIE's EMBEDDING has no poller).                                                                                                                                                                                                                                         | Design principle for new task classes.                                                                                                                                                                                                                                                                                                           |
| L-08 | **Pause as orthogonal flag** (`pauseRequestedAt`), status preserved, excluded at claim query.                                                                                                                                                                                                                                                                      | Confirm claim query can exclude paused-workflow tasks efficiently (join vs denormalised flag on task record); design interplay with approval/wait nodes (§2).                                                                                                                                                                                    |
| L-09 | **Reconcile-based restart**: supersede target's TaskRuns → RUNNING → `reconcile()`. One code path for retry/restart/resume/crash recovery.                                                                                                                                                                                                                         | For Flow: generic `supersedeFrom(nodeRef)` with transitive-downstream computation over the user DAG (§2). Also ARCHIE's boundary lesson: when a concern stops being per-workflow (their parsing → Library), move its restart surface out.                                                                                                        |
| L-10 | **Idempotent, re-entrant handlers**: re-read state; check transition not already done; versioned writes; never create a step's TaskRun if a non-SUPERSEDED SUCCEEDED one exists.                                                                                                                                                                                   | Audit engine handlers against the four rules; list any not safe to call twice.                                                                                                                                                                                                                                                                   |
| L-11 | **Reuse/caching policy lives in orchestration, not in the execution service.**                                                                                                                                                                                                                                                                                     | Design principle for module boundaries.                                                                                                                                                                                                                                                                                                          |
| L-12 | **Claim ownership — ARCHIE's GAP, not a pattern.** ARCHIE records no claimant; recovery is purely time-based. Insufficient for Flow: side-effectful user-defined tasks + heterogeneous agents. Candidate: `claimedBy`/`claimedAt` + `claimEpoch` (fencing token validated on result writes) + `leaseExpiresAt` with renewal, all written in the same atomic claim. | FIRST locate what the codebase already captures (v4 agent polling identifies itself somehow — check RunEntity/TaskRun worker identity fields). Then: which fields are needed; lease renewal per task class vs fixed timeout; fencing integration with result-write path AND agent external actions (Tekton TaskRun naming, callback validation). |

---

### 4.3 Relationship Implementation Review (against CHEER)

Boomerang Flow's relationship graph — the mechanism linking teams, users, workflows,
tokens, and other objects, and through which access scoping is resolved — is a candidate
for redesign in v5. **CHEER is the reference implementation to evaluate against.**
Its path will be provided locally at execution time — **if the CHEER path is not present
in context, ask for it before starting this workstream; do not proceed from assumptions
about what CHEER contains.**

> **Paths provided (2026-07-22):** CHEER = `/Users/tysonlawrie/Workspaces/walkaboutdev/cheer.dev`;
> ARCHIE = `/Users/tysonlawrie/Workspaces/tlawrie/asdr` (the WorkflowWatcher / queue /
> transition reference for the §4.2 lessons).

Tasks:

1. **Inventory the current implementation**: the relationship entities/collection, the
   graph model (node and edge types), how a relationship is created/removed through the
   object lifecycle, how access checks traverse it, the query patterns it serves (and
   their cost — lookups per API request, indexes used), and known pain points.
2. **Read CHEER** (at the provided path) and characterise its relationship model:
   structure, ownership/hierarchy semantics, how access resolution works, what problems
   its design solves that ours has.
3. **Compare and verdict** (ADOPT / ADAPT / REJECT per pattern) — with explicit
   attention to the two places relationship design intersects the rest of v5:
   - **Module boundaries (Phase 2A task 3)**: does the relationship mechanism belong in
     the `team` module, its own `relationship` module, or dissolved into the modules
     that own each object type?
   - **Engine mode (Phase 2A task 6)**: the engine does not understand relationships
     from an access perspective — the cleaner the relationship layer's seam, the cleaner
     the `default`-workspace resolution in engine mode. Does the CHEER model make that
     seam sharper or blurrier?
4. **Migration implications**: if adopting/adapting, what happens to existing
   relationship data — in-place transform, dual-model transition, or one-way migration?

---

## 5. Phase 2 — Architecture Analyses

### 5.2A Service Consolidation (merge is a HYPOTHESIS, not a decision)

1. **Call graph**: every flow→engine HTTP call — caller, endpoint, payload, sync vs
   fire-and-forget, eventual-consistency tolerance.
2. **`lib-common` audit**: classify each class — truly shared / flow-only / engine-only /
   should-be-split.
3. **Module boundary proposal** (starting hypothesis, to be corrected by evidence):
   `api`, `team`, `engine`, `agent`, `schedule`, `event`, `common` — public API surface
   per module, no reaching into internals.
4. **Interaction classification**: each cross-module interaction → direct call /
   `ApplicationEvent` / CloudEvent (CloudEvent where it must survive the deployment
   boundary in engine mode).
5. **Mode loading matrix**: which modules load in `full` / `engine` / `standalone`
   (via `@ConditionalOnProperty("flow.mode")` — the `flow.authorization.enabled`
   pattern extended). Fill with evidence, not hypothesis.
6. **Embedded engine contract** (`engine` mode = engine embedded in a host product,
   the EY SpaceTech model):
   - Single `default` workspace — workspace resolution is ONE seam, not scattered
     conditionals; document where.
   - No auth module — the protection story stated explicitly (network-only? optional
     static token?), never implicit.
   - Minimal API surface enumerated: submit, status, cancel, retry, events. Everything
     else documented loaded-or-not.
   - Event ingress: engine module's consumer identical whether the event arrived
     in-process (full) or over the wire (engine).
   - Migration path for existing standalone `service-engine` deployments.
7. **CRITICAL REVIEW — should we merge at all?** Argue both sides with evidence from
   tasks 1–6:
   - Runtime isolation: Modulith = compile-time boundaries, NOT JVM isolation. Effect of
     execution workload on API latency percentiles; does virtual-thread adoption change it?
   - Independent scaling profiles: merged model replicates the API layer when scaling
     for execution — material or negligible at realistic sizes?
   - Blast radius: pathological workflow OOMs the API too — is sweep+reconcile
     sufficient mitigation?
   - Release coupling: API-only fix redeploys the engine — real problem given proper
     draining?
   - **The honest alternative**: keep two deployables; fix the actual pain (async
     CloudEvents decoupling + lib-common split + Modulith boundaries WITHIN each
     service). Cost both options.
   - Recommendation must state what evidence would change it. Modulith-inside-current-
     services is a valid stepping stone even if the deployment merge is deferred.
8. **Breaking changes**: API contract, Mongo schema, container packaging — impact on
   full-mode Helm consumers, engine-mode embedders, `flow-loader`.
9. **Migration plan** (conditional on task 7): sequenced, independently-deployable
   steps; no big-bang. If not merging: the alternative plan.
10. **Versioning & release model**: unified product version (one tag → all images build
    as a guaranteed-compatible set) vs today's independent per-service tags — decided
    alongside the merge outcome (Q-213).
11. **Frontend monorepo inclusion**: evaluate moving the web frontend
    (`boomerang-io/flow.client.web`, a.k.a. `flow.web`; local
    `/Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web`) into this repository
    (Q-214). Decided with Q-213 — a unified product version is the strongest argument
    for co-location; an independent frontend cadence the strongest against.

### 5.2B Horizontal Scaling, Locking & Queueing

1. **Lock/ownership inventory**: every lock or ownership assertion — location, resource
   protected, mechanism, safety under N instances, failure mode if not held.
2. **Evaluate DELETING the distributed lock** (not auditing it): per use, can protection
   become an atomic single-document claim? Multi-document operations restructured so one
   claimed owner document gates them? Residual cases decide the library's fate. Record
   current lock failure modes (TTL vs longest op, crash expiry, re-entrancy, contention)
   for migration risk.
3. **Quartz cluster config**: clustering enabled and correct for N instances? Trigger
   dedup? Misfire threshold? Job store / Mongo version compatibility?
4. **CloudEvents consumer under N instances**: every-instance-consumes vs partitioned?
   What prevents duplicate transitions? Target model: partition by `workflowRunId` so
   one instance owns a run's event stream; reconciler is crash-recovery only.
5. **Reconciler under N instances**: N concurrent `reconcile()` — does `@Version`
   prevent double-transition? Leader election vs everyone-reconciles? Cost of N
   instances sweeping every 60s?
6. **Claim ownership model** (from L-12 verdict): locate existing capture first; then
   `claimedBy`/`claimedAt`, `claimEpoch` fencing on result writes (extending to agent
   external actions where needed), `leaseExpiresAt` renewal per task class; crash
   behaviour and recovery latency for each option.
7. **`@Version` coverage**: present on all concurrently-modified entities? Retry at the
   right level? Any `$set` paths bypassing versioned saves?
8. **Queueing model**: type-aware concurrency, starvation-safe paging, per-failure-class
   retry, kill switch (L-03/04/06 verdicts applied). Recommend the v5 queue design.
9. **Gap list + hardening plan**: each gap tagged severity + `BEFORE-MERGE` /
   `WITH-MERGE` / `POST-MERGE`.

---

## 6. Phase 3 — Core Engine Implementation

Per approved Phase 2 proposals, in dependency order:

1. Atomic claiming with ownership metadata (claim + `claimedBy`/`claimedAt`/epoch/lease
   in one `findAndModify`); distributed-lock library deleted if Phase 2 proves it out.
2. Recovery sweep (stuck-run reconciliation) replacing/augmenting current timeout handling.
3. Generic graph-walk `reconcile()` over data-defined DAG revisions (per §2 and the
   Phase 1 DAG inventory) — with the idempotency key on (node id, revision).
4. Pause as orthogonal flag + query-level exclusion + approval/wait-node interplay.
5. `supersedeFrom(nodeRef)` + generic restart-from-node, surfaced in the UI.
6. Typed queue routing with per-type concurrency, starvation-safe paging,
   per-failure-class retry, kill switch.
7. Consolidation execution (merge or the alternative) per the Phase 2 recommendation,
   including `flow.mode` and the embedded-engine contract.

Each item lands behind the Phase 0 baseline, with the Testcontainers suite extended to
cover multi-instance claim races, crash-mid-execution recovery, and fencing rejection.

---

## 7. Phase 4 — Task Runtime Evolution (Away From Tekton-as-Default)

**Goal:** Tekton stops being the engine's assumption and becomes one implementation of
a pluggable runtime interface. New default posture: the engine speaks an
**AgentRuntime SPI**; deployments choose implementations. Targets, in build order:

1. **Local Docker runtime** — runs task container images via the Docker API on a single
   host. This is the `standalone` mode enabler ("try Boomerang Flow in five minutes,
   no Kubernetes") and the SPI's proving ground.
2. **Serverless runtime(s)** — at least one of: Knative (K8s-native serverless),
   cloud container-job services (AWS Fargate/ECS RunTask or Lambda-container,
   GCP Cloud Run Jobs, Azure Container Apps Jobs). Selection driven by the analysis below.
3. **Tekton runtime** — the existing behaviour, refactored behind the SPI, fully
   supported but no longer privileged.

### Analysis tasks (before SPI design freezes)

1. **Tekton usage inventory**: exactly which Tekton features the agent uses today —
   TaskRun spec shape, param passing, results, workspaces/PVCs, step ordering, log
   retrieval, timeout/cancellation, labels/naming. The SPI abstracts what is USED,
   not everything Tekton offers.
2. **Task contract definition**: what a Boomerang task actually requires from a runtime —
   image + command/args, parameter injection (env? files?), secret injection, working
   storage semantics, result reporting (size limits!), log streaming back to the UI,
   exit status mapping, resource requests/limits, timeout, cancellation.
3. **Storage without PVCs**: serverless targets have no shared volumes — define the
   artefact/workspace story (object storage staging? result-only tasks?) and which task
   catalogue entries actually depend on shared workspace semantics today.
4. **Execution constraints per target**: cold start, max execution duration, payload/
   result size limits, image pull behaviour, cost model, concurrency quotas — a
   compatibility matrix per candidate runtime.
5. **Lifecycle mapping**: SPI shape — `submit(taskSpec) → handle`, `status(handle)`,
   `logs(handle)`, `cancel(handle)` — mapped onto each target; how L-12 fencing extends
   to runtime-side actions (deterministic external naming so a re-claimed task can
   detect/adopt or supersede its predecessor's execution).
6. **Migration & deprecation path**: existing Tekton deployments unaffected; default
   docs/quickstart move to local Docker; Tekton documented as the scale-out K8s option.

---

## 8. Consolidated Question Register

All open questions, numbered for tracking. Answer in place; each answer links to
evidence (file/class or measurement).

### Phase 0 — Baseline

- **Q-001** Current version of every managed dependency? (→ Version Matrix)
- **Q-002** Latest stable Java LTS / Spring Boot at execution time — and which Boot has a stable Spring Modulith release (the deciding constraint)?
- **Q-003** Which major-version breaking changes actually touch this codebase (by usage grep)?
- **Q-004** Is integration test coverage sufficient to catch upgrade regressions on submit→claim→transition→complete paths; if not, what Testcontainers suite is needed first?
- **Q-005** Do virtual threads measurably improve poller/agent throughput (before/after)?
- **Q-006** Which dependencies should be removed rather than upgraded?

### Phase 1 — DAG semantics

- **Q-110** Full node-type catalogue and per-type lifecycle (incl. what "complete" means for approval/wait nodes)?
  - ✅ **Answered (2026-07-22):** 17 `TaskType` values (`lib-common/enums/TaskType.java`) in
    four lifecycle families: structural (`start`/`end` — never executed; `start` pre-marked
    succeeded, `end` gates `finishedAll`); worker-executed (`template`,`custom`,`script`,
    `generic` — agent claims, calls back start/end); engine-inline (`decision`,
    `setwfproperty`,`setwfstatus`,`acquirelock`,`releaselock`,`runworkflow`,
    `runscheduledworkflow`,`sleep` — sync in `TaskExecutionService.execute` switch); gates
    (`approval`,`manual`,`eventwait` — TaskRun `waiting`, does not end; complete = external
    resolution via `ActionService`→`endTaskRun` or topic-matched event / `preApproved`).
    `runworkflow` completes on child *submission*, not child completion. `sleep` blocks a
    thread. **Direction:** catalogue will grow — AI task type(s) planned; eventually evaluate
    running the ARCHIE analysis workflows on Flow (the SPI/task-contract work in Phase 4
    should keep that migration in view).
- **Q-111** Edge/link condition semantics (success/failure/always, decision matches) and how "skipped" propagates?
  - ✅ **Answered (2026-07-22):** edges = `WorkflowTaskDependency {taskRef,
    executionCondition (always|success|failure, default always), decisionCondition}`.
    Evaluation is edge-removal over an ephemeral JGraphT graph rebuilt per operation
    (`DAGUtility.updateGraphWithTaskRunStatus/updateTaskInGraph`): a completed
    predecessor's non-matching outgoing edges are deleted; runnability = Dijkstra path from
    `start` survives. Decision branches: newline-separated regexes full-matched against
    `decisionValue`; empty condition = default branch; no match → defaults win. Skipped/
    cancelled predecessors match **only `always` edges**. Skip cascades node-by-node (no
    surviving path → `skipped` + immediately ended → dependants re-evaluated).
  - **Direction:** the per-operation in-memory evaluation is NOT a multi-instance
    correctness problem (stateless recomputation from DB — unlike the relationship
    singleton) but IS a cost and purity problem: `updateGraphWithTaskRunStatus` does one
    `findById` per task per evaluation, and evaluation runs per completion → O(N²) reads
    per run. The v5 reconciler replaces it with a pure function over (revision, one batched
    TaskRun fetch) — same rules, re-evaluable idempotently.
- **Q-112** Join semantics for multi-dependency nodes — all/any/configurable, and where stored?
  - ✅ **Answered — current state (2026-07-22, verified from source):** strict AND, two
    gates: (1) timing — `canExecuteTask` (`TaskExecutionService:978-993`) requires every
    dependency's TaskRun at phase `completed` before queueing; (2) condition validity —
    `allDependenciesValid` (`DAGUtility:283-291`) removes ALL incoming edges if any one
    edge's condition failed → node skipped, skip cascades. Not configurable; no join-rule
    field exists in the data model. The AND/OR switch point is explicitly commented at
    `DAGUtility.java:268` ("Remove or gate this call if you want an OR…") — the
    remembered "one true edge suffices" behaviour is exactly what removing that call
    yields (Dijkstra any-path).
  - **Target: TBD** — configurable join (all/any) is a candidate v5 feature; requires a
    data-model addition (join rule on the node) and a migration/compat decision for
    existing workflows (default = AND preserves behaviour).
- **Q-113** How are running instances pinned to workflow revisions; what happens to in-flight runs on new revision publish?
  - ✅ **Answered — current state (2026-07-22):** pinned at submit — `WorkflowRunEntity`
    holds `workflowRef` + `workflowRevisionRef` + denorm `workflowVersion` (requested or
    latest); TaskRuns denormalise the same refs. New revision publish does not affect
    in-flight runs; retry clones preserve the pin. Storage already follows ADR003's Subset
    Pattern (`WorkflowEntity` parent + `WorkflowRevisionEntity` per version) — the
    community-doc proposal shipped.
  - **Open sub-question (fold into Q-117):** reference vs **snapshot** — the run points at
    the revision document; reconcile therefore depends on that document existing forever.
    Tekton solves this by snapshotting the resolved pipeline spec INTO the run. v5 options:
    embed a DAG snapshot in the WorkflowRun (self-contained reconcile; bigger documents) or
    enforce revision immutability + retention (never delete a revision an unfinalised run
    references — current deletion behaviour unverified). Decide with the reconciler design.
- **Q-114** Can the generic reconciler distinguish "node missing" from "node legitimately skipped by a condition"?
  - ✅ **Answered (2026-07-22, conditional on Q-117):** under the current materialise-all
    model the distinction is explicit — every node has a TaskRun from queue time and
    condition-eliminated nodes carry `RunStatus.skipped`; nothing is ever "missing". The
    question only becomes hard if Q-117 chooses create-on-walk (absent TaskRun is ambiguous
    between not-yet-reached and skipped). Note: `canExecuteTask` treats a *missing*
    dependency TaskRun as satisfied (`TaskExecutionService:985-990`) — harmless under
    materialise-all, a real hazard under create-on-walk; carry into the Q-117 analysis.
- **Q-115** Under supersede/retry of a node, which result do downstream consumers observe?
  - ⚠️ **Open — hazard documented (2026-07-22):** no supersede exists today, so no
    current-state answer. Hazard: result references resolve via
    `findFirstByNameAndWorkflowRunRef` (`ParameterManager` result lookup; same pattern in
    `canExecuteTask` and `finishedAll`) — `findFirst` with no sort or supersede
    discrimination becomes **non-deterministic** the moment Phase 3 introduces
    `supersedeFrom` (multiple TaskRuns per node name in one run). Phase 3 item 5 acceptance
    criterion: result/dependency resolution must be supersede-aware (exclude SUPERSEDED,
    deterministic latest-wins). Maintainer note: open to a redesigned resolution mechanism
    here, not just patching the query.
- **Q-116** Run-workflow composition: parent/child WorkflowRun relationship; do pause/cancel/retry cascade; recursion limits?
  - ✅ **Answered — current state (2026-07-22):** child submitted in-process
    (`trigger=task`), inheriting parent params/annotations/workspaces/timeout/retries;
    parent task **succeeds immediately on submission** (never waits). Linkage only: a
    `RunResult workflowRunRef` on the parent task + a flow-side relationship edge created
    via an engine→flow HTTP callback. **No parent field on the child WorkflowRun; no
    cancel/retry/pause cascade; no recursion/depth limit.** `runscheduledworkflow` =
    engine→flow callback creating a runOnce schedule. Target semantics (wait-for-child
    mode, cascade rules incl. pause (Q-126), a real `parentRef`, depth limit, callback
    inversion per Q-211) are a Phase 2/3 design task.

- **Q-117** Reconciler model: keep materialise-all-TaskRuns (re-gate + supersede) or move
  to create-on-walk? (Includes the Q-113 reference-vs-snapshot sub-question.)
  - ✅ **RULED (2026-07-22): keep materialise-all, extended** with (i) on-demand
    supersede generations (superseded = orthogonal field, never a status;
    at-most-one-live-TaskRun-per-node via partial unique index; attempt history
    retained) and (ii) Airflow-style placeholder+expand for dynamic/AI-task fan-out.
    Create-on-walk rejected — analysis **preserved for future reference** in
    `specifications/reconciler-analysis.md` (maintainer instruction).
    Reference-vs-snapshot: **reference + enforced retention** (no DAG copy per run;
    revision immutability becomes law; deletion guard/soft-delete; supersede re-creation
    copies spec, never re-resolves). This resolves Q-115's mechanism (find-live-by-name)
    and finalises Q-114 (materialise-all keeps it answered; `canExecuteTask` inversion
    sequenced first).
    **🔴 Bug found en route:** workflow delete cascades runs/revisions with NO
    in-flight-run guard — running executions silently orphaned; task-template delete
    equally unguarded. Fix independent of the ruling (added to CLAUDE.md hazards).

### Phase 1 — ARCHIE lessons

- **Q-120** Can every `alturkovic` lock use become an atomic `findAndModify` claim; what are the residual cases? (L-01)
  - ✅ **Answered (2026-07-22):** 8 lock sites, all in service-engine (`LockManager` +
    `WorkflowExecutionService` start/timeout + `TaskExecutionService` start/end/graph-advance/
    approval-flag/param-append). All become atomic claims or status-CAS `findAndModify`.
    **One residual:** the user-facing `acquirelock`/`releaselock` *task types* — a product
    feature, not an internal lock. Redesign as an atomic TTL-lease document (upsert-if-absent
    with expiry) without the library. Library deletable.
- **Q-121** How does the engine handle task timeouts and crash recovery today, and at what recovery latency? (L-02)
  - ✅ **Answered (2026-07-22):** today — workflow timeout: durable JobRunr job (crash-safe,
    any instance executes); task timeout: in-memory `CompletableFuture` (in-code TODO,
    **lost on crash — task crash-recovery latency is ∞**); lazy backstop checks on
    start/end; no recovery sweep exists.
  - **Direction (L-02 = ADOPT):** all run/task timeout + crash recovery moves to a
    **WorkflowWatcher recovery sweep** — claimed-but-incomplete runs past their per-class
    timeout are requeued or failed by ANY instance (reaping is instance-agnostic; lease/
    fencing from Q-129 makes that safe). In-memory task futures are deleted. The durable
    per-run JobRunr timer becomes redundant and is expected to be deleted with JobRunr
    itself if claim-based scheduling lands (Q-227); the sweep is the single timeout path.
- **Q-122** Is task selection starvation-safe (paging, backoff/paused exclusion at the query)? (L-03)
  - ✅ **Answered (2026-07-22):** No — claim queries have no FIFO sort, no backoff fields
    exist, nothing is excluded at the query. **Direction (L-03 = ADOPT):** v5 claim query =
    FIFO on a compound index with `retryAfter` and paused-workflow exclusion in the query
    (Phase 3 item 6).
- **Q-123** Is retry policy per failure class or one-size-fits-all? (L-04)
  - ✅ **Answered (2026-07-22):** one-size-fits-all — run-level retry is clone-and-requeue
    from workflow timeout only. **Direction (L-04 = ADOPT, extended):** three failure
    classes — generic backoff, rate-limit (own base + higher cap), deterministic-terminal
    (no retry) — per the ARCHIE + CHEER shipped evidence.
- **Q-124** Does the run timeout ≥ transport timeout invariant hold? (L-05)
  - ✅ **Answered (2026-07-22): VIOLATED in 4 of 6 work classes** — full inventory +
    per-class chains + v5 constraints in `specifications/timeout-audit.md`. Headlines:
    engine reaps healthy Tekton tasks (guard at exactly T vs agent's T+10 provisioning
    grace); a `DAGUtility` bug discards positive per-task timeouts when the annotation is
    absent; `insecure`/`self`/`external` RestTemplates have **no read timeout at all**
    (engine→flow and engine→agent calls are unbounded); log streams die at Tomcat's 30s
    async default on flow/engine (the agent's 600s setting proves it was hit and fixed on
    one service of three); `sleep`/`acquirelock` are unbounded beneath the guard. The only
    clean chain: agent long-poll (30s hold < 60s read). Five design constraints recorded
    for the sweep timeout classes (grace composes downward + validated at submit; one
    durable mechanism; introduce the missing transport timeouts; long-poll as a named
    pair on async servlet; attempt-fencing so a stale guard can't reap the next attempt).
- **Q-125** Is dispatch type-aware with per-type concurrency; can slow work starve fast work? (L-06)
  - ✅ **Answered (2026-07-22):** routing is coarsely type-aware (agent registers
    `taskTypes`; queue query filters) but there are NO per-type concurrency caps — only
    global thread pools (200/100 threads, 100k queues). Slow work can head-of-line-block.
    **Direction (L-06 = ADOPT):** per-type pollers/caps + kill switch in the v5 queue design.
- **Q-126** Does the claim query exclude paused workflows via join or denormalised flag — and which performs? (L-08)
  - ✅ **Answered + committed (2026-07-22):** no pause exists today (nearest: `waiting`
    status, `awaitingApproval` flag). **Pause is a long-standing roadmap feature and is
    COMMITTED for v5** — and is to be used as a forcing function for the execution-model
    design: `pauseRequestedAt` flag (never a status), the ARCHIE three-chokepoint
    discipline (claim-query exclusion, single transition gate, recovery-sweep skip),
    resume = clear flag + reconcile. Interplay with approval/eventwait nodes per §2. The
    join-vs-denormalised-flag exclusion mechanism is decided (benchmarked) with the claim
    query design in Phase 2B.
- **Q-127** Which transition handlers are NOT safe to call twice? (L-10)
  - ✅ **Answered (2026-07-22): 31 handlers audited — ~20 unsafe; full table + ranked
    Phase 3 gating list in `specifications/idempotency-audit.md`.** Headlines: the
    graph-advance funnel `TaskExecutionService.end` is UNSAFE-RACE *even with today's
    locks* (no re-read inside the lock; duplicate join-task queueing); the agent queue's
    find-then-update returns the FIND result so the claim *loser still dispatches*;
    `runWorkflow`/`runScheduledWorkflow`/`createActionTask`/`retry` create duplicate
    side-effects on every re-call; `processWaitForEventTask` can re-arm a completed
    eventwait (wedged run); the CloudEvent aspects double/phantom-fire. **Structural:
    F2 — today's locks are not actually mutually exclusive** (deterministic token
    supplier: a racing acquirer with the same key-token succeeds; `releaseLock` deletes
    anyone's lock) — the system already runs on best-effort locking. Five cross-cutting
    fixes identified (pass-ids-not-entities, field-scoped atomic writes, unique indexes,
    event outbox, fencing at handler entry).
- **Q-128** Does the codebase already record claim/worker identity (v4 agent polling protocol), and where? (L-12)
  - ✅ **Answered (2026-07-22):** bare `agentRef` (String) on `TaskRunEntity` +
    `WorkflowRunEntity`, written by the (non-atomic) queue claim; `AgentEntity` registry
    (id/name/host/version/taskTypes/lastConnectedDate). No claimedAt, no lease, no epoch —
    and no authentication on the agent protocol.
- **Q-129** Which ownership fields are needed (claimedBy / epoch / lease), lease renewal for which task classes, and how does fencing integrate with result writes and agent external actions? (L-12)
  - ✅ **Working design recorded (2026-07-22; finalise in Phase 2B):** `claimedBy` +
    `claimedAt` + `leaseExpiresAt` + `claimEpoch`, all written in the single atomic claim.
    Lease renewal per task class: long-running worker-executed tasks renew; engine-inline
    tasks need no lease. Fencing validated at result writes (`endTaskRun` rejects a stale
    epoch) and extended to runtime-side execution identity via deterministic external
    naming (Q-406) so a re-claimed task can detect/adopt or supersede its predecessor.

### Phase 1 — Relationship implementation (vs CHEER)

- **Q-130** Current relationship inventory: entities/collection, graph model, lifecycle hooks, access-check traversal, query patterns and their per-request cost, known pain points?
  - ✅ **Answered (2026-07-22):** two collections `rel_nodes`/`rel_edges` (`type:ref` node
    ids, slugs, role-on-edge `data`); 10 `RelationshipType`s, 9 `RelationshipLabel`s.
    Resolution = process-wide JGraphT singleton (`RelationshipGraph`): full-collection load
    at startup, **full rebuild (two `findAll()`s) on EVERY mutation**, `BFSShortestPath`
    over a linear vertex scan per check (O(V·E)). Lifecycle wired inline in ~8 services +
    the engine `runworkflow` callback. Defects: per-replica cache incoherence (**authz bug
    under N instances**), slug-match-ignores-type precedence bug (`getNodeFromGraph`),
    `checkPermissions` result ignored by its caller, TASK-granted-to-all special case.
    Authz is two-layer: `@AuthCriteria` interceptor + service-layer graph checks.
- **Q-131** CHEER's relationship model (read at the provided path — ASK for the path if absent): structure, ownership/hierarchy semantics, access resolution — what does it solve that ours doesn't?
  - ✅ **Answered (2026-07-22):** same two-collection schema, resolved by **direct indexed
    queries** — anchored ≤4-level BFS walk (one edge query + one node batch per level;
    3–9 indexed queries per decision), access = reachability from the principal's node,
    **no cache** (deliberate: CHEER shipped the in-memory graph and removed it for exactly
    our defects — replica staleness, rebuild cost, reverse-lookup bug — with a written
    fitness analysis), permissions resolved onto the token at creation. Solves: replica-safe
    authz by construction, slug scoping + access as one mechanism, cheap renames, uniform
    API for new owned types. Known gaps to design around: no per-request memoisation, no
    domain-write/graph-write transactionality.
- **Q-132** Verdict per pattern (ADOPT/ADAPT/REJECT) — and where does the relationship mechanism belong in the module boundaries (`team`, its own module, or dissolved into object-owning modules)?
  - ✅ **Verdict (2026-07-22): ADAPT.** Keep the existing schema (already matches CHEER's);
    adopt the direct-query anchored walk; **reject the in-memory JGraphT materialisation**
    (per-replica staleness = authz bug); evaluate `$graphLookup` as the escalation path for
    deeper walks (maintainer direction); adopt the hot-data rubric (high-volume operational
    data carries `teamId` directly and skips the graph). Add what CHEER lacks: per-request
    memoisation and a defined write-ordering/orphan-repair story.
  - **Module placement (maintainer decision): the `core` module** — where it already lives
    (`io.boomerang.core`), as platform substrate depended on by team/workflow/schedule/
    integrations; dependency direction stays acyclic. Guardrails: expose only a named
    Modulith interface (the `RelationshipService` API), not internals — `core` must not
    become a god-module; and the mode-loading matrix (Q-205/Q-206) needs sub-`core`
    granularity so `engine` mode can exclude or no-op the relationship layer.
- **Q-133** Does the CHEER model sharpen or blur the engine-mode seam (where relationships don't exist and workspace resolves to `default`)?
  - ✅ **Answered (2026-07-22): sharpens it.** Access-as-anchored-reachability means
    `engine` mode simply doesn't load (or no-ops) the relationship layer and anchors at the
    `default` workspace — one seam, no scattered conditionals. Requirement it imposes: the
    engine must never WRITE relationships either — the `runworkflow` relationship-creation
    callback inverts to an event consumed by the flow side (contingent detail on Q-211,
    invariant either way).
- **Q-134** Migration path for existing relationship data — in-place transform, dual-model transition, or one-way?
  - ✅ **Answered (2026-07-22): near-zero data migration.** Schema and collections already
    match; the rewrite is resolution-strategy + call-site work, method-signature-preserving
    (CHEER precedent: ~165 call sites untouched). Migration = code deployment, not a data
    transform. Pre-cutover check: verify edge `data.role` values align with the role model.

### Phase 2A — Consolidation

> 🟡 **Q-201…Q-214 are answered in `specifications/consolidation-proposal.md` (2026-07-22),
> status PROPOSED — pending maintainer walkthrough.** One-line summaries below; the
> proposal is the authoritative detail.

- **Q-201** Complete flow→engine call graph — which calls tolerate eventual consistency?
  - 🟡 34-method `EngineClient` + 3 engine→flow callbacks mapped; 10 of 13 groups are
    reads/CRUD needing sync responses; eventual-consistency-tolerant: submit kickoff,
    event ingress, both callbacks. → proposal §3.
- **Q-202** `lib-common` classification — what moves where?
  - 🟡 72-class table done: no entity stays shared; contracts → `common`; query DTOs →
    owning module APIs; dead code deleted; model-extends-entity flattening is a hard
    prerequisite. → proposal §2.
- **Q-203** Evidence-based module boundaries and public API per module?
  - 🟡 Nine modules; hypothesis corrected twice: `workflow` (definition domain) splits out
    of `engine`; `integrations` is its own optional module. Six current-code cycles named
    with directed fixes. → proposal §1.
- **Q-204** Which interactions are direct calls vs ApplicationEvents vs CloudEvents (deployment-boundary-surviving)?
  - 🟡 17 groups classified; only event ingress is intrinsically CloudEvent flow↔engine;
    callbacks invert to events; agent protocol is a wire contract behind an SPI-ready API.
    → proposal §3.
- **Q-205** Mode loading matrix — which modules load per mode?
  - 🟡 Full matrix with sub-`core` granularity; mechanism = custom `@ConditionalOnFlowMode`
    on module-root `@Configuration`s; per-mode boot tests as CI gate. → proposal §4.
- **Q-206** Where is the ONE workspace-resolution seam for `default` in engine mode?
  - 🟡 `RunScopeResolver` at the submit boundary — team scoping reaches engine code only
    via 5 absence-tolerant annotation sites; engine collections carry no teamId. → §5.
- **Q-207** What protects the engine-mode API surface with no auth module — stated explicitly?
  - 🟡 Explicit network-only default + optional static bearer token (`flow.security.token`)
    — also closes the unauthenticated agent protocol in ALL modes. → proposal §5.
- **Q-208** Minimal embedded-engine API surface — exactly which endpoints?
  - 🟡 Enumerated: engine V1 lifecycle + agent + task catalogue + definitions; gates via
    direct `endTaskRun`; templates + flow-v2 excluded. → proposal §5.
- **Q-209** Is the engine's event consumer transport-agnostic (in-process vs wire)?
  - 🟡 Core consumer already transport-agnostic; add CE HTTP binding (engine mode) +
    ApplicationEvent path (full). Flagged gap: topic-correlation for hosts that don't
    know run ids (I5). → proposal §5.
- **Q-210** Migration path for existing standalone service-engine embedders (EY)?
  - 🟡 Config/collections/API map unchanged; product image + `flow.mode=engine` with
    `engine@` alias tags; dead callback URLs removed; auth posture explicit/opt-in. → §5.
- **Q-211** MERGE OR NOT: what do latency isolation, scaling-profile waste, blast radius, and release coupling measurements say — versus the async-decoupling + lib-common-split alternative — and what evidence would change the recommendation?
  - ✅ **CONFIRMED (2026-07-22, DD-02): MERGE, sequenced behind the Phase 3 execution rebuild.** Isolation is
    already pierced (sync proxying, shared Mongo, blanket-500 error fidelity); the blast-
    radius argument is retired by Phase 3 itself (bounded claims replace 100k in-memory
    queues); the alternative ends functionally worse (read calls stay on HTTP forever, two
    security surfaces, nothing deleted) — the decision axis is outcome, not effort.
    Falsifiability F1–F5 recorded (F1 = pre-cutover merged-app load test is mandatory).
    → proposal §0/§6.
- **Q-212** Full breaking-changes list (API, schema, packaging) per affected consumer?
  - 🟡 Six-consumer inventory done; six major-boundary hard breaks (headline: permission
    enforcement default-on is the riskiest flip in v5 — tokens never validated by prod
    traffic; sequence shadow→backfill→flag→major). Design constraints locked: no
    PAUSED/SUPERSEDED status values; `flow-service-engine` alias image survives ≥1 major.
    → proposal §7–§8.
- **Q-213** Versioning & release model: unified product version (one tag drives all
  services as a compatible set — the natural end-state if the merge proceeds, and honest
  either way given `lib-common` coupling) vs independent per-service versions? Must
  evaluate the separate-engine consumers (embedded/EY SpaceTech, `engine` mode): do they
  need an independent engine version line even under a unified product version? Also
  reconcile the documented tag format (`<svc>/<semver>` in CLAUDE.md) with the actual CI
  triggers (`<svc>@<semver>` in `.github/workflows/ci-*.yml`) — CI is currently the truth.
  - ✅ **CONFIRMED (2026-07-22, DD-03): unified product version.** One tag builds the
    guaranteed-compatible image set (app + agent); no independent engine version line —
    engine mode is configuration; `engine@` alias tags for a 1–2 release embedder
    deprecation window; CI triggers reworked; CLAUDE.md tag format corrected.
- **Q-214** Move the web frontend (`flow.client.web` / `flow.web`) into this repository?
  Evidence to weigh: it is a polyglot addition (Vite/React 17/pnpm + its own Node
  serving layer in `server/`, node:18 Dockerfile, vitest/Cypress) alongside Maven —
  CI must become path-filtered per stack; its version history is independent (3.12.0
  vs flow 4.x) so a unified version (Q-213) would re-baseline it; co-location puts
  API contract changes + frontend consumption in one PR/branch (the main benefit —
  today an API change spans two repos); against: repo weight (node_modules/pnpm,
  Cypress in CI), different release cadence, and history migration (subtree/filter-repo,
  preserving blame). Also decide the fate of the frontend's separate serving container
  (webapp image) under the deployment-mode model (`full` serves UI; `engine` must not).
  - ✅ **CONFIRMED (2026-07-22, DD-04): fold the frontend in — AFTER the merged image
    ships** (shares no critical path with the merge). v5 major re-baselines its 3.12.x
    history into the unified product version; path-filtered CI per stack; webapp served
    only in `full`/`standalone`; history imported with preservation. The DD-01
    Team→Workspace path rename lands with the frontend's v5 re-baseline.

### Phase 2B — Scaling / locking / queueing

- **Q-220** Complete lock/ownership inventory with per-item safety under N instances?
  - ✅ **Answered (2026-07-23):** 15-mechanism inventory in `gap-register.md` §1 — honest
    column confirms F2 (the alturkovic locks were never mutually exclusive). Residual
    after v5: only the user-facing lock task types, redesigned as TTL-lease documents.
- **Q-221** Is Quartz clustering correctly configured for N instances (trigger dedup, misfire, store compatibility)?
  - ✅ **Answered — question was moot as asked:** Quartz is gone (JobRunr since commit
    `f0a451b4`). Rewritten as the scheduling-substrate question and analysed in
    `queue-design.md` D2; see Q-227.
- **Q-222** CloudEvents consumption under N instances — duplicate-processing prevention; is workflowRunId partitioning achievable with the current transport?
  - ✅ **Answered + RULED (2026-07-23):** duplicate-processing solved by idempotency, not
    partitioning — ingress ledger + CAS-guarded delivery; egress = transition-keyed
    outbox (E1 ruled); **partitioning REJECTED** (E4 ruled — buys only the loser's no-op,
    costs routing/membership/rebalance). `multi-instance-model.md` §2.
- **Q-223** N concurrent reconcilers: converge safely? Leader election needed? Sweep cost at N instances?
  - ✅ **Answered (2026-07-23):** converge — every sweep action terminates in a CAS or
    unique insert; **no leader election anywhere** (it protects nothing once actions are
    CAS-guarded and adds the leader-death stall); cost < 100 index-only ops/s at N=5,
    with a leaderless `_id`-hash sharding valve if active runs grow 100×.
    `multi-instance-model.md` §3, `queue-design.md` D3.
- **Q-224** `@Version` coverage complete; any writes bypassing versioned saves?
  - ✅ **Answered + RULED (2026-07-23):** coverage is zero and stays zero — **CAS-only**
    (CQ-2 ruling): `save()` deleted from execution repositories (typed operations,
    ArchUnit-enforced); transitions = CAS `findAndModify`; fields = scoped atomics.
    `multi-instance-model.md` §1 (W1–W6).
- **Q-225** Recommended v5 queue design (typed claiming, caps, paging, retry classes, kill switch)?
  - ✅ **Answered + RULED (2026-07-23):** `queue-design.md` D1 with maintainer amendments —
    page-then-CAS claiming (M1); **4 queue classes** (worker / inline / waiting / structural
    — gate+wait unified, M2); class-level caps only, per-type override dropped (M3);
    claim-only kill switches; three retry classes with wire-carried typed `failureClass`
    (M4); lease ≠ timeout with batched renewal + fencing (M5); DeferredResult long-poll,
    claims only at dispatch (M6); full unique `(runRef, name, attempt)` generation index
    (M7 — the partial-index formulation was unimplementable). Pause exclusion = two-step
    join (CQ-1).
- **Q-226** Gap list with BEFORE-MERGE / WITH-MERGE / POST-MERGE tags?
  - ✅ **Answered (2026-07-23):** `gap-register.md` §2 — ~70 deduplicated gaps across 9
    categories, cross-checked so nothing from the audits was dropped; 8 cross-document
    conflicts resolved or explicitly held open; §3 is the Phase 3 work-order skeleton
    (E0–E11) incl. the 12 Testcontainers scenarios for Phase 0.
- **Q-227** Timeout/scheduling architecture: recovery sweep vs durable per-run jobs; JobRunr's fate?
  - ✅/⏸️ **Part-ruled (2026-07-23):** timeouts + sleep-resume move to the WorkflowWatcher
    sweep (ruled, Q-121); **schedule-firing substrate DEFERRED** to implementation time
    (migration step 6) — both designs (JobRunr-consolidated vs claim-based due-work on
    the schedule entity) fully documented in `queue-design.md` D2 with the
    kill-at-every-step fire test as the decision gate. E2 (Mongo transactions for the
    outbox) similarly deferred with heal-sweep as the default candidate.

### Phase 4 — Runtime evolution

- **Q-401** Which Tekton features does the agent ACTUALLY use (inventory by code, not docs)?
- **Q-402** The task contract: params, secrets, storage, results (size limits), logs, exit mapping, resources, timeout, cancel — what does every task truly need?
- **Q-403** Which catalogue tasks depend on shared-workspace/PVC semantics, and what is the storage story on serverless targets?
- **Q-404** Compatibility matrix per candidate runtime (cold start, max duration, payload limits, image pull, cost, quotas) — which serverless target first?
- **Q-405** AgentRuntime SPI shape validated against local Docker, one serverless target, AND Tekton-behind-SPI?
- **Q-406** How does claim fencing (Q-129) extend to runtime-side execution identity (deterministic naming, adopt-or-supersede on re-claim)?
- **Q-407** Deprecation path: quickstart on local Docker, Tekton as documented scale-out option — what breaks for existing Tekton deployments (target: nothing)?

---

## 9. Living Sections (To Be Completed by Claude Code)

### Version Matrix (Phase 0)

> ✅ **CONFIRMED by maintainer 2026-07-23** (targets + 5-PR sequence). Web-verified;
> sources in the research record. **The Modulith constraint resolves cleanly: Modulith
> 2.1.0 (GA Jun 2026) tracks Boot 4.1 — no cap needed.** Urgency: Boot 3.4 OSS ended
> Dec 2025; 3.5 OSS ended Jun 2026. The E0 Testcontainers safety net is in place
> (Tier 1 green path passing incl. atop the E2 stopgaps; Tier 2 red-lines demonstrating
> audit defects #28/#29/#25/#12; Tier 3 stubs) — upgrade PRs may proceed.

| Dependency          | Current                         | Target                        | Status |
| ------------------- | ------------------------------- | ----------------------------- | ------ |
| Java                | 21                              | **25 (LTS)** — VT poller-safe (JDK 24 fixed synchronized pinning) | 🟡 |
| Spring Boot         | 3.4.4                           | **4.1.0** (Jackson 3 migration is the bulk of the work) | 🟡 |
| Spring Modulith     | n/a (new)                       | **2.1.0** (tracks Boot 4.1; adoption itself = Phase 3 merge work) | 🟡 |
| Spring Data MongoDB / driver | Boot-managed (5.2.x)   | Boot 4.1-managed (5.6.x — server ≥4.2 through 8.x; Cosmos RU/vCore fine) | 🟡 |
| fabric8 kube/tekton clients | 5.12.4                  | **7.8.0** + Tekton `v1` model migration (v1beta1 models end at 6.14) | 🟡 |
| CloudEvents SDK     | 4.0.1 (flow) / **2.5.0 (engine!)** | **4.0.2** harmonized (verify Jackson-3 coexistence) | 🟡 |
| springdoc           | 2.8.6 / 2.6.0 / **1.6.14 (engine!)** | **3.0.3** (Boot 4 line)   | 🟡 |
| JaCoCo              | 0.8.2 (cannot instrument Java 21+) | **0.8.15**                 | 🟡 |
| Testcontainers      | none (flapdoodle embedded)      | **2.0.5** (`testcontainers-mongodb`, replaces flapdoodle) | 🟡 |
| cron-utils          | 9.2.1                           | 9.2.1 (dormant but stable)    | ✅ |
| JobRunr             | 7.4.1                           | ⏸️ defer (Q-227; note: Modulith 2.1 ships JobRunr event externalization — a point FOR keeping it) | ⏸️ |
| distributed-lock    | 1.4.3                           | ⚠️ delete (Phase 3, post-gating-list) | — |
| lib-scheduling/Quartz | 3.0.3 (Quartz 2.3.2 transitive) | remove (with the scheduling decision) | ⏸️ |
| Stale pins to delete | spring-retry 1.3.3, snakeyaml 1.33 pin, log4j-web 2.17.1, wiremock-jre8, opentracing/jaeger (archived → Micrometer Tracing), assorted Boot-managed pins | per PR 1/PR 3 | 🟡 |

**PR sequence (proposed):** PR1 pom hygiene on current baseline (harmonize CloudEvents/
springdoc/jgrapht, delete managed pins, JaCoCo 0.8.13) → PR2 Boot 3.5.16 stepping stone
(properties migrator, `@MockitoBean`, spring-retry 2.x) → PR3 **Boot 4.1.0 + Java 25**
together (Jackson 3, springdoc 3, Security 7 DSL, JaCoCo 0.8.15, CI/base images) → PR4
fabric8 7.8.0 + Tekton v1 models (service-agent) → PR5 Testcontainers 2.0.5 replacing
flapdoodle + OpenTracing→Micrometer. JobRunr/lock/Quartz explicitly out of Phase 0.

### DAG Semantics Inventory (Phase 1) — Q-110…Q-116

> Empty — populate with evidence (classes, fields, behaviours).

### Relationship Review (Phase 1) — Q-130…Q-134

> Empty — current-implementation inventory, CHEER characterisation, per-pattern
> verdicts, module-boundary placement, engine-mode seam assessment, migration path.
> CHEER path provided: `/Users/tysonlawrie/Workspaces/walkaboutdev/cheer.dev` — unblocked.

### Lessons Verdicts (Phase 1) — ✅ completed 2026-07-22 from the ruled Q-120…Q-129

| Lesson | Boomerang equivalent found | Verdict | Notes |
| ------ | -------------------------- | ------- | ----- |
| L-01 atomic claiming | None — agent queue is non-atomic find-then-bulk-update (race acknowledged in-code); 8 alturkovic lock sites, engine-only; zero `findAndModify` claims exist | **ADOPT** | Q-120: all sites become claims/CAS; residual = `acquirelock`/`releaselock` task types → atomic TTL-lease docs; library deleted |
| L-02 timeouts as reconciliation | Workflow timeout = durable JobRunr job; task timeout = in-memory future (lost on crash, recovery = ∞); **no sweep exists** | **ADOPT** | Q-121: watcher sweep becomes the single timeout/recovery path, instance-agnostic reaping; JobRunr per-run timers deleted (Q-227) |
| L-03 starvation-safe claim page | None — no FIFO sort, no backoff fields, nothing excluded at the query | **ADOPT** | Q-122: FIFO compound index; `retryAfter` + pause exclusion in the query |
| L-04 per-failure-class retry | None — clone-and-requeue from workflow timeout only | **ADOPT + extend** | Q-123: THREE classes (generic / rate-limit / deterministic-terminal) — the third from ARCHIE+CHEER shipped evidence |
| L-05 timeout ≥ transport invariant | Unverified; transport timeouts largely unconfigured | **ADOPT as invariant** | Q-124 audit in flight; per-class timeouts in the sweep design |
| L-06 typed queues, caps, kill switch | Partial — coarse `taskTypes` routing; NO per-type caps (global thread pools only) | **ADOPT** | Q-125: per-type pollers/caps + kill switch in the v5 queue design |
| L-07 not everything needs a queue | n/a (design principle) | **ADOPT** | Semaphore where only concurrency-limiting is needed |
| L-08 pause as orthogonal flag | No pause exists (nearest: `waiting`, `awaitingApproval`) | **ADOPT — committed feature** | Q-126: `pauseRequestedAt`, three-chokepoint discipline; the execution-model forcing function; never a RunStatus value |
| L-09 reconcile-based restart / supersede | None — retry clones the whole run; no supersede | **ADAPT** | Generic `supersedeFrom(nodeRef)` via DAG reachability — NOT ARCHIE's hardcoded anchor sets (9 shipped variants, closure written into predicates) |
| L-10 idempotent re-entrant handlers | Handlers are lock-guarded, not idempotent | **ADOPT — engine-enforced** | Q-127 audit in flight = the Phase 3 gating list; invariants become engine-enforced, not convention |
| L-11 reuse/caching in orchestration, not execution service | n/a (design principle) | **ADOPT** | Module-boundary principle (see consolidation-proposal.md) |
| L-12 claim ownership | Bare `agentRef` written by the (racy) claim; no claimedAt/lease/epoch; no agent auth | **ADAPT — Flow goes beyond ARCHIE** | ARCHIE writes `claimedBy` but never reads it; Q-129 working design adds semantics: `claimedBy`/`claimedAt`/`leaseExpiresAt`/`claimEpoch`, lease renewal per task class, fencing at result writes + runtime naming (Q-406) |

### Consolidation Proposal (Phase 2A)

> 🟡 **Delivered 2026-07-22 as `specifications/consolidation-proposal.md`** (status
> PROPOSED, pending maintainer walkthrough): nine-module architecture + dependency
> diagram + six cycle fixes, 17-row interaction table, mode matrix with
> `@ConditionalOnFlowMode` mechanism, embedded-engine contract (`RunScopeResolver` seam,
> static-token protection, minimal API, CE ingress binding), lib-common disposition,
> breaking changes with six major-boundary hard breaks, **merge recommendation with
> falsifiability statement (F1–F5)**, and the 12-step merge-branch migration plan
> (no-merge branch preserved). 14 judgement calls (J1–J8, I1–I6) await maintainer ruling
> — proposal §10.

### Scaling Assessment (Phase 2B)

> ✅ **Delivered 2026-07-22/23, all decisions ruled or explicitly deferred:**
> - `queue-design.md` — claim fields/indexes/CAS, 4 queue classes, retry classes,
>   leases, long-poll v2, WorkflowWatcher spec (6 sweeps + no-leader cost math),
>   scheduling-substrate comparison (deferred decision).
> - `multi-instance-model.md` — write discipline (CAS-only, save() banned), event
>   processing (outbox / ingress ledger / no broker / no partitioning), reconciler
>   convergence model.
> - `gap-register.md` — Q-220 lock inventory, Q-226 master gap list (~70 items,
>   BEFORE/WITH/POST-MERGE), Phase 3 work-order skeleton E0–E11 + the 12 Phase 0
>   Testcontainers scenarios.
> - `phase2b-decisions.md` — the ruling record (14 decisions: 11 ruled, 3 deferred
>   with documented defaults).

### Runtime Evolution Analysis (Phase 4)

> Empty — Tekton usage inventory, task contract, storage story, runtime compatibility
> matrix, SPI proposal, migration path.

---

## 10. Decisions

> Record as made:
> **DD-NN: [Title]** — Decision / Rationale / Rejected alternatives / Date

**DD-01: Rename Team → Workspace at the v5 major** — Full rename: API paths
(`/api/v2/workspace/{workspace}/…`, old `/team/…` aliased for a deprecation window),
module name (`workspace`), `RelationshipType`/`AuthScope` values (loader changeset),
frontend at its v5 re-baseline. / Rationale: aligns with CHEER, ARCHIE, and the
community docs' own vocabulary; matches the engine-mode "single default workspace"
model; the v5 major is the only natural moment. / Rejected: internal-only rename
(permanent code-vs-API vocabulary split); keeping Team. / 2026-07-22.

**DD-02: Merge service-flow + service-engine into one Spring Modulith deployable** —
`flow.mode = full | engine | standalone`; agent stays a separate deployable; sequenced
BEHIND the Phase 3 execution rebuild with per-mode boot tests as a CI gate and the F1
load test before deployment cutover; falsifiability F1–F5 stays live (the merge aborts
if the evidence flips). Architecture record: `specifications/consolidation-proposal.md`
(incl. the 14 ruled judgement calls: one v2 API surface for all modes, definitions
return platform-side, single Action owner, audit in all modes, workflow-module global
params, named AgentProtocol, Docker runtime as separate agent process for now). /
Rationale: proposal §0/§6 — isolation already pierced, alternative functionally worse. /
Rejected: stay-split + async decoupling (branch B, preserved in the proposal). /
2026-07-22.

**DD-03: Unified product versioning** — one tag builds the compatible image set; no
independent engine version line; `engine@` alias tags for the embedder deprecation
window. / Rejected: per-service tags + compatibility-set manifest. / 2026-07-22.

**DD-05: The merged deployable module is named `service-core`** — executed during E8's
Modulith restructuring (`service-flow` → `service-core` via git mv; engine code moves in
per the nine-module layout; `service-engine` dissolves; CI/image names updated in the
same change). / Rationale: matches the ARCHIE/CHEER `service-core` convention; keeps
"engine" unambiguous (an internal module + a `flow.mode` value, never also the app
name). / Rejected: `service-engine` as the merged name (overloads "engine" three ways);
renaming immediately (churns CI/poms mid-PR-train). / 2026-07-23.

**DD-04: Frontend joins the monorepo after the merged image ships** — v5 re-baseline of
`flow.client.web` 3.12.x into the product version; path-filtered CI; webapp only in
`full`/`standalone` modes. / Rejected: immediate move (would run alongside the heaviest
backend phases); staying separate. / 2026-07-22.

> **Slice C scope amendment (maintainer-ruled, 2026-07-24):** Compare-And-Set winners
> publish domain ApplicationEvents (ids + from/to only); two listeners replace the
> save()-aspects, which are DELETED in C: (1) a CloudEvents bridge invoking
> EventSinkService (slice D upgrades its delivery to the outbox), (2) the audit writer
> (per J4). This closes slice B's interim emission/audit gap in C instead of D. C also
> absorbs: engine-internal finalize for workspace-less completed runs (the redelivery
> bug was accidentally load-bearing).

> ✅ **GATE RULED (2026-07-23) with maintainer amendments — this note supersedes the
> corresponding sections below:**
> 1. **Simple outbox**: rows only on real status changes, written by the CAS winner,
>    ObjectId identity. `transitionSeq`, `lastOutboxedSeq`, and the heal-sweep are
>    DELETED from the design — rare crash-window event loss is accepted and documented
>    (the DB is the source of truth per J4; supersedes multi-instance-model §2b's
>    `(ref, seq)` key).
> 2. **No `tombstonedAt`** — `WorkflowStatus` gains `deleted`; delete = status marker →
>    watcher wind-down → physical purge once all runs finalise (retention knob decided
>    in slice F).
> 3. `task_locks` confirmed as specified (TTL doc per user-facing lock name).
> 4. **Interim worker protection = `timeoutAt` budget only** until E7's renew endpoint;
>    inline classes get full lease-reaping immediately.
> 5. **Working mode**: branch `e4` off `feat-v5`; per-slice review-and-approve before
>    the next slice starts; approved slices merge back. Slices renamed in outcome
>    language: A fresh-reads · B one-winner · C self-healing · D events/pause/queues ·
>    E retry-from-step · F lock-free + safe delete.

# E4 Gate — Execution-Model Rebuild (G1 Touch Analysis + G2 Data-Model Proposal + Slice Plan)

**Status:** 🟡 PROPOSED — the standing-gate stop for E4 (gap-register §3). No E4 code lands
until the maintainer rules on this document. The designs referenced here are already RULED
(multi-instance-model, queue-design, reconciler-analysis, e3-schema-proposal); this document
assembles them into gate form — it does not re-open them.

**E4's 12 internal steps (ruled order, gap-register §3):**
1 B14 (pass ids) → 2 C8 (canExecuteTask inversion) → 3 B1 (completion CAS) →
4 B2/B16 (claim + fencing) → 5 B3/B4 (admission/start CAS) → 6 C1 + timeout classes →
7 B5/B6/B7/B10/B11/B15 (idempotency completions) → 8 B9 (outbox) →
9 C6 pause + C2/C3/C4 (queue) → 10 C7 supersede + C13 fan-out + E5 payload →
11 lock-task TTL-lease + **delete alturkovic** → 12 E1-data tombstone/watcher/retention.
Standing constraint H15: no PAUSED/SUPERSEDED statuses; superseded excluded from default responses.

Naming rule (DD-06): E4 adds no new "agent"-named identifiers where a neutral name works
(`TaskClaimService`, `WorkflowWatcher`, `claim.by` holds an instance/dispatcher id). Existing
`AgentService`/`agentRef` names are rewired internally but renamed only at E7.

---

## Part 1 — G1 Touch Analysis

All refs `service-engine/src/main/java/io/boomerang/engine/`, line numbers verified 2026-07-23
on `feat-v5`. Audit `#n` = idempotency-audit.md §1. Scenario `Sn` = E0's 12 scenarios
(gap-register §3). Verdicts: **REWRITE** (semantics change), **REWIRE** (same semantics, new
primitives/inputs), **KEEP** (unchanged), **DELETE**.

### 1.1 DAGUtility (14 methods — the DAG walk)

The ruled shape (reconciler-analysis, C9): the gating walk becomes a **pure function** over
(pinned revision, ONE batched TaskRun fetch) — no per-node DB re-reads inside graph
evaluation. `createTaskList` becomes the reconcile create-missing half. Edge semantics do not
change.

| Method | Line | Before → After | Verdict | Covered by |
|---|---|---|---|---|
| `createTaskList` | :99 | find-or-create per node via `findFirstByNameAndWorkflowRunRef` + `save()` → create-missing half of `reconcile()`: find-**live**-by-name (C7), insert guarded by the E3 four-field unique index (DuplicateKey = already created), `insert` not `save` (W2); supersede re-creation copies spec at `attempt = prevMax+1`, never re-resolves (Q-117). Timeout-merge (D3, :187-199) unchanged — fixed in E2; `timeoutAt` computed at claim, not here | REWRITE | S1 baseline (`WorkflowRunLifecycleTest` stays green), S4 graph-advance race |
| `canRunTask` | :263 | rebuilds graph + per-task `findById` re-reads → pure predicate over the passed (already-fresh) list; same Dijkstra path-from-start decision | REWIRE (C9) | S1 baseline; decision/skip paths |
| `updateGraphWithTaskRunStatus` | :294 | `findById` per node inside the walk (O(N²) reads) → operates on the batched list only | REWIRE (C9) | S1 baseline |
| `retrieveTaskList` | :93 | `findByWorkflowRunRef` (all generations) → live-generation fetch (`superseded.at` absent) for gating; full fetch retained for the UI/API contract (full graph from TaskRuns) | REWIRE (C7) | S1 baseline; supersede tests (slice E) |
| `validateWorkflow` | :51 | unchanged decision (start→end path exists) over live generations | KEEP (input change only) | S1 |
| `createGraph` | :68 | unchanged (vertices/edges from dependencies) | KEEP | S1 |
| `allDependenciesValid` | :284 | unchanged — strict AND: in-degree must equal dependency count or all incoming edges removed (Q-112) | KEEP | S1, S4 |
| `processDecision` / `calculateMatchedNodes` | :327/:349 | unchanged — regex match per line, default-path fallback when no match (Q-111) | KEEP | S1 |
| `updateTaskInGraph` | :404 | unchanged — executionCondition success/failure/always edge-removal (Q-111) | KEEP | S1 |
| `getTaskByType` / `getOptionalDependency` / `getTaskById` / `getTasksDependants` | :277/:446/:455/:459 | unchanged pure helpers | KEEP | S1 |

Whether the pure walk stays in `DAGUtility` or moves to a `Reconciler` class is a
naming/packaging call made in slice B review — the semantics table above is the contract
either way.

### 1.2 TaskExecutionService (25 methods — the task lifecycle core)

| Method | Line | Audit | Before → After | Verdict |
|---|---|---|---|---|
| `queue` | :78 | #6 | stale-entity phase guard, leaves phase=pending, full-doc saves → entry = re-read by id (B14); **admission CAS** `notstarted/pending → ready` (winner-only); skip decision via inverted gate; system tasks route per queue class (inline classes execute in-process; gate/wait classes never claimed) | REWRITE |
| `start` | :155 | #7 | alturkovic lock, guards on stale entity, execute dispatched after release → re-read by id; fencing validation (claim.by/claimEpoch, B16); no lock; admission moves to execute-entry CAS | REWRITE |
| `execute` | :226 | #8 | **no guard** ("handled in start()") + full-doc save to running → **execution-entry CAS** `ready/queued → running`; null CAS = return (duplicate dispatch harmless); type switch preserved | REWRITE |
| `end` | :323 | #9 | lock but **no re-read inside**; save-then-advance; both racers run finishedAll+executeNextStep → **THE gating fix**: `findAndModify(running → terminal)` returning pre-image, results/duration/statusMessage set inside the CAS; **winner-only advance, advance = reconcile()**; fencing at entry (B16) | REWRITE |
| `timeoutTaskAsync` | :439 | #20 | in-memory `CompletableFuture` delayed timer, lost on crash → **DELETE** — watcher sweep 2c reaps on `timeoutAt` (durable, per-class, epoch-fenced) | DELETE |
| `finishWorkflow` | :870 | #10 | lock-only, full-doc save, double CloudEvent → completion CAS `running → completed` + transitionSeq/outbox row; end-task terminalisation via CAS | REWRITE |
| `executeNextStep` | :905 | #11 | queue decision unguarded (join queued by both parents) → converges on admission CAS; body becomes reconcile-driven | REWRITE |
| `canExecuteTask` | :978 | C8 | **missing dependency TaskRun = satisfied** (`:985-990`) → INVERTED: missing dep = broken invariant, log-and-reconcile, never "satisfied". Step 2, before any supersede code | REWRITE |
| `finishedAll` | :949 | — | `findFirstByNameAndWorkflowRunRef` per dep → find-live-by-name over the batched fetch; same AND semantics | REWIRE |
| `createActionTask` | :792 | #12 | new ActionEntity every call → find-before-create under E3 unique `actions(taskRunRef)`; awaiting-approval write becomes single `$set` (no lock) | REWRITE |
| `saveWorkflowParam` | :840 | #13 | lock + read-modify-append (double-appends even sequentially) → atomic keyed merge by result name (W3; shape CF-6 fixed at implementation) | REWRITE |
| `updatePendingAprovalStatus` | :508 | #14 | level-triggered recompute (correct) + full-doc save (racy) → same recompute, single-field `$set` | REWIRE |
| `runWorkflow` | :633 | #15 | new child WorkflowRun every call → idempotent via child `createdByTaskRunRef` + E3 unique index (DuplicateKey = already created) | REWRITE |
| `runScheduledWorkflow` | :676 | #16 | new schedule every call → idempotency key (taskRunRef) + E3 unique `workflow_schedules(createdByTaskRunRef)` | REWRITE |
| `createSleepTask` | :548 | #17 | `Thread.sleep` (pins thread; zombie completion on crash) → admission sets `waitUntil = min(duration, remaining budget)`; sweep 2d CAS `waiting → succeeded`; never claimed while waiting | REWRITE |
| `processWaitForEventTask` | :764 | #18 | unconditional `status=waiting` save (re-arm race → wedged run) → **arming CAS**: waiting only if running + not-yet-delivered (B11) | REWRITE |
| `acquireTaskLock` / `releaseTaskLock` | :570/:606 | #1 (lock table) | `LockManager` alturkovic (broken F2, unbounded wait, held thread) → atomic TTL-lease doc in `task_locks`; bounded attempts requeued via `retry.after` — never a held thread | REWRITE |
| `updateTaskRunForTopic` | :460 | #19 | correct same-value logic; `save()` → `$set preApproved` (W2) | REWIRE |
| `updateStatusAndSaveTask` | :995 | F1 | the generic status+phase `save()` — the primitive W2 bans → replaced by named repository transitions (`tryTransition`, admission/completion CAS); deleted | DELETE |
| `hasWorkflowRunExceededTimeout` / `hasTaskRunExceededTimeout` | :517/:528 | D10 | inline wall-clock checks scattered on start/end paths → deleted; single path = denormalised `timeoutAt` + watcher sweeps (2a/2c) | DELETE |
| `saveWorkflowStatus` | :539 | — | full-doc save of `statusOverride` → `$set` with `phase $ne finalized` (W3) | REWIRE |
| `processDecision(task)` | :563 | — | unchanged (sets decisionValue/succeeded; persisted by completion CAS) | KEEP |
| `getTaskWorkspaces` | :490 | — | unchanged (latent pre-existing bug: built `TaskWorkspace` never added to list — out of E4 scope, tracked separately) | KEEP |

### 1.3 Secondary G1 surfaces

**WorkflowExecutionService (9 methods):**

| Method | Line | Audit | Before → After |
|---|---|---|---|
| `queue` | :64 | #1 | delegates to createTaskList (unique-index-guarded); ready/pending via CAS |
| `start`/`executeWorkflowAsync` | :95/:143 | #2 | lock + re-read + phase guard → **start CAS** `pending/queued → running` (winner-only first-task queue + `$set timeoutAt = startTime + T*60+5s`); JobRunr per-run timeout job left scheduled but redundant (CF-3: sweep is authoritative from slice C; job deleted in E5) |
| `end` | :115 | #3 | same-value finalize → `$set phase=finalized` + precondition |
| `cancel` | :120 | #4 | **no status/phase check** (stomps succeeded) → CAS `phase ∈ pending/queued/running → completed` |
| `timeout`/`timeoutWorkflow` | :135/:230 | #5 | guard-then-lock race → both auto-retry → CAS returning pre-image; winner-only retry; retry dedup via `(retryOfRef, retryAttempt)` unique; count via real field, not annotation |
| `cancelPendingAndRunningTasks` | :286 | — | re-materialises via createTaskList then ends/queues → cancel CAS per live TaskRun (same path watcher sweep 3 uses) |
| `updateStatusAndSaveWorkflow` | :316 | F1 | the workflow-side banned `save()` primitive → deleted in favour of named CAS transitions |

**WorkflowRunService (13 public methods; 6 touched):** `run`/submit :379 → insert with
`idempotencyKey` honoured (B13, E3 index); `start` :390 → workspaces guarded-merge (#22) +
id-passing; `timeout` :454 → **CAS `phase == running` precondition** (#25 — currently
unconditional stomp); `retry` :474 → real `retryOfRef`/`retryAttempt` fields + unique index,
winner-only (#23); `event` :539 → ingress ledger + per-task CAS delivery, keyed result merge +
per-event delivery markers (#24); `finalize` :419 → precondition added (#26). `get`/`query`/
`insights`/`count` unchanged except default exclusion of superseded generations (H15).
`delete` :527 (run-level) unchanged in E4.

**AgentService (3 methods, all touched):** `register` :56 → upsert on unique `(name, host)`
(#27, E3 index). `getWorkflowQueue` :88 / `getTaskQueue` :148 → the find-then-bulk-update
(`updatePhaseAndAgentRef`) is **replaced by per-candidate `findAndModify` claim** re-checking
full eligibility, setting claim block + `timeoutAt` + `$inc claimEpoch` (#28/#29, B2). This
also fixes the E0-demonstrated **over-claim** (bulk update claims every matching ready TaskRun,
not just the returned page) and terminal-run redelivery (eligibility = ready/pending +
unclaimed only; workflow teardown = its own claimable). Long-poll stays the v1 blocking loop —
`DeferredResult`/protocol v2 is E7 (D5).

**Clients:** `TaskExecutionClient` / `WorkflowExecutionClient` — every signature changes from
entity-passing to **id-passing** (B14, W6); handler entry re-reads by id. This is step 1 and
touches every caller (controllers, TaskRunService, WorkflowRunService).

**Repositories:** `TaskRunRepository`/`WorkflowRunRepository` — `updatePhaseAndAgentRef`
DELETED; `findFirstByNameAndWorkflowRunRef` → find-live-by-name; typed operations added
(`tryTransition`, `claimNext`, `appendResult`, …); **`save()` removed from the execution
repositories** (W2) with an ArchUnit test enforcing it.

**Deleted outright in E4:** `LockManager` (:41/:52/:62 + internals) and the alturkovic
dependency (parent + service-engine pom :107) — slice F, blocked on B1–B10 green;
`locks` collection retained until the POST-MERGE drop (B17). The three
`aspect/*UpdateInterceptor` classes go inert when `save()` disappears; the outbox dispatcher
feeds the existing `EventSinkService` egress from slice D (CF-1 stage 1; ApplicationEvent-fed
egress + flow-side retirement is E9/WITH-MERGE).

### 1.4 What does NOT change (the invariant fence)

- **Q-111 edge semantics:** `executionCondition` (always|success|failure, default always) and
  `decisionCondition` regex-per-line matching with default-path fallback — byte-for-byte the
  same decisions (`updateTaskInGraph`/`processDecision`/`calculateMatchedNodes` KEEP).
- **Q-112 AND-join:** strict AND via both gates (dependency-count edge check + all-deps-
  completed). No any-of/configurable join (F5 unruled — not built).
- **Q-117 materialise-all:** one TaskRun per DAG node at queue time; condition-eliminated
  nodes carry `skipped`; UI renders the full graph from TaskRuns (recorded invariant I3).
- **API surfaces:** all v1 controller routes and request/response models unchanged; agent
  protocol v1 wire shape unchanged (v2 is E7); status remains the only external-facing field;
  phase never exposed; no new RunStatus values (H15).
- **Status/phase vocabulary:** existing enum values untouched; pause = `pauseRequestedAt`
  flag; supersede = orthogonal fields.

### 1.5 E0 safety-net map (which tests flip)

All under `service-engine/src/test/java/io/boomerang/engine/`.

| Test | Today | Flips/enables at | Scenario |
|---|---|---|---|
| `WorkflowRunLifecycleTest.submittedRunCompletesThroughAgentCallbacks` | green | **never flips — must stay green through every slice** (the G1 tripwire) | S1 |
| `AgentQueueClaimTest.twoAgentsCanBothReceiveTheSameReadyTaskRun` | green (demonstrates defect #28/#29) | slice B → invert: exactly one winner | S2 |
| `AgentQueueClaimTest.terminalTaskRunIsRedeliveredToEveryAgentOnEveryPoll` | green (defect #29) | slice B → invert: never redelivered | S2/S3 |
| `ApprovalActionIdempotencyTest.duplicateDispatchCreatesDuplicateApprovalActions` | green (defect #12/#8) | slice B (execution CAS) + slice C (B6 find-before-create) → invert: exactly 1 | S3 |
| `WorkflowRunTimeoutGuardTest.lateTimeoutOverwritesSucceededTerminalStatus` | green (defect #25) | slice C → invert: succeeded preserved | S11 |
| `PendingRecoveryScenariosTest.staleClaimEpochDispatchIsRejected` | @Disabled | slice B → implement + enable | S6 |
| `PendingRecoveryScenariosTest.pausedRunIsExcludedFromClaimUntilResumeReconciles` | @Disabled | slice D → implement + enable | S8 |
| `PendingRecoveryScenariosTest.duplicateEventsAndSubmissionsAreDeduplicated` | @Disabled | slice D (outbox/ingress/B13); topic-correlation refinement E9 | S10 |
| `PendingRecoveryScenariosTest.deletedWorkflowIsTombstonedCancelledAndPruned` | @Disabled | slice F → implement + enable | S9 |
| *(to be authored)* duplicate-dispatch tolerance; graph-advance race (join queued once) | — | slice B | S3, S4 |
| *(to be authored)* crash-mid-execution sweep recovery; per-class reaping (healthy in-budget NOT reaped, durable sleep, approval excluded) | — | slice C | S5, S7 |
| *(to be authored)* supersede/retry-from: one live generation, spec copied, skip closure | — | slice E | S8-adjacent (Q-115/Q-117) |

(S12 relationship parity is E6's — already green.)

---

## Part 2 — G2 Data-Model Proposal

Same form as the ruled E3 proposal; follows its ruled conventions: **nested sub-elements**
`claim{by, at, leaseExpiresAt}` + top-level `claimEpoch`, `retry{after, count, class}`,
`superseded{at, by}`; all new fields absent-as-eligible; dotted-path indexes; loader
changeunits in `service-loader` (Flamingock), continuing from E3's `_0006`.

### 2.1 New fields

**`workflow_runs`:**

| Field | Type | Written by | Absent means |
|---|---|---|---|
| `transitionSeq` | long | `$inc` inside **every** W1 transition CAS | 0 (legacy doc — first CAS creates it at 1) |
| `lastOutboxedSeq` | long | `$max` after the winner's idempotent outbox insert | 0 — heal sweep would synthesize; sweep scans **non-finalised runs only**, so finalised legacy runs are never touched |

**`task_runs`:**

| Field | Type | Notes |
|---|---|---|
| `transitionSeq` | long | **PROPOSED: YES on task_runs too.** W3 tabled it only on WorkflowRunEntity, but W1's discipline covers the three TaskRun CAS gates (they are transitions), and the ruled outbox key `_id = "<refType>:<ref>:<seq>"` explicitly carries a `refType` — TaskRun status CloudEvents exist today (`TaskRunEntityUpdateInterceptor`) and must be outboxed with a per-document seq. Without it, taskrun rows have no unique seq and the key collapses. Same `$inc`-in-every-CAS treatment |
| `lastOutboxedSeq` | long | same heal contract as workflow_runs, per-document |

**`workflows`:**

| Field | Type | Notes |
|---|---|---|
| `tombstonedAt` | Instant | absent = live. `delete` = single `$set` CAS (`tombstonedAt $exists false`) — nothing destroyed. Submit/trigger/schedule reject when present. Watcher sweep 3 cancels unfinalised runs; sweep 4 (disabled until retention ruled) prunes. Orphan backstop (sweep 5) covers genuinely-missing revisions |

### 2.2 New collections

**`events_outbox`** — the transactional outbox (multi-instance-model §2b; replaces
aspect-on-save emission):

```js
{
  _id:        "<refType>:<ref>:<seq>",     // ruled key — refType ∈ {workflowrun, taskrun}; seq = the winning CAS's transitionSeq
  refType, ref, seq,                        // also as fields for querying
  from: { status, phase }, to: { status, phase },
  occurredAt: Instant,
  routing:    { workflowRef, workflowRunRef },  // ids only (W6)
  status:     "pending" | "dispatching" | "sent" | "dead",
  attempts:   int,
  retry:      { after, class },             // E3 nesting convention
  claim:      { by, at, leaseExpiresAt },   // dispatcher claim — same machinery
  claimEpoch: long,
  sentAt:     Instant                       // set on sent; TTL anchor
}
```

Insert is idempotent by `_id` (winner inserts after its CAS; heal sweep re-inserts by the same
key). No multi-document transactions — **the ruled heal-sweep default** (`transitionSeq >
lastOutboxedSeq` synthesizes level-faithful rows; gap transitions coalesce — documented
contract). Dispatcher = stateless claim loop → POST CloudEvent via `EventSinkService` → CAS to
`sent`; `dead` is a status + metric, not another collection.

**`events_ingress`** — dedup/observability ledger (§2a; layer 1 only — correctness is the
per-task delivery CAS):

```js
{ _id: "<source>:<eventId>", payload, receivedAt, status: "received"|"processed", processedAt }
```

DuplicateKey on insert = transport redelivery → 200, done. Crash-after-insert healed by a
re-drive sweep on stale `received`.

**`task_locks`** — the ONE residual lock feature (user-declared `acquirelock`/`releaselock`
cross-workflow mutual exclusion — lock-inventory #1):

```js
{ _id: <key>,                 // team-prefixed lock key — natural unique
  holderTaskRunRef, workflowRunRef, acquiredAt, expiresAt }
```

Acquire = single-doc atomic insert-or-expired-takeover CAS on `expiresAt`; release = delete by
`{_id, holderTaskRunRef}`. Failed acquire → requeue the TaskRun via `retry.after`, bounded
attempts (fixes D8's unbounded wait) — never a held thread. The TTL index on `expiresAt` is
garbage collection only — correctness never depends on Mongo's ~60s TTL sweep timing (the
acquire CAS checks `expiresAt` itself).

### 2.3 Indexes

```js
// events_outbox
{ status:1, occurredAt:1 }                    // dispatch claim page (FIFO)
{ "claim.leaseExpiresAt":1 }  sparse          // dispatcher lease reap
{ sentAt:1 }  TTL (expireAfterSeconds: OPEN — see 2.6)
{ ref:1, seq:1 }                              // per-run ordered reads / consumer support

// events_ingress
{ receivedAt:1 }  TTL (7d proposed — OPEN O3)
{ status:1, receivedAt:1 }                    // re-drive sweep on stale 'received'

// task_locks
{ expiresAt:1 }  TTL (expireAfterSeconds: 0)  // GC only

// workflows
{ tombstonedAt:1 }  sparse                    // watcher sweep 3 page
// (workflow_runs {workflowRef, status} tombstone_sweep index shipped in E3)
```

No index on `transitionSeq`/`lastOutboxedSeq`: the heal sweep is an `$expr`
(`$gt: ["$transitionSeq", "$lastOutboxedSeq"]`) over the **already-indexed non-finalised page**
(status/phase index, count-don't-load, page-capped) — active-run scale, not collection scale.

### 2.4 Loader changeunits (`service-loader`, continuing E3's sequence)

| Unit | Content | Backfill |
|---|---|---|
| `_0007` | create `events_outbox` + its 4 indexes | none |
| `_0008` | create `events_ingress` + TTL/status indexes | none |
| `_0009` | create `task_locks` + TTL index | none (legacy `locks` collection untouched — B17: retained for rollback until POST-MERGE drop H11) |
| `_0010` | `workflows` sparse `{tombstonedAt:1}` | none |

Zero document backfills — every E4 field is absent-as-eligible. All builds background-mode;
all changeunits existence-checked/idempotent (DD-07).

### 2.5 Rollback

- Fields: inert to prior code (neither read nor written) — redeploy prior image, leave them.
- Collections: droppable; outbox/ingress carry no source-of-truth state (level-derivable /
  transport-replayable). `task_locks` rollback restores the alturkovic path only if the prior
  image still ships it — which is why alturkovic deletion is the **last** slice.
- Emission cutover (slice D) is the one behaviour-visible switch: prior image re-activates the
  aspects (they pointcut on `save()`, which the prior image still calls). No data migration in
  either direction.

### 2.6 Genuinely open decision points (nothing ruled is re-opened)

| # | Open point | Proposal on the table |
|---|---|---|
| O-1 | `events_ingress` TTL (ruled-open O3): 7d vs transport redelivery windows (GitHub manual redelivery ≤30d) | 7d, revisit at E9 ingress-binding design |
| O-2 | `events_outbox` sent-row TTL / retention window (observability vs bloat) | 7d TTL on `sentAt`; `dead` rows exempt (no `sentAt`) until manually cleared |
| O-3 | Interim worker-lease policy **before E7's renew endpoint exists** (queue-design 1.7 assumes `POST /claims/renew`; without it a 180s lease reaps healthy long worker tasks) | sweep 2b (lease reap) enabled for **inline class only** in E4; worker-class liveness guarded by `timeoutAt` alone until E7 ships renewal — then 180s/renewed activates |
| O-4 | `task_locks` bounded-attempt default (attempts × retry.after backoff vs author-set timeout param) | honour the task's `timeout` param as total budget; default 3 attempts, generic backoff |
| O-5 | E2-no-transactions final call — **DEFERRED by ruling** with heal-sweep as documented default; E4 implements the default; transactions-where-available may be revisited at implementation per that ruling | implement heal-sweep; record measured coalescing rate for the O-2/Q-227-style closure |
| O-6 | Attempt-history retention (gap E4-data item; sweep 4 ships **disabled**) | separate retention ruling before sweep 4 enables — not blocking E4 merge |

---

## Part 3 — Slice Plan (12 steps → 6 reviewable slices)

Every slice: full engine + flow suites green ("suite-green" = `mvn clean install` incl.
Testcontainers ITs), `WorkflowRunLifecycleTest` untouched-green, and only the listed red-lines
flip (a flip outside the slice's list = unplanned semantic change = stop).

| Slice | Steps | Scope | Files touched (primary) | Tests flip/enable/add | Depends on |
|---|---|---|---|---|---|
| **A — ids + inverted gate** | 1–2 (B14, C8) | Clients pass **ids only**; handler entry = re-read by id (kills the stale-snapshot class). `canExecuteTask` inverted: missing dep = broken invariant, log-and-reconcile. No CAS yet | `TaskExecutionClient`, `WorkflowExecutionClient`, `TaskExecutionService`, `WorkflowExecutionService`, `TaskRunService`, `WorkflowRunService`, controllers | none flip; ADD missing-dep-inversion unit test; lifecycle green | E3 shipped (fields/indexes exist) |
| **B — the CAS core** | 3–5 (B1; B2/B16; B3/B4) | Completion CAS w/ pre-image + winner-only advance = reconcile (`end`); per-doc claim CAS + ownership + `$inc claimEpoch` in `AgentService` (kills over-claim + terminal redelivery); fencing at start/end entry; admission + execution-entry CAS; workflow start CAS. Typed repo ops introduced; `updatePhaseAndAgentRef` deleted | `TaskExecutionService`, `AgentService`, `WorkflowExecutionService`, both repositories, `DAGUtility` (C9 batched-fetch rewiring) | FLIP `AgentQueueClaimTest` (both); ENABLE `staleClaimEpochDispatchIsRejected`; ADD S3 duplicate-dispatch-tolerance + S4 graph-advance-race (join queued once) | A |
| **C — watcher + idempotency completions** | 6–7 (C1 + timeout classes; B5/B6/B7/B10/B11/B15) | `WorkflowWatcher` (sweeps 1, 2a–2d, 5; jittered, every instance, no leader); `timeoutAt` baked at claim; delete in-memory timeout futures + inline wall-clock checks; durable sleep (`waitUntil`); eventwait arming CAS; retry via real fields winner-only; action/child-workflow/schedule idempotency keys; keyed merges (params/results/workspaces); cancel/timeout terminal-protection CAS; `save()` ban completed + ArchUnit test (W2/B15) | `TaskExecutionService`, `WorkflowExecutionService`, `WorkflowRunService`, new `WorkflowWatcher`, repositories | FLIP `WorkflowRunTimeoutGuardTest`, `ApprovalActionIdempotencyTest`; ADD S5 crash-recovery-≤-interval + S7 per-class-reaping (healthy NOT reaped, sleep durable, approval excluded) | B (CAS primitives) |
| **D — outbox + pause + queue classes** | 8–9 (B9; C6 + C2/C3/C4) | `transitionSeq`/`lastOutboxedSeq` `$inc`/`$max` wired into every CAS; `events_outbox` + dispatcher claim loop + heal sweep; `events_ingress` ledger + CAS-guarded delivery on the `event()` path; aspects inert (emission source = outbox); pause chokepoints + two-step-join claim exclusion + resume = clear + reconcile; 4 queue classes (worker/inline/waiting/structural) w/ per-class pollers, retry classes (typed, never string-matched), caps + kill switch (claiming only) | `WorkflowWatcher`, new outbox dispatcher, `EventSinkService` wiring, `AgentService`, `WorkflowRunService.event`, claim/poller config | ENABLE `pausedRunIsExcludedFromClaimUntilResumeReconciles`, `duplicateEventsAndSubmissionsAreDeduplicated` (outbox exactly-once + B13 dedup; topic correlation stays E9); ADD S8 pause+approval interplay | B, C; loader `_0007`/`_0008` |
| **E — supersede + fan-out + payload** | 10 (C7, C13, E5-payload) | Find-live-by-name everywhere; `supersedeFrom(nodeRef)` closure (incl. skipped) + reconcile re-creation copying spec at `attempt+1`; placeholder-expand fan-out (capped, `(name, mapIndex)`); result/param claim-check threshold | `DAGUtility`/reconciler, `TaskExecutionService`, `ParameterManager`, repositories | ADD supersede-generation tests (one live per node, deterministic result resolution Q-115, skip-closure re-run); H15 checked: superseded excluded from default responses | B (CAS), E3 four-field unique index |
| **F — lock deletion + tombstone/watcher** | 11–12 (TTL-lease + **delete alturkovic**; E1-data) | `acquirelock`/`releaselock` on `task_locks` TTL-lease, bounded attempts; **delete `LockManager` + alturkovic dependency (poms)** — gated on B1–B10 all green; `WorkflowService.delete` → tombstone `$set` (E2 stopgap guard retired); watcher sweep 3 (tombstone cancel) + sweep 4 (retention, **ships disabled**) + orphan backstop | `TaskExecutionService`, `LockManager` (delete), poms, `WorkflowService`, `WorkflowWatcher` | ENABLE `deletedWorkflowIsTombstonedCancelledAndPruned`; ADD lock-task contention/bounded-wait test; grep-gate: zero `alturkovic`/`LockManager` references; `locks` collection intact (B17) | B, C, D (every former lock site on CAS); loader `_0009`/`_0010` |

Merge discipline: slices land as separate PRs in order; A+B may share a release; F must be its
own release (rollback isolation for the dependency deletion). After F, E4's exit criteria:
all 9 existing safety-net tests green in inverted/enabled form, the 6 added scenario tests
green, zero distributed-lock code paths, and the G1 invariant fence (§1.4) demonstrably
unchanged.

# Multi-Instance Correctness Model — Q-222 / Q-223 / Q-224 (Phase 2B)

**Status:** ✅ **RULED (2026-07-23)** — outbox (E1), no-broker (E3), no-partitioning (E4),
CAS-only/no-@Version (W4), write discipline confirmed. Exceptions: E2 (no-transactions
vs transactions-where-available) **deferred** to implementation with heal-sweep as the
documented default; W7 pause flag **superseded** by the two-step join ruling.
Scope: how N identical instances of the merged app run safely with **zero distributed
locks and no leader election**. Builds on: idempotency-audit, timeout-audit,
reconciler-analysis, consolidation-proposal §3, Q-121/Q-126/Q-127/Q-129/Q-117 rulings.

**The model in one sentence:** durable truth lives in single documents; every
state-changing action is a winner-take-all single-document CAS or a unique-keyed insert;
in-process events are latency hints only; the level-triggered watcher re-derives any lost
hint from persisted state — therefore N instances doing everything everywhere converge,
and nothing needs a lock, a leader, or a broker.

---

## 1. Write-Discipline Specification (Q-224)

Scope: execution collections (`workflow_runs`, `task_runs`, `actions`, `agents`, new
`events_outbox`, `events_ingress`, `schedule_due`, `task_locks`). Definition/settings CRUD
is outside this discipline.

### W1 — Lifecycle transitions are CAS `findAndModify`, pre-image returned, winner-only side effects

Any write changing `status`, `phase`, claim fields, supersede fields, or
`pauseRequestedAt` MUST be a single-document `findAndModify` whose query encodes the
expected prior state, returning the pre-image (`new: false`). Canonical shape:

```js
db.workflow_runs.findAndModify({
  query:  { _id: runId, status: { $in: ["notstarted","ready"] },
            phase: { $in: ["pending","queued"] }, pauseRequestedAt: { $exists: false } },
  update: { $set: { status: "running", phase: "running", startTime: now },
            $inc: { transitionSeq: 1 } },
  new: false })
```

- `null` return = you lost → no side effects. Pre-image = you won → it supplies
  `(fromStatus, fromPhase, transitionSeq)` for the outbox row and winner-only actions.
- A CAS may `$set` many fields (completion sets status+results+statusMessage+duration in
  one guarded operation — the audit #9 gating fix).
- CAS on claimed work always carries the Q-129 fencing criteria (`claimedBy`/`claimEpoch`).

### W2 — `repository.save()` is BANNED on execution collections

F1 is fixed by removing the write primitive, not versioning it: repositories expose typed
operations only (`insert`, `tryTransition`, `appendResult`, `claimNext`, …); `save()`
deleted from the interface; **ArchUnit/Modulith CI test** enforces it. This also retires
the aspect interceptors (they pointcut on save — audit #30/#31).

### W3 — Field treatment table (every mutable field, one treatment)

**WorkflowRunEntity:** `status/phase` → W1 CAS only · `startTime/duration` → `$set` inside
the owning CAS · **`transitionSeq` (NEW)** → `$inc` in every transition CAS (outbox key +
per-run order) · `results` → atomic keyed-merge (in-place `$set` on `results.name` match,
else `$push` guarded `results.name $ne` — loop; double-append impossible, fixes #13) ·
`annotations` → dotted-path `$set`/`$inc` (retry-count via `$inc`, fixes #5) ·
`workspaces` → guarded `$push` (#22) · `isAwaitingApproval` → level-triggered recompute +
single `$set` (#14) · `statusOverride` → `$set` with `phase $ne finalized` ·
`pauseRequestedAt` → `$set`/`$unset` with non-terminal precondition (Q-126) · claim fields
→ claim CAS only, `$inc claimEpoch` per claim; lease renewal = CAS on
`{_id, claimedBy, claimEpoch}` · everything else (labels, params, timeout, retries, refs,
trigger) → **immutable after insert**.

**TaskRunEntity:** `status/phase` → three named CAS gates only — **admission**
(`notstarted/pending → ready`, #6, kills fan-in double-queue #11), **execution entry**
(`ready/queued → running`, #8, the multiplier fix), **completion** (`running → terminal`
returning pre-image, #9) — plus timeout/cancel CAS with `phase: running` + epoch criteria
(#20/#25) · `results/statusMessage/duration` → inside completion CAS · `preApproved/
decisionValue` → `$set` with status precondition (same-value harmless, #19 pattern) ·
supersede fields → CAS with `supersededAt $exists false` criteria (Q-117) · **`paused`
(NEW, denormalised)** → fan-out `$set` from the pause chokepoint; watcher drift-correction
heals (W7) · claim fields → claim CAS · `spec/dependencies/refs/type/name` → immutable
(spec copied, never re-resolved — Q-117).

### W4 — `@Version` verdict: **CAS-ONLY — `@Version` is NOT introduced** — ✅ RULED (2026-07-22)

After W1+W3, zero multi-field read-modify-writes survive on execution collections.
Rejecting `@Version` outright: (i) it only protects `save()`, which W2 deletes — keeping
it invites `save()` back; (ii) "unchanged since read" is weaker than "currently in state
X" for winner-only side effects; (iii) optimistic-retry loops re-implement imperatively
what CAS criteria state declaratively. **Deliberate divergence from ARCHIE** (which kept
whole-entity save + @Version). OPEN: definition-CRUD concurrent-edit UX may use @Version
— out of scope.

### W5 — Unique-index inventory (insert-as-claim; DuplicateKey = "already done")

```js
task_runs         { workflowRunRef:1, name:1, mapIndex:1 } unique,
                  partial: { supersededAt: { $exists: false } }   // live-generation invariant (#1,#11)
actions           { taskRunRef:1 } unique                          // #12
agents            { name:1, host:1 } unique (register = upsert)    // #27
workflow_runs     { idempotencyKey:1 } unique partial              // #21; schedule fires use "sched:<ref>:<epoch>"
workflow_runs     { createdByTaskRunRef:1 } unique partial         // #15 runworkflow child dedup (real field)
workflow_runs     { retryOfRef:1, retryAttempt:1 } unique partial  // #23/#5 duplicate retry fix
workflow_schedules{ createdByTaskRunRef:1 } unique partial         // #16
events_outbox     _id = "<refType>:<ref>:<seq>"                    // §2b
events_ingress    _id = "<source>:<eventId>" + TTL                 // §2a
schedule_due      { scheduleRef:1, fireTime:1 } unique             // misfire-safe fire dedup
task_locks        { key:1 } unique + TTL                           // acquirelock/releaselock residual (Q-120)
```

Claim compound index (non-unique, FIFO-covering, Q-122/Q-126):
`task_runs { status:1, phase:1, type:1, paused:1, retryAfter:1, creationDate:1 }`.

### W6 — Pass ids, not entities, across every async boundary

Events/queue payloads carry only ids, enums, instants, and `(from, to, seq)`. Handler
entry = re-read by id, then CAS. Reviewer rule: an event record with an entity-typed field
fails review. (CHEER's `TaskRunCompletedEvent` is the reference shape.)

### W7 — Pause exclusion — ❌ SUPERSEDED by maintainer ruling (2026-07-22): the two-step JOIN wins (queue-design.md §1.3). This section retained for the record; the `paused` TaskRun field and drift sweep are NOT built.

Pause CAS on the run → fan-out `$set {paused:true}` on live TaskRuns → claim query
excludes `paused $ne true` at the query (starvation-safe, absent-as-eligible, zero
backfill). Crash-mid-fan-out drift heals via the watcher drift sweep (ARCHIE
`correctDenormDrift` precedent). OPEN O1: confirm vs `$in`-of-paused-ids by benchmark
before Phase 3 item 6.

---

## 2. Event Processing Under N Instances (Q-222)

### 2a — Ingress: ledger + CAS delivery

- **Layer 1 (dedup/observability, not correctness):** insert into `events_ingress`
  (`_id = source:eventId`, payload, `status received|processed`, TTL). DuplicateKey =
  transport redelivery → 200, done. Crash-after-insert healed by a re-drive sweep.
- **Layer 2 (correctness):** delivery is CAS-guarded per target task — waiting eventwait →
  completion CAS (`status:"waiting"` + live criteria; exactly one delivery wins — also
  fixes #18's re-arm race via the arming CAS); not-yet-waiting → `$set preApproved` +
  keyed result merge with per-event `$addToSet` delivery markers (#24 fix). **Topic
  correlation (I5): deliver-to-all matching live tasks; per-task CAS makes broadcast and
  duplicate delivery compose safely.** Correlation key narrows the match set, never
  changes the guard.
- **VERDICT: the spec's "partition by workflowRunId" hypothesis is REJECTED** —
  unnecessary complexity. CAS-guarded delivery makes partitioning buy only the loser
  round-trips (negligible), while costing sticky routing/membership/rebalance — leader
  election's cousin. Every-instance-consumes + CAS is strictly simpler, equally correct.

### 2b — Egress: transactional outbox (replaces the aspect interceptors)

Row per transition: `_id = "<refType>:<ref>:<seq>"` with from/to status+phase,
`occurredAt`, routing denorm (ids only), `status pending|dispatching|sent|dead`,
attempts/retryAfter/failureClass, claim fields, TTL on sent.

- **PICK — key is `(ref, seq)`, NOT `(ref, from, to)`:** supersede/restart legally repeats
  a transition; `seq` (the W1 `$inc transitionSeq` of the winning CAS) is unique by
  construction and gives per-run total order free.
- **PICK — no Mongo multi-document transactions** (would break single-node
  standalone/quickstart): winner CAS → idempotent outbox insert → `$max
  lastOutboxedSeq`. Crash-gap healed by a sweep on `transitionSeq > lastOutboxedSeq`
  synthesizing missing rows from current state; gap transitions **coalesce** (documented
  contract: level-faithful, not edge-complete, under crash).
- **Dispatcher:** stateless claim loop on `pending` rows (same claim machinery), POST
  CloudEvent, CAS to `sent`; lease reap returns to `pending` (at-least-once); three retry
  classes; `dead` is a status + metric, not another collection.
- **PICK — ordering by seq-in-payload, no wire-order guarantee:** consumers sort/dedup by
  `(ref, seq)`; strict in-order dispatch would need a per-run serializer (a mini-leader).

### 2c — In-process ApplicationEvents: at-most-once, and why that's enough (the no-broker argument)

CAS winner publishes ids-only event; listener re-reads and advances; watcher repairs.
The argument, precisely:
1. The CAS commits **before** the event; the event carries nothing not derivable from DB
   — it is a latency hint, not a fact-carrier.
2. The watcher is level-triggered: it observes the *level* the edge produced, not the
   edge.
3. Every watcher action is CAS/unique-insert-guarded, so re-driving is exactly as safe as
   original dispatch; re-driving a non-lost event is a no-op.
4. **Lost event ⇒ bounded extra latency (≤ one sweep interval), never lost work; duplicate
   ⇒ no-op.** Safety = durable CAS state; liveness = events (fast path) ∪ sweep
   (guaranteed path).
5. A broker upgrades edge notifications to at-least-once — still weaker than
   level-triggered recomputation from state (you need the sweep anyway). Durability is
   only owed to facts not in our DB (ingress → ledger) and promises to others (egress →
   outbox). Internal coordination needs neither.

---

## 3. Reconciler Concurrency Model (Q-223)

### 3.1 Sweeps → guarding primitives (convergence proof obligation)

Every sweep action terminates in a §1 CAS or unique insert — any interleaving of N
sweepers yields each effect exactly once:

| Sweep | Predicate (index-covered) | Action → guard |
|---|---|---|
| S1 timeout/lease reap | `running` + lease expired / class budget exceeded, **with claimEpoch fencing** (guard for attempt N can never reap N+1) | requeue CAS `running→ready $inc claimEpoch` / fail CAS; winner-only auto-retry via `(retryOfRef, retryAttempt)` unique |
| S2 stalled-run reconcile | active run with zero non-terminal TaskRuns — **count, don't load** | `reconcile()` → creations guarded by live-partial unique index; admission/transition CAS |
| S3 durable sleep/wait resume | `resumeAt <= now` | completion CAS |
| S4 tombstone cancel | unfinalised runs of tombstoned workflows | cancel CAS per run/task; winner-only agent notify |
| S5 retention prune + drift correction | finalised past retention; denorm mismatch | `prunedAt` marker CAS then idempotent deletes; equality-guarded `$set` |
| Schedule-fire | `schedule_due.fireTime <= now` | claim CAS → insert next due (unique) → submit with idempotencyKey — double-fire structurally impossible |
| Outbox dispatch + heal | `pending`; `transitionSeq > lastOutboxedSeq` | claim CAS; heal-insert idempotent by `_id` |

### 3.2 Cost at N instances

~8 index-covered queries per instance per 60s tick → N=20 ≈ **2.7 QPS** (noise). Worst-case
CAS-loser writes with no jitter: `(N−1) × min(K, pageCap)` per tick — self-extinguishing;
with jitter, overlap ≈ action-latency/interval. Linear in queries, sub-linear in
contention; the knob is sweep interval, not architecture. Hygiene: counts-not-loads,
id projections, `maxTimeMS` + `socketTimeoutMS` (the sweeper must not hang), per-tick page
caps.

### 3.3 Jitter (PICK)

`fixedDelay = interval × uniform(0.75, 1.25)` re-drawn each cycle + startup stagger
`uniform(0, interval)`; per-sweep independent draws. First sweep on
`ApplicationReadyEvent` (ARCHIE). Efficiency only — correctness never depends on ticks
not colliding.

### 3.4 Leader election: REJECTED

(1) Protects nothing — document-granularity mutual exclusion already exists; a leader
only saves the (trivial) loser-CAS cost. (2) Adds the failure mode we're eliminating —
leader death = sweep outage until re-election (today's lost-timer problem reborn one
level up). (3) Split-brain forces per-write fencing anyway — so it's "CAS" vs "CAS plus
elections/heartbeats/lease-collection/library", with F2 showing this codebase already got
lock exclusivity wrong once. (4) ARCHIE ships watcher-on-every-instance, no election.
**Named-and-dismissed temptations:** per-run egress ordering (solved by seq-in-payload);
global retention pruning (idempotent deletes + marker CAS + page caps). **No leader,
anywhere.**

### 3.5 Open items

- **O1** pause-flag vs `$in` claim-query benchmark (confirmation gate for W7).
- **O2** confirm no egress consumer needs edge-complete streams (coalescing tolerance).
- **O3** `events_ingress` TTL (7d proposed) vs transport redelivery windows (GitHub manual
  redelivery ≤30d).
- **O4** per-class sweep intervals/page caps — finalize with the Q-225 queue design.

**Cross-refs:** W1–W7 satisfy audit cross-cutting fixes 1–5 and gating items #1–#30;
§2c+§3.1 discharge consolidation-proposal §3; the model is the correctness precondition
for deleting alturkovic/LockManager and for DD-02's F3 gate.

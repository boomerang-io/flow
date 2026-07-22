# Phase 2B — Open Decisions Walkthrough (detail + examples)

**Purpose:** the readable decision document for the remaining Phase 2B rulings — each
decision with current state, proposed design, worked examples, and trade-offs. The dense
implementation specs live in `queue-design.md` and `multi-instance-model.md`; this
document is for review and ruling.

**Status legend:** ✅ RULED · 🟡 OPEN (awaiting ruling) · ⏸️ DEFERRED

> ✅ **ALL DECISIONS RULED OR DEFERRED (2026-07-23).** This document is now the Phase 2B
> decision record; rulings are reflected in `queue-design.md` / `multi-instance-model.md`
> and the master spec register.

| # | Decision | Status |
|---|---|---|
| 1 | Pause exclusion mechanism | ✅ RULED — two-step join |
| 2 | `@Version` on execution entities | ✅ RULED — CAS-only, no `@Version` |
| 3 | Scheduling substrate (JobRunr vs due-work) | ⏸️ DEFERRED to implementation time (schedule-firing only; timeouts/sleep move to the sweep regardless, per Q-121) |
| E1 | Outbox for outgoing CloudEvents | ✅ RULED — transition-keyed outbox |
| E2 | No Mongo transactions (heal-sweep instead) | ⏸️ DEFERRED — decide at implementation; heal-sweep is the documented default candidate |
| E3 | Internal events at-most-once (no broker) | ✅ RULED — no broker; watcher is the guarantee |
| E4 | No event partitioning | ✅ RULED — no partitioning |
| M1 | Page-then-CAS claiming | ✅ RULED |
| M2 | Queue classes | ✅ RULED — **4 classes**: worker · inline · **waiting** (sleep+approval+manual+eventwait unified — never claimed; resolver = clock/human/event; `timeoutAt` only if author-set) · structural |
| M3 | Kill switches stop claiming only | ✅ RULED — **per-taskType cap override DROPPED**; caps at class level only |
| M4 | Typed failure classification | ✅ RULED — wire-carried `failureClass`, typed exceptions, no string matching |
| M5 | Lease renewal protocol (lease ≠ timeout) | ✅ RULED |
| M6 | Long-poll v2 (async servlet, claim-at-dispatch) | ✅ RULED |
| M7 | Node-generation unique index (full, not partial) | ✅ RULED — full unique `(workflowRunRef, name, attempt)` |

---

## E1 — Outbox for outgoing CloudEvents

### Current

When a run's status changes, an AOP aspect (`WorkflowRunEntityUpdateInterceptor`,
`TaskRunEntityUpdateInterceptor`) intercepts `repository.save()`, reads the **old**
document from the DB, diffs the status, and publishes a CloudEvent to the sink URLs —
**before** the save commits, fire-and-forget, with the dead-letter write commented out.

Three defects:

```
1. DOUBLE-FIRE:  two instances saving the same run both read the same "old" doc
                 → both see a change → two identical CloudEvents sent.
2. PHANTOM:      the aspect runs @Before — publish happens, THEN the save.
                 Save fails → consumers received an event for a transition
                 that never happened.
3. SILENT LOSS:  fire-and-forget POST; sink down = event gone forever.
```

### Proposed

Event emission becomes *data first, delivery second*. The transition CAS winner — and
only the winner — inserts one outbox row:

```js
// events_outbox
{ _id: "workflowrun:6789:7",        // ref + transitionSeq → unique by construction
  ref: "6789", refType: "workflowrun",
  seq: 7, from: "running", to: "succeeded",
  occurredAt: ISODate, status: "pending", attempts: 0 }
```

A dispatcher (the same claim-loop pattern as all other v5 work) claims `pending` rows,
POSTs the CloudEvent, CAS-marks `sent`. Sink down → retried with the standard backoff
classes; permanently failing → `status: "dead"` + a metric (dead-letter is a status, not
another collection).

**Why the key is `(run, seq)` and not `(run, from, to)`:** a superseded/restarted run
*legally repeats* transitions — `running→failed` can occur twice in one run's life, so
`(from, to)` collides. The sequence number (`$inc transitionSeq` inside every transition
CAS) is unique by construction **and** gives consumers per-run ordering: sort and dedup
by `(ref, seq)`.

### Alternative

Keep aspect-style inline emission: simpler code today; all three defects remain and
worsen once CAS retries exist (every retried CAS re-fires the aspect).

---

## E2 — No Mongo transactions; heal crash gaps with a sweep

### The problem

The transition CAS (`workflow_runs`) and the outbox insert (`events_outbox`) touch two
documents. A crash between them loses the event row. The textbook fix — a multi-document
transaction — is rejected because:

- Mongo transactions **require a replica set**. A single-node Mongo (the
  `docker-compose` quickstart, standalone mode) cannot run them — we'd trade the
  five-minute experience for crash-window coverage.
- Transactions bring session management and transient-error retry loops for exactly one
  insert.

### Proposed

The run document carries `lastOutboxedSeq`. The write order is: CAS (which `$inc`s
`transitionSeq`) → outbox insert (idempotent by `_id`) → `$max lastOutboxedSeq`.

```
Crash between CAS and insert:
  run.transitionSeq = 7, run.lastOutboxedSeq = 6      ← gap detected by the sweep
  → sweep synthesizes the missing row from current state.
```

**Documented trade:** if two transitions both fell into one crash gap, the healed row
**coalesces** them (consumers see a `from` jump, with the seq gap making it detectable).
The stream contract is *level-faithful* (you always learn current truth), not
*edge-complete* (every intermediate hop) — the right contract for status sinks.

### Alternative

Transactions-where-available (replica-set deployments) with sweep-heal fallback on
single-node: strongest guarantee, but two code paths to maintain and test forever.

---

## E3 — Internal events at-most-once; the watcher is the guarantee (no broker)

### Context

Post-merge, "something happened, react" between modules becomes an in-JVM
`ApplicationEvent`. It can be lost if the process dies between the DB commit and the
listener running. Does that require a durable broker (Kafka/NATS)?

### Proposed: no — by argument, not by hope

```
Order of operations:
  1. CAS commits to Mongo        ← the FACT is durable
  2. event published             ← a hint: "state changed, look now"
  3. listener advances the DAG   ← every action it takes is itself CAS-guarded

Crash after 1: the fact survived. The watcher's next sweep observes the LEVEL
("run RUNNING with zero non-terminal tasks → a transition was missed") and
re-drives through reconcile().

Lost hint ⇒ latency ≤ one sweep interval (60s), on a crash. Never lost work.
Duplicate hint ⇒ CAS-loser no-op.
```

A broker upgrades hint delivery to durable at-least-once — but the listener can still
crash *mid-action after* durable delivery, so the sweep is required anyway; once the
sweep exists, the broker adds no correctness, only permanent infrastructure in every
deployment mode (including standalone). Durability is owed only to facts **not** in our
DB (inbound events → the ingress ledger) and promises **to others** (outbound → the
outbox, E1).

### Alternative

Add NATS/Kafka for internal events: lower worst-case latency after a crash (no sweep
wait), at the cost of a broker in every deployment including the quickstart.

---

## E4 — No event partitioning

### The hypothesis being rejected

The original spec asked: *"partition CloudEvents consumption by workflowRunId so one
instance owns a run's event stream"* — route all of run #6789's events to instance 3.

### Why it's rejected

What partitioning requires: consistent-hash routing or sticky LB rules, cluster
membership tracking, and **rebalance when an instance dies** (ownership transfer — the
same problem class as leader election, which v5 rejects everywhere else).

What it buys once every delivery is CAS-guarded: the *loser's* wasted `findAndModify` —
one cheap primary-key no-op. It does not even buy ordering: concurrent external events
for one run arrive at the load balancer in arbitrary order regardless of which instance
they land on.

**Proposed:** any instance processes any event; CAS makes duplicates harmless; the
infrastructure stays a plain load balancer.

---

## M1 — Page-then-CAS claiming

### Current

The agent queue endpoints do `find(...)` then a separate bulk `updatePhaseAndAgentRef`,
**and return the find result** — so the loser of the update race still dispatches the
same runs to its agent, and terminal-phase runs are redelivered to every agent on every
poll (audit #28/#29).

### Proposed

```
Each poll cycle, per queue class:
  1. ONE page query: FIFO-sorted eligible work
     { type ∈ T, status:"ready", phase:"pending", claimedBy: absent,
       retryAfter: absent-or-elapsed, workflowRunRef ∉ pausedIds }
     sort { creationDate: 1 }, limit 20
  2. Per candidate: findAndModify by _id, re-checking FULL eligibility,
     setting the claim block + $inc claimEpoch.
     null return = lost the race or no longer eligible → skip to next candidate.
  3. Dispatch = the CLAIMED set only.
```

### Why not the alternative (sort-inside-findAndModify loop, CHEER's shape)

Two reasons: (a) **caps need a budget computed once per cycle** — one running-count then
loop-local decrement; a claim-loop must interleave counting with claiming or over-claim.
(b) **Head-blocking**: with sort inside the claim op, a head candidate this claimant must
*skip* (agent lacks the task type, per-type sub-cap reached, paused mid-cycle) is
returned on every call, blocking everything behind it. A page lets the loop skip and
continue. Atomicity is identical — the per-candidate CAS re-checks everything.

---

## M2 — Queue classes: 17 TaskTypes → 5 classes

### Current

No classes exist. One global thread pool per concern (200/100 threads, 100k-slot
in-memory queues); agents filter coarsely by registered `taskTypes`; slow work
head-of-line-blocks fast work; `sleep` holds a real thread; approval gates sit in the
same machinery as everything else.

### Proposed

| Class | TaskTypes | Claimed? | Executed by | Lease |
|---|---|---|---|---|
| **worker** | template, custom, script, generic (+ workflow provision/teardown) | Yes — via agent protocol | Remote agent (Tekton; Docker/serverless per Phase 4 SPI) | 180s, renewed |
| **inline** | decision, setwfproperty, setwfstatus, acquirelock, releaselock, runworkflow, runscheduledworkflow | Yes — claimed then executed immediately in-process; a slow 10s backstop poller catches orphaned admissions | Engine, bounded by a semaphore (L-07) | 120s fixed |
| **wait** | sleep | Never while waiting — `waitUntil` due-time on the document | Watcher due-sweep ends it | n/a |
| **gate** | approval, manual, eventwait | **Never claimed** — resolved externally (approver action → `endTaskRun` CAS; topic-matched event) | n/a | n/a |
| **structural** | start, end | Never — not executed | n/a | n/a |

Notable consequences: gates being unclaimable is what makes pause-over-approval safe for
free; `acquirelock` waits become bounded `retryAfter` requeues (never a held thread);
the class map is a static engine-side map — no schema change, no migration.

---

## M3 — Kill switches stop claiming only

### Current

No kill switches exist at all. The only way to stop the engine taking work is to stop
the engine — which also stops recovery.

### Proposed

```
flow.queue.enabled                 # global
flow.queue.<class>.enabled         # per class (worker | inline)
flow.queue.<class>.cap             # per-class concurrency
flow.queue.type.<taskType>.cap     # optional per-type override
```

Settable at runtime via the settings collection (property fallback in engine mode).
**Semantics: a disabled queue stops *claiming* only.** Sweeps keep running — timeouts
still reap, expired leases still recover, gates still resolve, tombstone cancellation
still proceeds. New work accumulates in `ready` and drains FIFO on re-enable.

Example: a bad task image is hammering the cluster → `flow.queue.worker.enabled=false`
→ in-flight work finishes or times out normally, nothing new dispatches, recovery
machinery untouched. Re-enable → backlog drains in submission order.

---

## M4 — Typed failure classification (never string matching)

### Current

Flow: no failure classes at all (retry = clone-and-requeue on workflow timeout only).
ARCHIE (the reference) classifies rate-limits like this — **the named anti-pattern**:

```java
// ARCHIE AgentQueueService — walks the cause chain doing:
message.contains("429") || message.contains("rate_limit") ||
message.contains("Too Many Requests") ...
// Works only because ARCHIE owns its single LLM gateway. Breaks on: wrapper
// rewording, locale, any runtime that phrases 429 differently.
```

### Proposed

The classification happens **where the knowledge exists**, and travels as data:

```java
// 1. Agent protocol v2 — the agent maps its runtime's knowledge (HTTP status,
//    container exit codes, Tekton failure reasons) onto the wire:
TaskRunEndRequest { status: failed, results: [...],
                    failureClass: "ratelimit" }   // generic | ratelimit | terminal

// 2. Engine-internal failures — typed exceptions, never message inspection:
throw new TaskFailureException(FailureClass.TERMINAL, BoomerangError.TASK_INVALID_...);
// plus a static BoomerangError-code → class table for legacy throw sites.
```

Retry classes: `generic` 10s×2 (cap 3) · `ratelimit` 30s×2 (cap 6 — ride out the
window) · `terminal` → `failed` immediately with a readable message. Backoff is written
as `retryAfter` on the document — backoff is *claim eligibility*, not a timer.

OPEN sub-item (deferred to Phase 4 / Q-402): the task-author surface for catalogue tasks
to self-classify (exit-code convention in the template spec).

---

## M5 — Lease renewal: lease ≠ timeout

### Current

Nothing. `agentRef` is stamped by the racy bulk update and never read; a crashed agent's
tasks are stuck forever (recovery = ∞); a slow-but-healthy task is indistinguishable
from a dead one.

### Proposed — two independent guards on every claimed task

```
leaseExpiresAt  = "is the claimant ALIVE?"   breach → requeue (attempt consumed)
timeoutAt       = "is the work OVER BUDGET?" breach → fail as timedout
```

Worker-class protocol: lease 180s; the agent sends **one batched renew call every 60s**
for all its claims (`{agentId, claims: [{taskRunId, claimEpoch}]}`); the engine
CAS-renews each (`{_id, claimedBy, claimEpoch}` → `$set leaseExpiresAt`). The response
lists **rejected** ids (stale epoch / not owner) — the agent MUST halt that work and
abandon its external actions: this is how fencing reaches the runtime (Q-406).

Worked example — the 3-hour healthy task: it renews its lease 180 times without issue
and is never touched by the lease sweep; it is judged *only* against `timeoutAt`, which
was computed at claim as `budget + provisioning grace` — so the timeout-audit's class-A
violation (engine reaping healthy in-budget Tekton work at exactly T while the agent
granted T+10) is structurally fixed.

Inline tasks: fixed 120s lease, no renewal — renewal machinery for sub-second work is
over-abstraction (ARCHIE meta-lesson).

---

## M6 — Long-poll v2: async servlet, claims only at dispatch

### Current

```java
// AgentService: a literal while-loop holding a Tomcat request thread
while (elapsed < 30s) {
  work = find(...); if (!work.isEmpty()) { bulkUpdate(...); return work; }
  Thread.sleep(1000);
}
```

Two threads per agent held continuously (~100 agents saturate the default 200-thread
pool); the hold (30s) and the agent's read timeout (60s) are unrelated constants in two
services; and claiming happens *at find time* — a poll that returns work to a dying agent
strands it ("ghost claims").

### Proposed

```
GET /api/agent/v2/queue  →  DeferredResult (no thread held), hold 30s → 204

Completion triggers: work-ready ApplicationEvent nudge (post-merge in-process)
                     + a 5s fallback tick (for clock-eligible retries)

At completion: run the M1 page for this agent's types ∩ capacity → per-candidate
claim CAS → respond with the CLAIMED batch + {claimEpoch, leaseExpiresAt,
renewIntervalSeconds, holdSeconds} per item.
```

Key properties: **claims happen only at dispatch** — a timed-out poll claims nothing, so
a dead agent can never strand unstarted work; all timing numbers come from the engine's
registration handshake (`readTimeout = 2 × hold` — the invariant is co-located code, not
constants that drift); registration becomes an upsert on unique `(name, host)` (no more
duplicate agent records per restart). Sits behind the named `AgentProtocol` (ruled I6),
with the in-process binding interface-shaped for standalone later (ruled J8).

---

## M7 — Node-generation uniqueness: full unique index

### The correction

The Q-117 ruling specified *"at most one live TaskRun per (run, node) via a partial
unique index where `supersededAt` is absent"* — **that exact form is unimplementable**:
MongoDB `partialFilterExpression` supports `$exists: true` but **not** `$exists: false`.

### Proposed replacement

```js
db.task_runs.createIndex(
  { workflowRunRef: 1, name: 1, attempt: 1 },   // attempt: absent(gen 0), 1, 2, ...
  { unique: true })
```

Same race protection: two racing reconcilers computing the same `prevMax+1` for a
superseded node collide — the second insert gets `DuplicateKeyException` = "already
created" (exactly the audit #1 fix). "At most one **live**" is then enforced by the
supersede CAS ordering (a generation must be marked superseded before its successor is
inserted) plus a reconcile invariant assert. One bounded migration prerequisite: a
loader pre-changeset dedupes any existing `(workflowRunRef, name)` duplicates (the audit
says they can exist today).

---

## How to rule

Reply with rulings per item (`E1 confirm`, `M3 confirm`, `M5 discuss …`), or ask for the
wizard again — each item above is self-contained. Ruled items get recorded in
`queue-design.md` / `multi-instance-model.md` and the master spec register; the Phase 2B
closeout (register Q-220–Q-227 + Scaling Assessment living section) completes when all
items are ruled.

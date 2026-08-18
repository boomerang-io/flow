# v5 Claim/Queue Design, Scheduling Substrate & WorkflowWatcher Spec — Q-225 / Q-221 / Q-227 (Phase 2B)

**Status:** ✅ **RULED (2026-07-23)** with maintainer amendments: **4 queue classes**
(gate + wait unified into `waiting` — sleep is a gate resolved by the clock; gates get
`timeoutAt` only if author-set); **per-taskType cap override dropped** (class-level caps
only — delete `flow.queue.type.<taskType>.cap` from §1.5); pause = single admission gate
(no claim filter — see §1.3); scheduling substrate (D2) deferred to implementation time. Finalises Q-129 and
Q-126.

> ## ⚠️ Implementation status (verified against code 2026-08-18)
>
> **This document is the ruled design. Parts of it were never built.** Read this block before
> trusting any section below — a review that assumed the spec described shipped code nearly drew
> the wrong conclusion twice.
>
> **Built and correct:** atomic claiming (single `findAndModify`, full eligibility re-checked at
> claim time, losers skipped); `claim.seq` fencing **written AND read** (`claimantIsValid` at
> start/end); pause as a single admission gate at `TaskExecutionService.queue`; `pauseRequestedAt`
> as a flag with `RunStatus` still a closed 10-value enum; the `ScheduleWatcher` cron substrate.
>
> **NOT built — the spec is aspirational here:**
> - **§1.6 Retry classes.** Only a single generic exponential backoff exists (`Backoff`, 10s base,
>   5m ceiling — matching the "generic" row). There is **no `retryClass` field**, no `ratelimit`
>   class, and no deterministic-terminal classification; `RunRetry` carries only `after`/`count`.
>   "Terminal" in practice means the type isn't requeueable or the 3-retry budget is spent. An
>   agent-reported *failure* (as opposed to a timeout) is never retried at all.
>   **Disposition: correct the doc, don't build it** — nothing has demanded these classes, and
>   building them now is over-abstraction ahead of proven need.
> - **§1.5 Caps / kill switch.** No cap enforcement exists anywhere; `findClaimable` filters only by
>   the requesting agent's registered task types. The kill switch does not exist either.
>   **Disposition: correct the doc.** Load testing is what should reopen this.
> - **§1.7 Leases.** `leaseExpiresAt` is declared and indexed (`lease_sweep`) but **written
>   nowhere** — only ever `unset` — so that index is permanently empty. There is no renew endpoint.
>   Crash recovery relies entirely on the durable `timeoutAt` deadline. Safe, but slower than the
>   designed 180s lease + 60s renewal.
>   **Disposition: keep as-is** unless worker-crash latency proves to matter.
> - **§D3 #5 Orphan backstop.** Did not exist in any form; **now being implemented**, because a real
>   bug sat behind it (below).
>
> **The bug the missing backstop hid — and it is a DRIFT FROM THIS DESIGN, not a missing feature.**
> §1.1 already specifies `timeoutAt` as `claimedAt + effectiveTimeout + class grace`, and §1.2
> already says the claim CAS "sets phase …, claim block, **`timeoutAt`**" — the whole point being to
> compute the effective timeout **once at claim**, explicitly to fix the `DAGUtility` merge-bug site.
> The code never did this: `tryClaim` set `phase → queued` and the claim fields but left `timeoutAt`
> to `tryStartExecution`. Since `findReapable` selects on `timeoutAt <= now`, a task claimed but
> never started was invisible to every sweep — and `cancelPendingAndRunningTasks` covered only
> `running`/`pending`, so cancelling the parent run did not reach it either. A dispatcher dying
> between claim and execute left a permanently stuck run with no API path out.
>
> **Interim fix applied:** a *provisional* 10-minute deadline at claim
> (`EngineConstants.CLAIM_TIMEOUT_MINUTES`), re-baked to the real budget at start, plus the orphan
> sweep and a cancel cascade extended to `queued`. **This is weaker than the design**: it introduces
> an arbitrary constant to justify, computes the deadline twice, and leaves a window where a
> healthy-but-slow dispatcher can be reaped. Bringing `tryClaim` in line with §1.1 — set the real
> `claimedAt + effectiveTimeout + grace` deadline at claim, from the timeout already on the claim
> pre-image — removes the constant, the second computation, and the window. **Pending maintainer
> decision.**
>
> **Index authority (F4):** the entity `@Indexed`/`@CompoundIndex` annotations are **inert** —
> `spring.data.mongodb.auto-index-creation=false` is pinned. The loader
> (`_0017__RunIndexes`) is the sole authority and **does** provision the full FIFO claim indexes
> including `creationDate`. The annotations remain in 12 files and have drifted; reading them
> instead of the migration gives a wrong answer.

## D1. Claim/Queue Design (Q-225)

### 1.1 Claim fields (flat, absent-as-eligible)

**TaskRunEntity (new):** `claimedBy` (engine instanceId or agentRef — replaces `agentRef`
as ownership; `agentRef` kept as protocol-v1 alias until v1 retires) · `claimedAt` ·
`leaseExpiresAt` (liveness guard) · `claimEpoch` (`$inc` on every claim, **never reset** —
survives requeue; validated at start/end/renew) · `retryAfter` (backoff gate; claim CAS
`$unset`s it and re-checks it — page-to-claim race is real) · `retryCount` ·
`retryClass` (`generic|ratelimit`) · `timeoutAt` (**denormalised absolute deadline** =
claimedAt + effectiveTimeout + class grace — sweep reaps on an indexed range scan; fixes
the DAGUtility merge-bug site by computing effectiveTimeout once at claim) · `waitUntil`
(durable sleep resume) · `attempt`/`supersededAt`/`supersededBy` (Q-117).

**WorkflowRunEntity (new):** `pauseRequestedAt` · same claim block for the two
workflow-level claimables — *provision* and *teardown* (fixes "completed runs redelivered
every poll": teardown eligibility = `phase completed, workspaces != [], claimedBy absent`)
· `timeoutAt` (set at start CAS = startTime + T*60+5s, validated at submit ≥ critical-path
Σ task budgets; absent = unguarded, logged visibly).

### 1.2 Claim query + CAS + indexes

Eligibility per class C: `{ type ∈ T(C), status:"ready", phase:"pending", claimedBy
absent, retryAfter absent-or-elapsed }`, sort
`creationDate:1` (FIFO). Claim = per-candidate `findAndModify` by `_id` re-checking full
eligibility; sets phase (`queued` worker / `running` inline), claim block, `timeoutAt`,
`$inc claimEpoch`, `$unset retryAfter`. Null = lost, skip. **This is the audit #28/#29
fix.**

Indexes (loader changeset): `task_runs {type,status,phase,creationDate}` (claim_page) ·
`{workflowRunRef,status,name}` (run_tasks — count-don't-load + find-live-by-name) ·
**`{workflowRunRef,name,attempt}` UNIQUE** (node_generation_unique) · sparse
`{leaseExpiresAt}`, `{timeoutAt}`, `{waitUntil}` · `workflow_runs
{status,phase,creationDate}`, sparse `{timeoutAt}`, sparse `{pauseRequestedAt}`,
`{workflowRef,status}` (tombstone_sweep).

**PICK — full unique `(workflowRunRef, name, attempt)` index, NOT the ruled
partial-where-supersededAt-absent form: Mongo `partialFilterExpression` cannot express
`$exists:false` — the ruled formulation is unimplementable.** Same race protection
(racing reconcilers computing prevMax+1 → DuplicateKey = already-created); "one live per
node" enforced by the supersede CAS + reconcile assert. Requires one bounded loader
dedupe backfill of existing `(workflowRunRef,name)` duplicates.

### 1.3 Pause exclusion — ✅ SUPERSEDED (2026-08-13): single admission gate, no claim filter

**Current ruling (Track 2):** pause is enforced at the **single admission gate** in
`TaskExecutionService.queue` — a paused run admits no NEW TaskRuns. The claim query carries
**no** pause filter: work already `ready`/claimed/running when the run pauses runs to
completion (and reaps on its absolute deadline regardless of pause). This removes the
two-step join entirely (the `excludePausedRuns` lookup and its per-poll round-trip are
deleted from `findClaimable`/`findReapable`), so there is no `pausedIds` to compute and the
benchmark flip-gate below is moot. Rationale: the admission gate is a strictly workflow-level
check on an already-loaded `WorkflowRunEntity`; the claim-query exclusion was a redundant
second chokepoint that also held back in-flight work, contradicting the "in-flight completes"
pause semantic. The `{pauseRequestedAt}` sparse index stays (the admission gate and resume
reconcile read it). Verified by `PendingRecoveryScenariosTest`
(`readyTaskStaysClaimableWhenRunPauses` + `pausedRunHoldsGraphAdvanceUntilResume`).

**Original ruling (2026-07-22), retained for rationale — two-step join:** Per-cycle: read
paused run ids (sparse index, id-projection) → `$nin` in the claim page → ARCHIE's
second-line re-check between page and claim. Rationale then: **one source of truth** (a
denorm flag makes pause a fan-out write whose partial failure is an authz-grade bug needing
its own repair sweep); the paused set is human-scale (tens). The single-gate ruling above
removes the exclusion altogether rather than choosing between the two-step join and the denorm
flag — resolving conflict CQ-1 (vs multi-instance-model.md W7) by making it moot.

### 1.4 PICK — page-then-CAS (ARCHIE), not per-doc sort-loop (CHEER)

Caps need a once-per-cycle budget; sort-inside-findAndModify head-blocks on candidates
this claimant must skip (capability mismatch, sub-cap, paused mid-cycle); atomicity is
identical (the CAS re-checks everything). Page 20, poll 3s inline / event-nudged
long-poll workers.

### 1.5 Queue classes (17 TaskTypes → 5 classes; static engine-side map)

| Class | Types | Claimed? | Lease | Cap |
|---|---|---|---|---|
| worker | template, custom, script, generic (+ workflow provision/teardown) | Yes, via agent protocol | 180s, renewed | per-agent declared + per-type engine cap |
| inline | decision, setwfproperty, setwfstatus, acquirelock, releaselock, runworkflow, runscheduledworkflow | Yes — CAS then immediate in-process execution; 10s backstop poller for orphaned admissions | 120s fixed, no renewal | semaphore 50/instance (L-07) |
| wait | sleep | Never while waiting — `waitUntil` due-work | n/a | n/a |
| gate | approval, manual, eventwait | **Never claimed** — resolved externally (endTaskRun CAS / topic event) | n/a; `timeoutAt` only if explicitly set (human-paced excluded from reaping) | n/a |
| structural | start, end | Never | n/a | n/a |

`acquirelock`/`releaselock` execute as atomic TTL-lease docs, bounded attempts requeued
via `retryAfter` — never a held thread.

**Caps/kill switches — PICK: DB-settable with property seed** (settings override →
`flow.queue.*` property → default; engine mode degrades to properties-only). Kill switch
disables **claiming only** — sweeps keep running; work accumulates in `ready`, drains
FIFO on re-enable.

### 1.6 Retry classes (Q-123) — typed, never string-matched

generic 10s×2 +j5s cap3 ceil5m · ratelimit 30s×2 +j15s cap6 ceil10m · terminal → failed
immediately with readable message. Requeue = one field-scoped update guarded by
`claimEpoch` (stale worker can't requeue the next attempt). **Classification: protocol v2
carries `failureClass` on `TaskRunEndRequest` (the agent maps runtime knowledge — HTTP
status, exit codes, Tekton reasons); engine-internal = `TaskFailureException(class)`
hierarchy + static error-code→class table. ARCHIE's `isRateLimitError` string-matching is
the named anti-pattern.** OPEN: task-author self-classification surface → Q-402.

### 1.7 Leases

Worker: 180s = 3× the 60s batched renew (`POST /api/agent/v2/claims/renew` with
`{taskRunId, claimEpoch}` list; per-claim CAS on `{_id, claimedBy, claimEpoch}`; rejected
ids ⇒ **agent halts and abandons external actions** — fencing propagated to the runtime,
Q-406). Inline: 120s fixed. Expiry ⇒ requeue as generic (consumes an attempt); next
claim's `$inc claimEpoch` fences ghosts. **Timeout and lease are independent guards**: a
healthy long task renews for hours and is reaped only by `timeoutAt` (= budget + grace —
the class-A violation fix; grace composes downward).

### 1.8 Long-poll v2

`DeferredResult` (no container thread held; fixes ~2-threads-per-agent saturation), hold
30s engine-owned; registration handshake returns `{holdSeconds, readTimeout=2×hold,
renewInterval, leaseSeconds}` — no independently-drifting constants. Completion triggers:
work-ready ApplicationEvent nudge + 5s fallback tick. **Claims happen only at dispatch**
— a timed-out poll claims nothing (ghost-claim window eliminated). Registration = upsert
on unique `(name, host)`. Behind the named `AgentProtocol` (I6); in-process binding
interface-shaped now, exercised by tests until standalone embedding (J8).

## D2. Scheduling Substrate (Q-221/Q-227) — ⏸️ **DEFERRED (maintainer, 2026-07-22): decide at implementation time** (migration step 6)

> Deferral scope: **schedule firing only** (JobRunr uses #1/#2). Uses #3 (per-run
> workflow timeout) and #4 (sleep resume) move to the watcher sweep regardless — that
> was ruled in Q-121 and is not deferred. Both schedule-firing designs below are kept
> current so the point-in-time decision has a complete record; the kill-at-every-step
> fire test remains the decision gate if (b) is chosen.

JobRunr's four uses: recurring schedules, runOnce, per-run workflow timeout (condemned —
sweep replaces), sleep resume (never shipped — `Thread.sleep`). Against keeping it:
stored-lambda serialization couples jobs to class/method names — **a refactor landmine
aimed directly at v5's rename/merge**; a second opaque claim system with the
architecture's **only leader election** (recurring-job master node); `schedulerRef` drift
(the entity is already declared source of truth). What (b) re-implements: next-fire =
**cron-utils** (already shipped — today preview and firing use two different parsers that
can disagree); misfire ~20 explicit lines; durable one-shots = a field. Not needed:
dashboard/retry UI (never enabled).

**Design:** `nextFireAt` + `lastFiredAt` + `firing{at,by,leaseExpiresAt}` +
`misfirePolicy(skip|fireOnce)` on `WorkflowScheduleEntity` (no separate due collection —
the domain doc IS the due-work doc, same reasoning as sleep's `waitUntil`). Fire
protocol (10s jittered sweep, schedule module): CAS-claim on the observed `nextFireAt`
value (natural fencing) with 60s firing lease → idempotent submit guarded by sparse
unique `workflow_runs {scheduleRef, scheduleFireAt}` (DuplicateKey = already fired =
success) → advance `nextFireAt` via cron-utils (DST: gap-skip / first-offset) + `$unset
firing`. Kill-at-every-step analysis: no fire lost, no fire doubled; **catch-up storms
structurally impossible** (fireOnce collapses any backlog to one run). runOnce: `$unset
nextFireAt` + status completed — also completes the `runscheduledworkflow` inversion
(`ScheduleRequested` event → runOnce entity → same fire machinery). Sleep resume:
admission CAS sets `waitUntil = min(duration, remaining budget)`; due-sweep CAS
`waiting→succeeded`; zombie completions impossible. Loader: compute `nextFireAt` for
active schedules (second bounded backfill). **Falsifiability: if the kill-at-every-step
integration test cannot show exactly-once effect, option (a) re-opens.**

## D3. WorkflowWatcher Specification

Every instance, jittered ±20%, startup pass on `ApplicationReadyEvent`. Laws: narrow
indexed queries · count-don't-load · act only through the live path's CAS/claim
primitives · `maxTimeMS` everywhere. Schedule-fire lives in the schedule module (engine
mode gets sweeps 1–5 only).

| # | Sweep | Interval | Guard |
|---|---|---|---|
| 1 | Stalled-run reconcile (active run, zero non-terminal live tasks → `reconcile()`) | 60s | unique generation index + transition CAS; cooldown-limited logging |
| 2a | Workflow timeout reap (`timeoutAt ≤ now, running` → CAS timedout, winner-only auto-retry) | 30s | CAS pre-image; retry = NEW run doc so stale guards physically can't reap attempt N+1 |
| 2b | Task lease reap (`leaseExpiresAt ≤ now`; pause does not skip reaping — in-flight tasks reap on their absolute deadline regardless of pause) | 30s | requeue CAS on `{_id, claimedBy, claimEpoch}` |
| 2c | Task timeout reap (per-class `timeoutAt` baked at claim; agent notified via poll/renew cancellation list) | 30s | epoch-guarded CAS |
| 2d | Sleep due (`waitUntil ≤ now` → CAS waiting→succeeded) | 30s | status-matched CAS |
| 3 | Tombstone cancellation (runs of tombstoned workflows → normal cancel path; agents notified; gates released) | 60s | same CAS as user cancel; crash-mid-delete self-heals |
| 4 | Retention pruning (tombstoned + all-finalised → hard cascade; attempt-history retention) — **only sweep that destroys data**; ships disabled until retention ruled (OPEN) | 1h | finalised-count gate re-evaluated every tick |
| 5 | Orphan backstop (missing revision → CAS failed, ERROR log; stale agent → treat as 2b; stray actions closed) | 10m | CAS; never silent deletion |
| 6 | Schedule fire (schedule module) | 10s | §D2 protocol |

**No-leader-election math:** at A=1000 active runs, N=5: sweep 1 ≈ 84 index-only ops/s;
everything else ≈ 2 ops/s. Total < 100 ops/s — vs an election mechanism, a distinguished
failure mode (leader death stalls ALL sweeps — the recovery-latency class Q-121 kills),
and a third coordination system after deleting two. Scaling valve if A grows 100×:
cooperative `_id`-hash sharding of the reconcile page — leaderless, not built ahead of
need.

## Gap tags

Claim/indexes/CAS/pollers/retry/kill-switches + sweeps 1-3,5,6 = **BEFORE-MERGE** (steps
4–6; the sweeps are DD-02's blast-radius precondition) · long-poll v2 + agent auth =
BEFORE-MERGE (step 8) · JobRunr deletion = BEFORE-MERGE (step 6), collection drop
POST-MERGE · sweep 4 = POST-MERGE, disabled by default · in-process binding exercised =
POST-MERGE (J8) · pause benchmark + fire crash-test = CI gates.

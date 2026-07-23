# E3 Schema Proposal — G2 Gate Document (2026-07-23)

**Status:** ✅ **G2 RULED (2026-07-23)** — D1: **nested sub-elements** `claim{by, at,
leaseExpiresAt}` + **top-level `claimEpoch`** (fencing token must survive the requeue
`$unset`), `retry{after, count, class}`, `superseded{at, by}`; `attempt`/`timeoutAt`/
`waitUntil` top-level; same blocks on BOTH TaskRun and WorkflowRun. Field tables below
read in flat form — apply the nesting. Indexes become dotted-path
(`{"claim.leaseExpiresAt":1}` sparse; claim page filters `"retry.after"`). D2: **unique
indexes ship WITH E3, before E4 code** (the legacy race throws loudly instead of
corrupting). D3: **four-field unique** `{workflowRunRef, name, mapIndex, attempt}`
(mapIndex null for normal tasks — fan-out never rebuilds the index). The loader
changesets implement exactly this ruled shape.
**Maintainer ruling (2026-07-24):** the fencing token moves INSIDE the claim block as
`claim.seq`; requeue clears `claim.by`/`claim.at`/`claim.leaseExpiresAt` only — seq is
never cleared — and eligibility keys on `"claim.by": {$exists: false}`.
**Scope:** the E3 row of the gate table — additive claim/supersede/pause schema + indexes
(migration step 4). E4's additions (`transitionSeq`, `lastOutboxedSeq`, `events_outbox`,
`events_ingress`, `task_locks`, `tombstonedAt`) come in E4's own G2. Schedule fields
(`nextFireAt` etc.) come with the deferred Q-227 decision at E5.
**Logistics note (superseded by DD-07):** implemented as the `service-loader` module in
THIS repo — Flamingock changeunits `_0001`–`_0006` (the legacy `flow.loader` repo remains
only for the fresh-install seed during its deprecation window).

## 1. New fields — all absent-as-eligible (zero document backfill)

**TaskRunEntity** (`task_runs`):

| Field | Type | Written by | Absent means |
|---|---|---|---|
| `claimedBy` | String | claim CAS only | unclaimed |
| `claimedAt` | Instant | claim CAS | — |
| `leaseExpiresAt` | Instant | claim CAS + renewal CAS | no lease (unclaimed or non-leasing class) |
| `claimEpoch` | long | `$inc` per claim, **never reset** | seq 0 |
| `retryAfter` | Instant | fail-path requeue (`$unset` on claim) | eligible now |
| `retryCount` | int | `$inc` on requeue | 0 |
| `retryClass` | String (`generic`\|`ratelimit`) | fail path | no prior failure |
| `timeoutAt` | Instant | claim CAS (budget + class grace, computed once) | unguarded |
| `waitUntil` | Instant | sleep admission | not a waiting sleep |
| `attempt` | int | reconciler on supersede re-creation | generation 0 |
| `supersededAt` / `supersededBy` | Instant / String | supersede CAS | live generation |

**WorkflowRunEntity** (`workflow_runs`):

| Field | Type | Notes |
|---|---|---|
| `pauseRequestedAt` | Instant | Q-126: the pause flag — never a status. Exclusion via the **two-step join** (ruled) — NO paused field on task_runs |
| `claimedBy`/`claimedAt`/`leaseExpiresAt`/`claimEpoch` | as above | for the two workflow-level claimables (provision/teardown) — also fixes terminal-runs-redelivered |
| `timeoutAt` | Instant | set at start CAS; validated at submit ≥ critical-path Σ task budgets |
| `idempotencyKey` | String | request dedup (B13); schedule fires will use `sched:<ref>:<seq>` |
| `createdByTaskRunRef` | String | real field for runworkflow child dedup (B6) — replaces annotation-only linkage |
| `retryOfRef` / `retryAttempt` | String / int | real fields replacing the `boomerang.io/retry-of` annotations (B5) |

Existing fields untouched. `agentRef` remains (protocol-v1 alias until DD-06's dispatcher
rename retires it).

## 2. Indexes (loader changeset, additive)

```js
// task_runs
{ type:1, status:1, phase:1, creationDate:1 }              // claim_page (FIFO)
{ workflowRunRef:1, status:1, name:1 }                     // run_tasks (count-don't-load, find-live-by-name)
{ workflowRunRef:1, name:1, attempt:1 }  UNIQUE            // node_generation (M7; see D2/D3)
{ leaseExpiresAt:1 }  sparse                               // lease sweep
{ timeoutAt:1 }       sparse                               // timeout sweep
{ waitUntil:1 }       sparse                               // sleep-due sweep

// workflow_runs
{ status:1, phase:1, creationDate:1 }                      // workflow claim_page
{ timeoutAt:1 }          sparse                            // workflow timeout sweep
{ pauseRequestedAt:1 }   sparse                            // paused-id lookup (the join's step 1)
{ idempotencyKey:1 }        UNIQUE partial (field exists)  // B13
{ createdByTaskRunRef:1 }   UNIQUE partial (field exists)  // B6 child dedup
{ retryOfRef:1, retryAttempt:1 } UNIQUE partial (exists)   // B5 retry dedup

// actions
{ taskRunRef:1 }  UNIQUE                                   // B6 duplicate approvals

// agents
{ name:1, host:1 }  UNIQUE                                 // B12 (registration becomes upsert)
```

All builds background-mode. Partial filters use `$exists: true` on the keyed field
(supported — unlike the `$exists:false` form M7 corrected).

## 3. The one bounded backfill

**Generation dedupe (pre-changeset for the unique node_generation index):** the audit says
`(workflowRunRef, name)` duplicates can exist (the claim race demonstrated by the safety
net can produce them). Before the unique index: mark extras on finalised runs
`supersededAt: <migration timestamp>`, renumber `attempt`. Small, enumerable set —
the only write-backfill E3 needs.

## 4. Rollback

Additive fields are inert to old code (it neither reads nor writes them). Non-unique
indexes: droppable, zero behaviour change. **Unique indexes are the one behaviour-visible
item** — see D2.

## 5. Decision points for the G2 ruling

- **D1 — Field names as tabled?** (claim block, retry trio, `timeoutAt`/`waitUntil`,
  supersede pair, the three promoted-from-annotation workflow_runs fields).
- **D2 — Unique-index timing.** Ship uniques in the E3 loader (before E4 code): the
  pre-E4 engine's duplicate-creation race then throws `DuplicateKeyException` to the
  submitter instead of silently corrupting — an error where there was silent corruption.
  Alternative: ship non-uniques at E3, uniques in the SAME release as E4's
  DuplicateKey-aware code — no behaviour change window, one more loader release.
- **D3 — `mapIndex` in the unique key now?** Four-field
  `{workflowRunRef, name, mapIndex, attempt}` future-proofs the dynamic fan-out (C13,
  absent = null for normal tasks, no rebuild later) vs the ruled three-field M7 shape
  (rebuild the index when fan-out lands).

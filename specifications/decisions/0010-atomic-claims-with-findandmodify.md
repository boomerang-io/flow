# 0010 — Work is claimed atomically with findAndModify, not with distributed locks

**Status:** accepted · **Date:** 2026-07-23

## Context

The previous queue did a `find` and then a separate bulk update, and returned the `find` result — so the loser of
the update race still dispatched the same runs, and terminal runs were redelivered on every poll. The distributed-lock
library that was supposed to guard this was not actually mutually exclusive (deterministic token, exists-then-acquire).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Distributed locks around find-then-update | a lock library that is genuinely exclusive; single-writer patterns | an extra collection and lease to keep correct; a lock holder that crashes blocks everyone until expiry |
| B. Page candidates, then one `findAndModify` per candidate that re-checks eligibility and writes the claim block | any number of instances polling the same queue | one round trip per claim; a losing claimant wastes a cheap primary-key no-op |
| C. Sort inside a single `findAndModify` loop | a single queue class with no skipping | a head candidate the claimant must skip (wrong task type, paused) blocks everything behind it |

## Decision

Option B. `findClaimable` pages eligible ids oldest first (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:75-95`)
and `tryClaim` claims each by id in one write that re-checks status, phase, absence of `claim.by` and the retry
backoff, and sets `claim.by`, `claim.at`, `$inc claim.seq` and `timeoutAt` together (`TaskRunService.java:238-258`).
Only the claimed set is dispatched (`dispatcher/DispatcherService.java:205-215`). The atomic write is what makes two
claimants unable to both win; the page is what lets a claimant skip candidates it cannot take.

## Consequences

- No lock collection for the queue; the claim block on the run is the only ownership record, and `claim.seq`
  fences stale claimants at start and end (`TaskExecutionService.claimantIsValid`).
- Workflow runs use the same shape for provisioning and teardown claims (`WorkflowRunStateHelper.java:46-133`).
- A dead claimant's work is recovered by the deadline sweep, not by a lock expiry (see decision 0014).

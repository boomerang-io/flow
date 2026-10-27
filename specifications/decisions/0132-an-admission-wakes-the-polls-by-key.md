# 0132 — An admission wakes the held polls by key, each on a condition of its own

**Status:** accepted · **Date:** 2026-10-28

## Context

Admitting a task woke every poll held on the instance through one generation counter and `signalAll`, so each
admission cost one claim-page query per held poll whatever its filter: a dispatcher holding eight typed pools paid
eight finds for a task one of them could claim. The wake has to reach the polls that can claim the task, and a
signal that lands while a poll is still looking must not be lost.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One counter, wake all, each poll re-queries | Few held polls per instance | A find per held poll per admission; the eight-pool dispatcher measured it |
| B. A ring of recent admissions; every poll wakes and matches in memory, re-queries only on a match | The match must be cheap and the registry avoided | Every admission still wakes every poll's thread; a ring bounds how far back a poll may look, so a burst past it falls back to A |
| C. A registry keyed by task type, one condition per waiter, registered before the look, the filter matched in memory | The wake should reach matching polls only | A map insert and remove per poll; the in-memory match must mirror the claim query's narrowing exactly |
| D. A database change stream on task runs entering `ready` | Admissions on other replicas must wake polls too | A tailing cursor per instance and a replica set requirement; listed as not built in `performance.md` |

## Decision

C. The reference designs all wake by key: Temporal holds waiting polls per task queue and matches an added task
to them directly, Oban and River notify on one channel per queue, Linux's filtered wake-ups carry a key so only the
waiters for it run, and Java's own guidance is a wait-set per condition over `signalAll`. A held task poll registers
its `ClaimFilter` under each of its types before its first look and waits on its own `Condition`; an admission
signals the waiters under the task's type whose filter matches its task and workflow references, the in-memory
twin of `TaskRunService.narrowed`; a match that lands during a look is kept on the waiter so the wait that follows
ends at once (`dispatcher/PollWakeups.java:34-110`, `dispatcher/DispatcherService.java:392-396`,
`engine/TaskExecutionService.java:255-256`). Run signals have their own counter and reach the workflow polls alone.

## Consequences

- One claim-page find per admission per poll that can claim the task, measured in
  `DispatcherRequestDatabaseCommandsTest.anAdmissionWakesOnlyThePollsWhoseFilterMatches`; the timed re-check is
  unchanged and still covers an admission on another replica.
- The in-memory match and the claim query's narrowing MUST stay the same predicate; a divergence costs latency
  (the timed re-check finds the task), never a lost task.
- Revisit with D when a measured multi-replica hop latency is dominated by the interval.

# 0061 — Concurrency caps and per-class kill switches wait for load-test evidence

**Status:** accepted · **Date:** 2026-08-18

## Context

The queue design described per-class concurrency caps, per-class kill switches, a per-type cap
override, and typed retry classes (rate-limit, deterministic-terminal). When the claim-based queue was
built, only the generic backoff and one global switch were implemented; the question was whether to
build the rest now or wait.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Build caps, per-class switches, and retry classes now, as designed | A known workload already saturates one task type or a runtime already reports typed rate-limit signals | New fields (`retryClass`), a settings-backed budget per poll cycle, and a per-class switch — all abstractions with no caller that needs them yet. |
| B. Ship the global switch and the generic backoff; reopen on evidence | No load test has shown starvation, overrun, or a need to stop one class selectively | An incident could need a selective stop before the knob exists; the fallback is the global switch or scaling the dispatcher. |

## Decision

Option B. The claim page filters only by the dispatcher's registered task types
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:75-95`); the single kill switch
`flow.queue.enabled` stops claiming only and never gates the recovery sweeps
(`dispatcher/DispatcherService.java:38-40,111,176`); retry is one exponential backoff with a
3-attempt budget (`lib-common/src/main/java/io/boomerang/common/util/Backoff.java:12-22`,
`engine/WorkflowWatcher.java:58`) and `retry` carries only `after` and `count`. Building the
designed classes ahead of a demonstrated need is over-abstraction; load testing reopens this, not
speculation.

## Consequences

- Operators have one lever today: `flow.queue.enabled=false` drains nothing new to any dispatcher while
  in-flight work finishes or times out normally.
- A dispatcher-reported failure (as opposed to a timeout) is not retried; only timeouts and gone
  claimants requeue.
- Reopen when a load test shows one task type starving others or overrunning the cluster, when an
  incident needs one class stopped while others run, or when an integrated runtime returns typed
  rate-limit signals that a single backoff handles badly.

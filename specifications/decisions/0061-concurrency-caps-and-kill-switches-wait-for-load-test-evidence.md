# 0061 — Concurrency caps, per-class kill switches and retry classes wait for load-test evidence

**Status:** accepted · **Date:** 2026-08-18

## Context

The queue design described per-class concurrency caps, per-class kill switches, a per-type cap override,
and three retry classes (generic, rate-limit, deterministic-terminal) on a typed `retryClass` field. When
the claim-based queue was built, only the generic backoff and one global switch were implemented.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Build caps, per-class switches, and retry classes now, as designed | A known workload already saturates one task type or a runtime already reports typed rate-limit signals | New fields (`retryClass`), wire changes to the dispatcher protocol, a settings-backed budget per poll cycle, and a per-class switch — all abstractions with no caller that needs them yet. |
| B. Ship the global switch and the generic backoff; reopen on evidence | No load test has shown starvation, overrun, or a need to stop one class selectively | An incident could need a selective stop before the knob exists; a rate-limited task retries on the generic curve. |

## Decision

Option B. The claim page filters only by what the dispatcher's poll asks for
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:93`); the single kill switch
`flow.queue.enabled` stops claiming only and never gates the recovery sweeps
(`dispatcher/DispatcherService.java:57-58,178,284`); retry is one exponential backoff — 10 s base,
doubling, 5 min ceiling, up to 5 s jitter (`service-core/src/main/java/io/boomerang/common/util/Backoff.java:12-21`)
— with a 3-attempt budget (`engine/EngineConstants.java:34`), and `RunRetry` carries only `after` and
`count` (`service-core/.../model/RunRetry.java:17-18`). Retry happens only when a requeueable task times
out, its lease lapses or its dispatcher disappears; a task the dispatcher reports as `failed` is not
retried. Building the designed classes ahead of a demonstrated need is over-abstraction; load testing
reopens this, not speculation.

## Consequences

- Operators have one lever today: `flow.queue.enabled=false` drains nothing new to any dispatcher while
  in-flight work finishes or times out normally.
- Retry behaviour is one curve everywhere: the task queue, the outbox dispatcher and schedule re-arming
  all call the same `Backoff`. A retry policy, if ever built, derives from the typed `statusReason`
  (decision 0065) rather than a new wire field.
- Reopen when a load test shows one task type starving others or overrunning the cluster, when an
  incident needs one class stopped while others run, or when a task family (for example a rate-limited
  AI task type) demonstrably needs a different policy.

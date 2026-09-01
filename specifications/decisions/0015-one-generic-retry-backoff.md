# 0015 — One generic retry backoff; retry classes are not built until proven needed

**Status:** accepted · **Date:** 2026-08-18

## Context

The queue design specified three retry classes — generic backoff, rate-limit (longer base, higher cap) and
deterministic-terminal (no retry) — carried on a typed `retryClass`/`failureClass` field. Only the generic backoff
was ever implemented, and nothing has demanded the other two.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Build the three classes as designed | a task family with distinct failure economics exists today | a new typed field, wire changes to the dispatcher protocol and classification logic with no consumer |
| B. Keep the single `Backoff` and correct the design document | no evidence of need | a rate-limited task retries on the generic curve |

## Decision

Option B. `Backoff.nextRetryAt` (10 s base, doubling, 5 min ceiling, up to 5 s jitter —
`lib-common/src/main/java/io/boomerang/common/util/Backoff.java:12-21`) is the only policy; `RunRetry` carries
`after` and `count` only (`lib-common/.../model/RunRetry.java:17-18`). Retry happens only when a requeueable task
times out or its dispatcher disappears, with a budget of 3 attempts (`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:55-58`,
`:157-163`); a task the dispatcher reports as `failed` is not retried. Building the classes ahead of a consumer
would be abstraction ahead of proven need.

## Consequences

- Retry behaviour is one curve everywhere: the task queue, the outbox dispatcher and schedule re-arming all call the
  same `Backoff`.
- Trigger to revisit: a task family (for example a rate-limited AI task type) whose failures demonstrably need a
  different policy; the earlier design is in git history (`specifications/queue-design.md`, before 2026-09-01) and is the starting point.

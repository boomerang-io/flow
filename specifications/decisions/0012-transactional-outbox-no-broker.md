# 0012 — Outbound events go through a transactional outbox; no broker, no partitioning, no leader

**Status:** accepted · **Date:** 2026-07-23

## Context

Outbound CloudEvents used to be emitted by an aspect around `repository.save()`, before the save committed and
fire-and-forget: two instances double-fired, a failed save produced a phantom event, and a down sink lost the event.
In-process events between modules can also be lost if the process dies after the database write.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep inline emission from the save path | single instance, best-effort notifications | double-fire, phantom and silent-loss defects remain |
| B. Outbox row written by the transition winner, drained by every instance with a status CAS | any instance count; sink may be down | at-least-once delivery, consumers must tolerate duplicates |
| C. A message broker (Kafka/NATS) for internal and external events, partitioned by run | strict ordering or sub-sweep crash latency is required | permanent infrastructure in every deployment; a listener can still crash mid-action, so the sweep is needed anyway |

## Decision

Option B. `CloudEventsBridge` inserts one `events_outbox` row per externally visible status change when the sink is
enabled (`service-core/src/main/java/io/boomerang/event/CloudEventsBridge.java:32-76`); `OutboxDispatcher` on every
instance drains pending rows, marks them `sent` by CAS, retries with backoff and marks them `dead` after 3 attempts
(`event/OutboxDispatcher.java:59-85`). Internal reactions stay in-process Spring events, which are hints only: the
durable fact is the CAS commit, and the watcher sweeps re-derive any lost hint from persisted state. A broker would
add infrastructure without adding correctness.

## Consequences

- Consumers receive each event at least once and MUST treat duplicates as benign.
- No partitioning or sticky routing: any instance processes any event.
- The creation-loss window between the CAS commit and the outbox insert is accepted separately (decision 0017).

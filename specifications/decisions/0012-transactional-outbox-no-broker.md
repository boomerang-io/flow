# 0012 — Outbound events go through a transactional outbox, not a broker

**Status:** accepted · **Date:** 2026-07-23

## Context

Outbound CloudEvents used to be emitted by an aspect around `repository.save()`, before the save committed and
fire-and-forget: two instances double-fired, a failed save produced a phantom event, and a down sink lost the event.
A transition commits with a single-document compare-and-set, and no transaction spans that write and the outbox
insert — there is no `MongoTransactionManager` in `service-core` — so a crash between the two loses one notification.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep inline emission from the save path | single instance, best-effort notifications | double-fire, phantom and silent-loss defects remain |
| B. Outbox row written by the transition winner, drained by every instance with a status CAS | any instance count; sink may be down | at-least-once delivery; a crash between the commit and the insert loses that one row |
| C. A message broker (Kafka/NATS) for internal and external events, partitioned by run | strict ordering or sub-sweep crash latency is required | permanent infrastructure in every deployment; a listener can still crash mid-action, so the sweep is needed anyway |
| D. B plus a `transitionSeq` on runs, outbox rows keyed by it, and a heal sweep — or multi-document transactions | an outbound consumer is load-bearing (billing, audit of record) | a new field and an `$inc` inside every CAS, or transactions on every transition, for a microsecond window |

## Decision

Option B, with the creation-loss window accepted. `CloudEventsBridge` inserts one `events_outbox` row per externally
visible status change from the in-process transition event, after the compare-and-set that won
(`service-core/src/main/java/io/boomerang/event/CloudEventsBridge.java:32-64`); `OutboxDispatcher` on every instance
drains pending rows, marks them `sent` by CAS, retries with backoff and marks them `dead` after three attempts
(`event/OutboxDispatcher.java:63-93`). Internal reactions stay in-process Spring events, which are hints only: the
durable fact is the CAS commit, and the watcher sweeps re-derive any lost hint from persisted state. The window
affects creation only: the engine never reads the outbox to decide anything, so a lost row cannot stall or corrupt a
run, and the sink is off by default (`application.properties:51`); the row's own documentation records the limitation
(`event/entity/EventOutboxEntity.java:15`). A broker would add infrastructure without adding correctness, and option D
would be built ahead of an observed loss.

## Consequences

- Consumers receive each event at least once and MUST treat duplicates as benign.
- Outbound CloudEvents are at-least-once for created rows and best-effort across a crash in the window.
- No partitioning or sticky routing: any instance processes any event (decision 0060).
- Trigger to revisit: any report of a missing terminal-status CloudEvent, or a consumer becoming load-bearing on
  delivery; option D is the starting point.

# 0017 — The outbox creation-loss window is accepted

**Status:** accepted · **Date:** 2026-08-21

## Context

A transition commits with a single-document CAS; only the winner then publishes an in-process event, and a
synchronous listener inserts the `events_outbox` row. No transaction spans those two statements — there is no
`MongoTransactionManager` in `service-core` — so a crash between them loses that one outbound notification.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Add `transitionSeq` to runs, key outbox rows by `<refType>:<ref>:<seq>`, and add a heal sweep | an outbound consumer is load-bearing (billing, audit of record) | a new field on both run entities, an `$inc` inside every CAS, and a sweep that compares sequences |
| B. Mongo multi-document transactions | a replica set is guaranteed in every deployment | transactions on every transition for a microsecond window |
| C. Accept and document the window | the only consumer is optional CloudEvents, off by default | one status notification can be lost on a crash in the window |

## Decision

Option C. The window affects creation only; delivery of rows that exist is sound (`OutboxDispatcher` retries and marks
`dead`, `service-core/src/main/java/io/boomerang/event/OutboxDispatcher.java:59-85`). The engine never reads the outbox
to decide anything, so a lost row cannot stall or corrupt a run, and the sink is off by default
(`flow.events.sink.enabled=false`). The row's own documentation records the limitation
(`event/entity/EventOutboxEntity.java:13-17`). Building option A ahead of an observed loss would repeat the retry-class mistake.

## Consequences

- Outbound CloudEvents are at-least-once for created rows and best-effort across a crash in the window.
- Trigger to revisit: any report of a missing terminal-status CloudEvent, or a consumer becoming load-bearing on
  delivery. Option A's design is in git history (`specifications/entity-diff-v4-v5.md` §6, before 2026-09-01).

# 0012 — Outbound status events go through an outbox, not a broker, and carry a run's identity and lifecycle, not the run

**Status:** accepted · **Date:** 2026-09-16

## Context

A status CloudEvent must reach a sink that may be down, from any number of instances, and never for a write that did not
commit; transitions commit with a single-document compare-and-set (CAS), and no transaction spans them and a second write.
Most consumers treat the event as a trigger and read the run back, yet a sink sent the run model gets every result.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Delivery: emit inline from the save path | One instance, best-effort notifications | Double-fire across instances, a phantom event for a failed save, silent loss while the sink is down |
| Delivery: a message broker (Kafka, NATS) partitioned by run | Strict ordering or sub-sweep crash latency is required | Infrastructure in every deployment; a listener can still crash mid-action, so the sweeps are needed anyway |
| Delivery: the outbox plus a `transitionSeq` on runs and a heal sweep, or multi-document transactions | An outbound consumer is load-bearing (billing, audit of record) | A new field and an `$inc` in every CAS, or a transaction on every transition, to close a microsecond window |
| Payload: the full public run model by default | Consumers act on results without calling back | Parameters and results go to every sink; a large run makes a large request |

## Decision

An outbox written by the transition winner and drained by every instance, with a thin payload. `CloudEventsBridge` inserts
one `events_outbox` row per externally visible status change, from the event the winning CAS publishes, and exists only
while the sink is enabled (`service-core/src/main/java/io/boomerang/event/CloudEventsBridge.java:21-77`; off by default).
`OutboxDispatcher` drains pending rows every 5 s on every instance, gives the re-read run the row's status and phase,
marks the row `sent` by CAS, retries with the shared backoff and marks it `dead` after three attempts
(`event/OutboxDispatcher.java:64-115`, `:133-163`); `/api/v2/system/outbox` lists and replays rows
(`event/OutboxControllerV2.java:31`, `:66`). `flow.events.sink.payload` defaults to `thin`: `data` holds `id`, `workflowRef`,
`workflowRunRef`, `status`, `statusReason`, `phase`, `labels`, `creationDate`, `startTime` and `duration`; `full` sends
the public model with secret parameters redacted and their values scrubbed (`event/model/RunStatusSummary.java:23-71`).
The engine never reads the outbox to decide anything, so a lost row cannot stall a run, and a broker adds no correctness.

## Consequences

- Delivery is at least once for every row created; consumers MUST treat duplicates as benign. A crash between the CAS
  and the insert loses that one row (`event/entity/EventOutboxEntity.java:13-19`). Internal reactions stay in-process
  events, which are hints only; the sweeps re-derive any lost one (decision 0060).
- A consumer that needs values reads the run back, where secret parameters are filtered (decision 0043).
- Revisit on a report of a missing terminal-status event or a consumer becoming load-bearing on delivery (start from the
  `transitionSeq` option), or a consumer that must act on results and cannot use `full` (per-sink payload is not built).

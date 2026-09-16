# 0079 — A status CloudEvent carries a run's identity and lifecycle, not the run model

**Status:** accepted · **Date:** 2026-09-16

## Context

`io.boomerang.event.status.workflowrun` and `.taskrun` events put the whole public run model in
`data`: every parameter value and every result (capped at 4 KB each, decision 0041) went to every URL in
`flow.events.sink.urls`. Most consumers use the event as a trigger and then read the run back over the API,
so the payload was unread — but every configured sink received it all the same.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the full run model | A consumer processes results without calling back | Params and results leak to every sink; a big run makes a big POST |
| B. Thin by default, `full` still available | Most consumers trigger on the event and read the run back | A consumer that did read the payload must set `payload=full` or add one API call |
| C. Thin only | No consumer ever needed the payload | Breaks those that did, with no way back |

## Decision

Option B. `flow.events.sink.payload` defaults to `thin`: `data` is the run's identity and lifecycle —
`id`, `workflowRef`, `workflowRunRef`, `status`, `statusReason`, `phase`, `labels`, `creationDate`,
`startTime`, `duration` — projected in one place
(`service-core/src/main/java/io/boomerang/event/model/RunStatusSummary.java:34-53`) and never carrying
`params`, `results` or `annotations`. `payload=full` restores the previous model. The CloudEvent `type`,
`source`, `subject` and `id`, and the outbox rows behind them, are unchanged, so routing and filtering
built on the envelope keep working.

## Consequences

- A consumer that needs values reads the run back (`GET /api/v2/workflowrun/{id}`), where password-typed
  parameters are already filtered (decision 0043) — the sink no longer bypasses that filter.
- A sink receives a bounded payload, so a run with large results no longer sizes the POST.
- Trigger to revisit: a consumer that must act on results without an API call and cannot use
  `payload=full` — that would need per-sink payload selection, which is not built.

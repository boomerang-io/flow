# 0050 — `status` is the external run field; `phase` stays exposed until the dispatcher gets its own wire model

**Status:** accepted · **Date:** 2026-08-18

## Context

A run carries two lifecycle fields: `status` (the user-facing outcome, `RunStatus`) and `phase`
(the engine's orchestration position, `RunPhase`). The intent is that clients read `status` only,
but one pair of classes — `WorkflowRun` and `TaskRun` in `lib-common` — is returned by both the
public `/api/v2` controllers and `/api/v1/dispatcher`, and the dispatcher branches on `phase`
(`service-dispatcher/src/main/java/io/boomerang/dispatcher/QueueService.java:47-55`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `phase` on the shared models and document it | The dispatcher must keep working and no client is harmed by an extra field | The public API carries an internal field; every consumer sees it and may start depending on it |
| B. Split into a dispatcher wire model (or a `@JsonView`/mixin that hides `phase` on `/api/v2`) | A second model is acceptable and the webapp stops reading `TaskRun.phase` | New mapping code on the most-touched classes; the webapp's approval screens read `TaskRun.phase` today |
| C. Drop `phase` and have the dispatcher derive it from `status` | `status` alone can encode queued/completed/finalized | It cannot: `queued` and `finalized` have no `RunStatus` equivalent |

## Decision

Option A for now: `status` is the external field and `phase` is serialised alongside it,
recorded as a known deviation rather than hidden. The tripwire
`service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:110-121`
asserts the current behaviour and MUST be inverted when the split lands. The model comment at
`lib-common/src/main/java/io/boomerang/common/model/TaskRun.java:19-23` states the reason.

## Consequences

- Clients MUST treat `phase` as informational; the query filter `?phase=` exists but `status` is the supported contract.
- Splitting requires: a dispatcher-specific model or view, a `status`-based replacement for the webapp's `TaskRun.phase` reads, and inverting the tripwire. Revisit when the dispatcher protocol next changes shape.

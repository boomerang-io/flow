# 0134 — A workflow limits its in-flight runs at admission, parking or skipping the rest

**Status:** accepted · **Date:** 2026-10-28

## Context

The only concurrency limit was the workspace quota over every workflow. An embedder's index rebuilds and reconcile
ticks overlapped when two runs of one workflow were admitted together, and its launcher checked for an in-flight
run by hand. The limit has to hold under simultaneous submits and must not change the closed `RunStatus` enum.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A per-(workflow, key) slot document taken by an atomic `$inc` | An exact mutex is required | A new collection; a counter that drifts when a decrement is lost and needs its own reconcile |
| B. A count before admission and a recount after it, the younger run stepping back, as the workspace quota does | The limit may be exceeded for the instant between two simultaneous admissions and never after | Two counts per admission on an indexed query; a park compare-and-set |
| C. A new status for a parked run | A distinct filter is wanted | Breaks the closed enum the webapp shares and the public-model pin |
| D. A keyed limit (`concurrency.key` from a param or label) | One run per tenant or per record | A typed key field and an index on the run; the `acquirelock` task already serialises runs on a key |

## Decision

B, with no new status and no key. A workflow carries `concurrency: {limit, overflow}`
(`common/model/WorkflowConcurrency.java:18-19`, saved with the revision). At run admission the engine counts the
workflow's runs in `ready` or `running` on `workflow_ref_status`; at the limit it parks the run as
`waiting/pending` with a message, or with `overflow: skip` completes it `cancelled` with the typed reason
`ConcurrencyLimit`; after a successful admission it recounts and steps back a run that is not among the oldest
`limit` (`engine/WorkflowExecutionService.java:91-116`, `:135-165`; `engine/WorkflowRunStateHelper.java:181-217`). A parked run is the shape a task run parked for retry already has. A
run's completion re-admits the oldest parked run of its workflow (`engine/ConcurrencyAdmissionListener.java:33-55`), and
the watcher's `readmitParkedRuns` sweep is the fallback. The workflow run gains `statusReason`, as a task run has.

## Consequences

- A parked run counts against the workspace quota (it is `waiting`), so a queue cannot hold more than the quota
  allows; the quota keeps precedence.
- The limit holds between two simultaneous admissions only after the recount, as the quota does; an exact mutex
  reopens A with a reproduced incident.
- A per-value limit is the `acquirelock` task until a case shows the lock's in-flight accounting is wrong for it.

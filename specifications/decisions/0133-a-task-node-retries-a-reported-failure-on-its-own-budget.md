# 0133 — A task node retries a reported failure on its own budget, and every superseded attempt is recorded

**Status:** accepted · **Date:** 2026-10-28

## Context

A failure a dispatcher reported was final: the engine requeued only an attempt that never ran. An embedder running
model-call tasks retried transient gateway errors inside its task images, so Flow showed one attempt where several
happened, and reported HTTP 429 as `RateLimited` only to obtain a requeue. The retry belongs to the workflow author
and its attempts to the run.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep retries inside task images | No change to Flow | Invisible attempts; a policy per image; could-not-start reasons bent into failure retries |
| B. A node setting `retry: {max, backoff}`, retried through the existing requeue, with a per-reason selector `on` | The author can tell transient from final failures | The task container cannot set `statusReason`; the dispatcher does from the pod outcome, so a transient error and a bug both arrive as `JobFailed` and the selector cannot separate them |
| C. The same setting without a selector; the engine never retries a cause a retry cannot change, and records each superseded attempt on the task run | The author bounds the attempts, Flow decides what is pointless to retry | Two budgets on `retry`; an attempt history on the run |

## Decision

C. `retry.max` and `retry.backoff` are copied from the node onto the task run when its task list is built, as
`timeout` is, so the end path reads one document (`engine/DAGUtility.java:312-323`, `common/model/RunRetry.java:21-25`).
A reported `failed` end takes the requeue a timed-out claimant takes while `retry.failures` is below `retry.max` and
the reason is not in `StatusReason.NOT_RETRIABLE` (`engine/TaskRunService.java:1175-1223`), on the node's
budget; the could-not-start reasons keep the engine's `retry.count` budget. Every requeue, the engine's reaps
included, pushes the attempt it supersedes onto `attempts` (`:830`), which the public `TaskRun` exposes
beside `attempt`; `retry` stays on the entity. The ceiling is `EngineConstants.MAX_FAILURE_RETRIES`.

## Consequences

- An author's retries are Flow's: bounded, backed off, visible as attempts, and `RateLimited` goes back to meaning
  "the task never started".
- A failure the engine refuses to retry is a fixed list, not a per-node choice; a per-reason selector can be added
  later without a migration, since an absent selector means every retriable failure.
- The webapp's task panel edits the setting and the run's task view lists the attempts; the dispatcher contract is
  unchanged.

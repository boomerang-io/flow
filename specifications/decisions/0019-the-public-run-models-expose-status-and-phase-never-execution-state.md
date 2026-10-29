# 0019 — The public run models expose `status` as the external field, keep `phase` beside it, and never carry execution state

**Status:** accepted · **Date:** 2026-08-18

## Context

A run carries `status` (the outcome, `RunStatus`) and `phase` (the engine's position, `RunPhase`), and its entity holds
claim, deadline, retry and pause state so any instance can claim, fence and reap work. If those internals reach
`/api/v2`, clients build on scheduling details that change with the execution model.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Shape: A. Public models extend the entities and hide fields with `@JsonIgnore` | Fewest classes | One forgotten annotation leaks a field; the entity shape becomes the API shape |
| Shape: B. Standalone public classes with an explicit field list, pinned by a serialisation test | The contract must be stable and reviewable | A mapping step on every read |
| Phase: C. Drop `phase` from the public models | Every client reads `status` only | The webapp's run header, approval and retry screens read it; `queued` has no `status` equivalent |
| Phase: D. Keep `phase` beside `status` as a recorded deviation | Some clients still need it | The public API carries an internal field others may start to depend on |

## Decision

B and D. `WorkflowRun` and `TaskRun` are standalone classes (`common/model/WorkflowRun.java:38`,
`common/model/TaskRun.java:18-28`), and `PublicRunModelSerialisationTest` fails the build if `claim`, `timeoutAt`,
`retry`, `retryAfter`, `waitUntil`, `pauseRequestedAt`, `waitingSince`, `agentRef`, `dispatcherRef`, `dependencies`,
`preApprovedStatus` or `decisionValue` is serialised (`service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:56-69`).
Pause shows only as the derived boolean `paused` (`WorkflowRun.java:53-54`), and a claimed task's `attempt` is derived
from its hidden retry count (`TaskRun.java:60-71`). `phase` stays on both models because the webapp reads it and the list
routes filter on it; a dispatcher reads its own wire models instead (decision 0037). The tripwire
`phaseIsStillSerialisedOnThePublicModels` (`PublicRunModelSerialisationTest.java:172-185`) asserts the deviation and
is inverted when `phase` leaves. What decided it: an explicit list makes every public field a deliberate edit.

## Consequences

- Entity fields can be added or renamed without an API change; a new execution-state field joins the test's forbidden
  set, not just the entity.
- Clients treat `phase` as informational and `status` as the contract. Removing `phase` needs a `status`-based
  replacement for the webapp's reads and a way to show `queued`.

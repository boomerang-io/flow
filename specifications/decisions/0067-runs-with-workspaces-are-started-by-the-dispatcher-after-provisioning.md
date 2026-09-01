# 0067 — A run that declares workspaces is started by the dispatcher after provisioning; the provisioning claim takes only such runs

**Status:** accepted · **Date:** 2026-09-02

## Context

`submit?start=true` started every run directly, so a run with workspaces was `running` before any dispatcher
could claim it for provisioning and its claims were never created; meanwhile the provisioning claim query took
every `ready`/`pending` run, so a run deliberately parked with `start=false` was started by the first connected
dispatcher.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Engine provisions storage itself before starting | the engine may touch the cluster | breaks the rule that only the dispatcher touches infrastructure |
| B. Start directly, let the dispatcher create claims lazily when the first task needs them | pods can wait on a claim | a pod that starts before the claim exists fails to schedule; two tasks racing create two claims |
| C. A run with workspaces waits at `ready`/`pending` for a dispatcher to provision and start it; the provisioning claim requires `workspaces.0`; a run without workspaces starts at once | the dispatcher is the only infrastructure actor | a run with workspaces needs a connected dispatcher to start, which it needs to execute anyway |

## Decision

Option C. `WorkflowRunService.run` skips the direct start when the run has workspaces and logs why;
`WorkflowRunStateHelper.findClaimableForProvision` and `tryClaimForProvision` require `workspaces.0` to exist,
mirroring the teardown pair (`service-core/src/main/java/io/boomerang/engine/WorkflowRunStateHelper.java`). The
dispatcher provisions the claims and calls `PUT /api/v1/dispatcher/workflowrun/{id}/start`, as it always did.

## Consequences

- A run parked with `start=false` stays parked until someone calls `PUT /{id}/start`.
- A run with workspaces on a stack without a dispatcher never starts; the run-level timeout reaps it — the
  same outcome its tasks would have had.
- Trigger to revisit: an executor that needs no provisioning step for storage (for example a Docker executor
  with named volumes) — then the claim criterion becomes executor-declared rather than `workspaces.0`.

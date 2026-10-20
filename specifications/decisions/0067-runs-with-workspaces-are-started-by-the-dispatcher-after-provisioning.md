# 0067 — A run that declares a workspace is started by the dispatcher after provisioning; the provisioning claim takes only such runs

**Status:** accepted · **Date:** 2026-09-02

## Context

`submit?start=true` started every run directly, so a run with a workspace was `running` before any dispatcher
could claim it for provisioning and its claims were never created; meanwhile the provisioning claim query took
every `ready`/`pending` run, so a run deliberately parked with `start=false` was started by the first connected
dispatcher.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Engine provisions storage itself before starting | the engine may touch the cluster | breaks the rule that only the dispatcher touches infrastructure |
| B. Start directly, let the dispatcher create claims lazily when the first task needs them | pods can wait on a claim | a pod that starts before the claim exists fails to schedule; two tasks racing create two claims |
| C. A run with a workspace waits at `ready`/`pending` for a dispatcher to provision and start it; the provisioning claim requires `workspace` to be present; a run without one starts at once | the dispatcher is the only infrastructure actor | a run with a workspace needs a connected dispatcher to start, which it needs to execute anyway |

## Decision

Option C. `WorkflowRunService.run` skips the direct start when the run has a workspace and logs why (`service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:709-714`);
`WorkflowRunStateHelper.findClaimableForProvision` and `tryClaimForProvision` require `workspace` to exist (`engine/WorkflowRunStateHelper.java:48-78`),
keyed by the run id (decision 0104). The
dispatcher provisions the claim and calls `PUT /dispatcher/v1/workflowrun/{id}/start` (`contracts/dispatcher-v1.yaml:37`).

## Consequences

- A run parked with `start=false` stays parked until someone calls `PUT /{id}/start`.
- A run with a workspace on a stack without a dispatcher never starts; the run-level timeout reaps it — the
  same outcome its tasks would have had.
- Trigger to revisit: an executor that needs no provisioning step for storage (for example a Docker executor
  with named volumes) — then the claim criterion becomes executor-declared rather than the presence of `workspace`.

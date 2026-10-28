# 0137 — A dispatcher reports a failed provisioning attempt

**Status:** accepted · **Date:** 2026-10-29

## Context

A dispatcher that claims a run with a workspace and cannot create its storage told the engine nothing. The run sat
`ready/queued` with no message; the stale-claim sweep released it after 5 minutes, and the third stale claim failed it
with "did not complete after 3 attempts", about 15 minutes after submit and with the cause only in the dispatcher's
log. A Kubernetes cluster that was off produced exactly this.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The dispatcher reports the failure; the engine shows it and retries after a short delay | The dispatcher knows why it failed | An additive dispatcher route; older dispatchers keep the sweep's behaviour |
| B. The sweep writes "retrying (attempt n of 3)" when it releases a claim | No contract change is wanted | The cause stays unknown; each attempt still waits 5 minutes |
| C. Release the claim as soon as a failure is reported | Failures are momentary | A dispatcher that cannot reach its cluster spends all three attempts in seconds |

## Decision

A, with a delay. `PUT /dispatcher/v1/{id}/workflowrun/{workflowRunId}/provision-failed {message}`
(`dispatcher/DispatcherControllerV1.java`) applies only to a run that dispatcher holds. Below the last attempt the
engine keeps the claim, writes "Workspace provisioning failed: <cause>. Retrying in 1 minute (attempt n of 3)." and
ends the claim's `leaseExpiresAt` after `flow.watcher.provision-retry-ms` (60 s); the stale-claim sweep releases a
claim whose lease has ended as well as one older than its grace. The last attempt fails the run with the cause
(`engine/WorkflowExecutionService.java`, `engine/WorkflowRunStateHelper.java`). The SDK reports the innermost cause,
best effort (`dispatcher-sdk/.../WorkflowRunner.java`).

## Consequences

- A cluster that is off shows its cause on the run at once and fails it in about 2–3 minutes, not 15.
- `claim.leaseExpiresAt`, unused by provisioning claims before, now marks a held one; no field is added.
- A run's start clears its `statusMessage`, so the retry message never outlives provisioning.
- A dispatcher built on an older SDK never calls the route and keeps the 5-minute release.

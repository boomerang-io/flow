# 0124 — A claim carries a lease from the moment it is made

**Status:** accepted · **Date:** 2026-10-20

## Context

A claim had no lease until its dispatcher's first heartbeat, up to 30 s later. A dispatcher that crashed in
that window and registered again under the same name and host got the same id back within the 60 s it takes to
count as gone, so neither the lease sweep nor the gone-dispatcher sweep ever fired, and its newest claims waited
for their task deadline, or for ever when the task had no timeout.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The claim sets the first lease; heartbeats renew it | every dispatcher heartbeats what it holds | a dispatcher that sends no heartbeat has every claim requeued 90 s after it was made, so heartbeats become a MUST and the Kubernetes dispatcher's switch to turn them off goes |
| B. A registration under an existing id starts a new epoch, and claims from before it are requeued | dispatchers never restart while their work keeps running | Flow's Kubernetes dispatcher restarts while its pods run on; requeuing them duplicates work, and the epoch is a new field on the registration |

## Decision

Option A. `TaskRunService.tryClaim` writes `claim.leaseExpiresAt` = claim time + `flow.dispatcher.lease-ms` in the
claim's own write (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:290`, `:316`), and
`renewLeases` moves it on each heartbeat (`:614`). The SDK already heartbeats every task from the moment its claim
arrives (`dispatcher-sdk/.../InFlightTasks.java`, `Dispatcher.java:190`), so the first heartbeat lands at most 30 s
after a claim, well inside the lease. It needs no new field, index or migration, and leaves a restarted
dispatcher's running work alone as long as it reports it.

## Consequences

- A claim lost in a crash is requeued within the lease plus one sweep, whatever the dispatcher id does next.
- Heartbeats are required: `flow.dispatcher.lease.enabled` is gone from `service-dispatcher`, and an SDK
  dispatcher built with a zero heartbeat interval loses every claim after 90 s.
- Revisit if a dispatcher needs to hold a claim it cannot report on for longer than the lease.

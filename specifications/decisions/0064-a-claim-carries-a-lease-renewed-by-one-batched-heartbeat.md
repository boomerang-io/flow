# 0064 — A claim carries a lease from the moment it is made, renewed by one batched heartbeat per dispatcher

**Status:** accepted · **Date:** 2026-10-20

## Context

Without a lease apart from its deadline, a task whose dispatcher crashed waits out that deadline, or for ever when it
has none. The gone-dispatcher sweep misses a dispatcher that restarts under the same name and host: it gets the same id
back (`service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:143-160`) inside the 60 s that marks a
dispatcher gone (`engine/WorkflowWatcher.java:74`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One heartbeat request per task | Few tasks in flight | 200 tasks in flight make 6.7 requests a second per dispatcher |
| B. The poll lists the ids in flight | No extra request is wanted | Couples the lease to the claim poll and to `tryClaim` |
| C. The claim sets the first lease; one batched heartbeat per dispatcher renews it | Many tasks in flight; every dispatcher reports what it holds | One request per 30 s whatever the count; heartbeats become a MUST |
| D. A registration under an existing id starts a new epoch, and older claims are requeued | Dispatchers never restart while their work runs on | Flow's Kubernetes dispatcher restarts while its pods run, so requeuing duplicates work; a new field on the registration |

## Decision

C. `tryClaim` writes `claim.leaseExpiresAt` = claim time + `flow.dispatcher.lease-ms` (90 s) in the claim's own write
(`engine/TaskRunService.java:333`, `:339`). `dispatcher-sdk` holds a task in flight from the moment its claim arrives until the
engine has accepted or refused its end (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/InFlightTasks.java:8-12`,
`TaskRunner.java:94-100`, `:157-158`) and sends every id in flight, across its pools, in one `PUT /dispatcher/v1/{id}/heartbeat`
every 30 s (`Dispatcher.java:50`, `:190-200`, `:291-300`); Flow's Kubernetes dispatcher takes the interval from
`flow.dispatcher.lease.beat-ms` (`service-dispatcher/src/main/java/io/boomerang/config/DispatcherConfig.java:48`). One
`updateMulti` renews the ids the caller still holds, queued or running (`TaskRunService.java:622-634`); `reapExpiredLeases`
requeues or abandons a lapsed claim with `statusReason=LeaseExpired` (`WorkflowWatcher.java:482-496`). It adds no field.

## Consequences

- A claim lost with its dispatcher, whatever id the dispatcher comes back under, is requeued within the lease plus one
  sweep interval. A hung handler keeps its lease renewed; the task's own deadline bounds it (decision 0014).
- The lease MUST stay at least twice the beat interval plus one sweep interval; 90 s is the floor
  (`service-core/src/main/resources/application.properties:44-47`). A completion clears it and an abandon keeps it, so a
  lost end is reaped again (`TaskRunService.java:597-604`, `:711`).
- Heartbeats are required: a `dispatcher-sdk` dispatcher built with a zero interval loses every claim after 90 s
  (`Dispatcher.java:437-445`). Revisit if a dispatcher must hold a claim it cannot report on for longer than the lease.

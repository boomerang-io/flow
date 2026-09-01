# 0064 — Claimed tasks carry a lease renewed by one batched heartbeat per dispatcher

**Status:** accepted · **Date:** 2026-09-01

## Context

A task whose pod died while the dispatcher's Kubernetes watch was closed, or whose dispatcher thread died,
was recovered only when its `timeoutAt` elapsed — sixty minutes by default — because `claim.leaseExpiresAt`
was declared and indexed but never written (decision 0018). Every comparable execution tier studied in
`competitive-analysis.md` renews a lock separate from the job timeout and detects a dead worker within a minute.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One `PUT` per task per heartbeat | few tasks in flight | 200 in-flight tasks = 6.7 requests per second per dispatcher |
| B. The dispatcher lists in-flight ids on its existing poll | zero extra requests wanted | presence-of-field semantics; couples the lease to the claim poll and to `tryClaim` |
| C. One batched `PUT` per dispatcher per heartbeat, ids stamped by the executor threads | many tasks in flight; dead threads must be detected | one request per 30 s regardless of task count; a new endpoint |

## Decision

Option C. Each executor thread stamps `LeaseRegistry` before its first wait and on every reconcile pass
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java`, `watch`); `LeaseHeartbeat` sends
`PUT /api/v1/dispatcher/{id}/heartbeat {ids}` every `flow.dispatcher.lease.beat-ms` (30 s); the engine renews
`claim.leaseExpiresAt` for the ids the caller still owns in one `updateMulti`
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java`, `renewLeases`) with
`flow.dispatcher.lease-ms` (90 s); the `reapExpiredLeases` sweep requeues or abandons a lapsed claim with
`statusReason=LeaseExpired` (`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java`). A thread that
dies stops stamping, so its id drops out of the batch and its lease lapses. The first heartbeat creates the lease:
a dispatcher that never heartbeats holds no lease and is recovered exactly as before.

## Consequences

- A dead pod, a dead executor thread and a dead dispatcher are all recovered within the lease plus one sweep
  interval, against the task timeout before.
- The lease MUST stay at least twice the beat interval plus one sweep interval; 90 s is the floor, not a knob to lower.
- A requeued task can duplicate a pod only when its dispatcher is gone; the executor adopts an existing Job by
  label before creating one, which closes the remaining case.

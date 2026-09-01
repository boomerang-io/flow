# 0018 — Worker leases and lease renewal are deferred

**Status:** accepted · **Date:** 2026-08-14

## Context

The queue design paired two guards on every claimed task: `timeoutAt` ("is the work over budget?") and a renewed
`leaseExpiresAt` ("is the claimant alive?"), with a batched renew call from each worker. The deadline shipped; the
lease protocol did not, and there is no renew endpoint or lease-reap sweep.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Ship the 180 s lease with a 60 s batched renew and a lease-reap sweep | dead-worker recovery must be faster than the task budget | a renew endpoint, protocol change on every dispatcher, and a rejected-claim path the worker must honour |
| B. Rely on `timeoutAt` plus a dispatcher-liveness check | recovery within the task budget (or 60 s of dispatcher silence) is acceptable | a slow-but-healthy task and a dead worker are told apart only by the dispatcher's last connection time |

## Decision

Option B for now. `claim.leaseExpiresAt` stays declared on `RunClaim` (`lib-common/src/main/java/io/boomerang/common/model/RunClaim.java:21`)
and indexed (`service-loader/src/main/java/io/boomerang/loader/migration/_0017__RunIndexes.java:82`) but is written
nowhere — only ever `unset`. Recovery is the deadline sweep plus `reapClaimsFromGoneDispatchers`, which requeues a
claimed task whose dispatcher has not connected for 60 s (`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:69`,
`:326-361`). Nothing consumes a lease today, so building the protocol would be work without a consumer.

## Consequences

- The `lease_sweep` index is permanently empty; documentation MUST NOT describe leases as a working guard.
- Recovering a claim re-dispatches the task while the original executor may still be running it, so the 60 s
  staleness window cannot be tightened until the dispatch protocol can cancel in-flight work.
- Trigger to revisit: worker-crash recovery latency proves to matter in operation.

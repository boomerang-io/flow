# 0014 — Timeouts and crash recovery use a periodic sweep, not per-run timers

**Status:** accepted · **Date:** 2026-07-23

## Context

Timeouts used to be in-memory scheduled futures and wall-clock checks inside handlers: a restart forgot every timer,
the engine reaped healthy work at exactly its budget while the dispatcher granted extra provisioning time, and a
crashed dispatcher's tasks were stuck forever because nothing observed the loss.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-run timers in memory | single instance that never restarts | lost on restart; unrecoverable after a crash; one instance must own each run |
| B. A durable `timeoutAt` deadline on the run, reaped by a periodic sweep on every instance | any instance count; crash recovery required | up to one sweep interval of latency after the deadline |
| C. Leases renewed by the worker plus the deadline | fast detection of a dead worker is required | a renew endpoint and protocol on every worker |

## Decision

Option B. `timeoutAt` is baked at claim and start as start time + budget + 5 s grace
(`service-core/src/main/java/io/boomerang/engine/RunTimeouts.java:14-18`) and cleared at completion.
`WorkflowWatcher.reapTaskTimeouts` and `reapWorkflowTimeouts` (`engine/WorkflowWatcher.java:151-187`) are the only
reapers; they run on every instance every 30 s. Crash recovery is the same path: a requeueable task past its
deadline is requeued with backoff up to 3 attempts, and `reapClaimsFromGoneDispatchers` (`:326-361`) shortcuts the
wait when the dispatcher has not connected for 60 s. Both reap writes are fenced on the observed `claim.seq`. The
deadline in the database is the one place a restart cannot lose.

## Consequences

- A run's timeout MUST be at least the transport timeout of the work it guards; `RestConfig` gives every HTTP
  template real timeouts so the invariant holds.
- Recovery latency is bounded by the sweep interval plus the deadline; leases were deferred (decision 0018).
- Gates, waits and inline system tasks time out terminally; only dispatcher-executed types are requeued
  (`WorkflowWatcher.java:53-56`).

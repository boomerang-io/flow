# 0014 — Timeouts and crash recovery use a durable deadline and a periodic sweep, not per-run timers

**Status:** accepted · **Date:** 2026-07-23

## Context

Timeouts used to be in-memory scheduled futures and wall-clock checks inside handlers: a restart forgot every timer,
the engine reaped healthy work at exactly its budget while the dispatcher granted extra provisioning time, and a
crashed dispatcher's tasks were stuck forever because nothing observed the loss. A timeout audit also found three of
four outbound templates with no read timeout, so a hung connection could hold a thread past any run guard.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-run timers in memory | single instance that never restarts | lost on restart; unrecoverable after a crash; one instance must own each run |
| B. A durable `timeoutAt` deadline on the run, reaped by a periodic sweep on every instance, with every transport bounded beneath it | any instance count; crash recovery required | up to one sweep interval of latency after the deadline |
| C. Leases renewed by the worker plus the deadline | fast detection of a dead worker is required | a renew endpoint and protocol on every worker |

## Decision

Option B, later joined by C (decision 0064). `timeoutAt` is baked at claim as start time + budget + 5 s grace
(`service-core/src/main/java/io/boomerang/engine/RunTimeouts.java:14-18`, `EngineConstants.java:13`,
`TaskRunService.java:297-305`) and cleared at completion. `WorkflowWatcher.reapTaskTimeouts` and `reapWorkflowTimeouts`
(`engine/WorkflowWatcher.java:202,232`) are the only reapers; they run on every instance every 30 s (`:133-137`). Crash
recovery is the same path: a requeueable task past its deadline is requeued with backoff up to 3 attempts
(`EngineConstants.java:34`), and `reapClaimsFromGoneDispatchers` (`:451`) shortcuts the wait when the dispatcher has
not connected. Both reap writes are fenced on the observed `claim.seq`. Every outbound template carries real transport
timeouts — connect 10 s, idle read 60 s, pool lease 10 s (`core/config/RestConfig.java:50-52`), 10 min idle read on the
streaming template, 600 s async request timeout (`application.properties:72`) — and a run or task timeout MUST be at
least the transport timeout of the work it guards, so a guard never fires beneath a still-open, healthy connection.
The deadline in the database is the one place a restart cannot lose.

## Consequences

- Recovery is deadline-based and instance-agnostic: any instance reaps a past-deadline run, within one sweep interval.
- Grace composes downward (executor budget plus provisioning grace); anyone adding an outbound call or a work class
  MUST set both the transport timeout and the guard above it and check the inequality. Nothing validates it at submit.
- Gates, waits and inline system tasks time out terminally; only dispatcher-executed types are requeued
  (`EngineConstants.java:23`).

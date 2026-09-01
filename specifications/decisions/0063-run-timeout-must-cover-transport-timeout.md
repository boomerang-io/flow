# 0063 — A run timeout must be at least the transport timeout of the work it guards

**Status:** accepted · **Date:** 2026-07-23

## Context

A timeout audit found the invariant "run timeout ≥ transport timeout of the guarded work" violated in
4 of 6 work classes: three of four outbound templates had no read timeout at all, log streams died at
the servlet's 30 s async default, and the engine could reap a task at exactly its budget while the
executor still granted a provisioning grace. Timeouts were also enforced in memory and lost on crash.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Leave transport unbounded and rely on run-level timeouts | The guarded work always completes or fails on its own | A hung connection holds a thread forever; the run guard fires with no transport-level cause and the thread survives it. |
| B. Bound every transport, keep run timeouts independent | Timeouts are set per call site by hand | Two unrelated constants can drift: a 60 s read under a 30 s guard reaps healthy work. |
| C. Bound every transport AND require every run/task deadline to be ≥ the transport timeout beneath it, with a durable deadline field | Deadlines must survive crashes and instance changes | Grace must compose downward (executor budget + provisioning grace) and be applied consistently. |

## Decision

Option C. Every template carries real transport timeouts (`service-core/src/main/java/io/boomerang/core/config/RestConfig.java:35-38,48-54`),
and the deadline is a durable field: `timeoutAt` is written at claim time as now + task timeout + 5 s
grace (`engine/TaskRunService.java:251,260`; `engine/RunTimeouts.java:14-17`;
`engine/EngineConstants.java:9`) and reaped by the sweep, not an in-memory timer
(`engine/WorkflowWatcher.java:151-178`). A run or task timeout MUST be at least the transport timeout
of the work it guards, so a guard can never fire beneath a still-open, healthy connection.

## Consequences

- Crash recovery is deadline-based and instance-agnostic: any instance reaps a past-deadline run.
- Long-running transports need their own budget: the streaming template reads for 10 min and the async
  request timeout is 600 s (`service-core/src/main/resources/application.properties:49`).
- Anyone adding a new outbound call or a new work class MUST set both the transport timeout and the
  guard above it, and check the inequality; nothing validates it automatically at submit today.

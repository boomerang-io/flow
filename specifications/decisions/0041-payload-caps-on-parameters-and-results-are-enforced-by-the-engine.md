# 0041 — Payload caps on parameters and results are enforced by the engine

**Status:** accepted · **Date:** 2026-08-25

## Context

Each execution substrate has its own hard limit: 128 KiB per environment string and a 4096-byte
termination message on Kubernetes, roughly 1.5 MiB per pod object, 4 KB of environment on a serverless
function. Without a Flow-level cap an oversize task fails differently on every executor — a container that
crashes at exec on one, a truncated result on another — and the Tekton executor could only detect a result
overflow after the fact by tailing the pod log.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Rely on each substrate's own limit | One executor only | Different failure per executor; some are silent truncation |
| B. Each executor enforces a cap before submit | The dispatcher owns the contract | Duplicated logic; the task is already claimed when it fails |
| C. The engine enforces one cap at admission (params) and at end (results) | Any number of executors | Two configuration values to tune per install |

## Decision

C. Resolved params are serialised and compared with `flow.engine.task.params.max-bytes` (16384) before the
admission compare-and-set; an oversize task is invalidated with `PARAMS_TOO_LARGE` and never becomes
claimable (`service-core/src/main/java/io/boomerang/engine/TaskExecutionService.java:207-212`). Reported
results are compared with `flow.engine.task.results.max-bytes` (1048576, a storage guard) in
`TaskRunService.end`; an oversize task ends `failed` with `RESULTS_TOO_LARGE`, `statusReason=ResultsTooLarge`,
and the results are not persisted (`engine/TaskRunService.java:57-58,983-992`). Beneath the guard each
dispatcher applies its own substrate limit and reports the same reason: on Kubernetes the 4096-byte
termination message (`service-dispatcher/src/main/java/io/boomerang/kube/KubeServiceImpl.java:253`). One
clear engine message on every executor is the reason.

## Consequences

- Task authors see the same message regardless of where the task runs; the effective limit is the lower of
  the engine guard and the dispatcher's substrate.
- Values above the cap are passed by reference: a workspace path, or an artifact (decision 0085).
- Raising the engine guard above a substrate's own limit changes nothing on that substrate; the dispatcher's
  limit is the one a task on it meets first.

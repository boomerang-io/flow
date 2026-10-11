# 0096 — Starting is bounded apart from running, and a task that never started is handed back

**Status:** accepted · **Date:** 2026-10-05

## Context

A quota refusal at create failed the task for good. A pod stuck on an image pull or unschedulable waited out
the whole task timeout - by default the run's - before it was reported. A task cancelled while being handed
over still had its pod created. Every comparable executor separates "waiting to start" from running time and
treats quota as temporary (Tekton, Argo, Airflow, Jenkins, the Kubernetes Job controller).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep failing at create and waiting out the deadline | Nothing ever waits | Hours lost to an image typo; a full cluster fails tasks |
| B. Retry inside the dispatcher while holding the claim | Only one cluster can run the task | The timeout keeps running; the work stays stuck on the full cluster |
| C. Bound the start in the dispatcher; hand temporary failures back to the engine's requeue | Default | Two new end reasons the engine must recognise |

## Decision

C. The dispatcher fails an image it cannot pull, or a container it cannot create, after
`kube.timeout.startFailureGraceSeconds` (60), and reports a pod still Pending after `kube.timeout.startMinutes`
(15) as `StartTimeout`, deleting the runtime object either way (`service-dispatcher/.../executor/PodStartWatch.java`).
A quota refusal at create is `ExceededQuota` (`dispatcher/TaskService.java:89`). The engine requeues those two
on a timed-out claimant's budget (`service-core/.../engine/TaskRunService.java:977`). Before creating anything
the dispatcher reads the engine's `start` answer and skips a task already finished (`client/EngineClient.java:123`).

## Consequences

- A full cluster delays a task instead of failing it; an image mistake fails in about a minute.
- Pods lost after starting (eviction, preemption) are still reported as ordinary failures, not retried.
- The retry budget is shared with timeouts: three requeues in all.

# 0096 — Starting is bounded apart from running, and a task that never started is handed back

**Status:** accepted · **Date:** 2026-10-05

## Context

A pod held on an image pull, or one no node can schedule, would otherwise wait out the whole task timeout before it is
reported, and a quota refusal would fail a task that never ran. Comparable executors (Tekton, Argo, Airflow, Jenkins,
the Kubernetes Job controller) bound "waiting to start" apart from running time and treat quota as temporary.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Fail at create, and let a stuck pod wait out the task deadline | Nothing ever waits to start | Hours lost to an image typo; a full cluster fails tasks |
| B. Retry inside the dispatcher while holding the claim | Only one cluster can run the task | The timeout keeps running, and the work stays on the full cluster |
| C. Bound the start in the dispatcher, and hand a task that never started back to the engine's requeue | Any number of dispatchers | Typed end reasons the engine must recognise |

## Decision

C. On either executor the Kubernetes dispatcher fails an image it cannot pull (`ImagePull`), or a container it cannot
create (`DispatchError`), after `kube.timeout.startFailureGraceSeconds` (60), and reports a pod still not running after
`kube.timeout.startMinutes` (15; 0 turns it off) as `StartTimeout`, deleting the runtime object either way
(`service-dispatcher/src/main/java/io/boomerang/executor/PodStartWatch.java:43-70`, `kube/KubeJobsExecutor.java:419-431`,
`kube/TektonServiceImpl.java:492-500`). A namespace quota refusing the object is `ExceededQuota`
(`service-dispatcher/src/main/java/io/boomerang/dispatcher/TaskService.java:140-146`). The engine requeues a `failed` end carrying `StartTimeout`, `ExceededQuota` or
`RateLimited` through the same fenced transition a timed-out claimant takes, while the task's type is requeueable and
its budget lasts (`service-core/src/main/java/io/boomerang/common/model/StatusReason.java:44-45`,
`engine/TaskRunService.java:1088-1111`). Before running anything `dispatcher-sdk` reads the engine's start answer and skips a
task already finished or refused (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/TaskRunner.java:163-183`).
Handing the task back frees it for any dispatcher, where retrying in place would keep it on the full cluster.

## Consequences

- A full cluster delays a task instead of failing it; an image mistake fails in about a minute.
- A pod lost after it started (eviction, preemption) is an ordinary failure and is not retried.
- The budget is shared with timeouts, lapsed leases and gone dispatchers: three requeues in all
  (`engine/EngineConstants.java:34`), each after the shared backoff (decision 0060).

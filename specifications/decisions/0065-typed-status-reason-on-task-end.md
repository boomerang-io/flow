# 0065 — A task ends with a typed `statusReason` beside its `statusMessage`

**Status:** accepted · **Date:** 2026-09-01

## Context

Free text such as `"<reason> - <message>"` cannot tell an out-of-memory kill from an image-pull failure or a non-zero
exit without parsing strings. Anything the engine may decide on must be a typed field (decision 0020), and every
comparable system studied (Code Engine, Tekton, Google Cloud Batch, Trigger.dev) carries a closed reason vocabulary.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A retry-policy class on the wire (`generic`, `ratelimit`, `terminal`) | Retry classes are being built | A derived classification with no consumer |
| B. The observed cause as `statusReason`, a closed string set beside `status` and `statusMessage` | The cause must be recorded, and policy may follow | One field on the end request and the task run |

## Decision

B. `TaskRunEndRequest`, `TaskRun` and `TaskRunEntity` carry `statusReason` (`service-core/src/main/java/io/boomerang/common/model/TaskRunEndRequest.java:30`,
`common/model/TaskRun.java:47`, `common/entity/TaskRunEntity.java:49`). The closed set lives in one class
(`common/model/StatusReason.java:11-45`): a dispatcher sends `DispatchError`, `DeadlineExceeded`, `JobFailed`,
`JobDeleted`, `OOMKilled`, `ImagePull`, `ImageNotAllowed`, `AdmissionDenied` or `ResultsTooLarge`, or, for a task that
never started, `ExceededQuota`, `StartTimeout` or `RateLimited`; the engine writes `DispatcherGone`, `LeaseExpired` and
`DeadlineExceeded` on its reaps (`engine/WorkflowWatcher.java:228`, `:467`, `:492`) and its own causes for refused
specs, parameters, artifacts, for-each items and child runs. The `StatusReason` in `dispatcher-sdk` is a subset, checked by its tests
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/StatusReason.java:8-47`), and Flow's Kubernetes dispatcher maps
Job and TaskRun conditions and the pod's container state onto it (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:458-478`,
`kube/TektonServiceImpl.java:541-565`).
A reason the engine does not know is recorded as an ordinary failure. The name pairs with `status` and `statusMessage`
rather than starting a second vocabulary, and policy derives from it.

## Consequences

- The one policy built on it is the requeue of a task that never started: `StartTimeout`, `ExceededQuota` and
  `RateLimited` (`common/model/StatusReason.java:44-45`, `engine/TaskRunService.java:1088-1111`); any further retry policy derives
  from it without a wire change.
- Insights and status events carry the reason (`workflow/WorkflowRunInsightService.java:347`,
  `event/model/RunStatusSummary.java:88-99`).
- Cancel and delete carry no reason; add values only when something consumes them.

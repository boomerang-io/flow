# 0065 — A task ends with a typed `statusReason` beside its `statusMessage`

**Status:** accepted · **Date:** 2026-09-01

## Context

The dispatcher reported a failure as free text (`"<reason> - <message>"`), so nothing downstream could tell an
out-of-memory kill from an image-pull failure from a non-zero exit without parsing strings. The typed-fields rule
(decision 0020) requires anything the engine may decide on to be a field, and every comparable system studied —
Code Engine, Tekton, Google Cloud Batch, Trigger.dev — carries a closed reason vocabulary.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A retry-policy class on the wire (`generic`, `ratelimit`, `terminal`) | retry classes are being built | a derived classification with no consumer; the earlier plan that never shipped |
| B. The observed cause as `statusReason`, a closed string set paired with `status` and `statusMessage` | the cause must be recorded now, policy may follow later | a new field on the end request and the run record |

## Decision

Option B. `TaskRunEndRequest`, `TaskRun` and `TaskRunEntity` carry `statusReason`
(`lib-common/src/main/java/io/boomerang/common/model/TaskRunEndRequest.java`); the closed set is `DeadlineExceeded`,
`JobDeleted`, `JobFailed`, `OOMKilled`, `ImagePull`, `AdmissionDenied`, `ResultsTooLarge`, `DispatchError`,
`DispatcherGone`, `LeaseExpired`. The dispatcher maps Job and TaskRun condition reasons and the pod's container
status onto it in `TaskExecutionException` (`service-dispatcher/src/main/java/io/boomerang/error/TaskExecutionException.java`);
the engine sets it on deadline reaps, abandons and oversize results. The name pairs with `status`/`statusMessage`
rather than introducing a second vocabulary; a retry-policy class, if ever built, is derived from it.

## Consequences

- Operators and the UI can filter failures by cause; an opt-in "retry only these reasons" policy becomes possible
  without a further wire change.
- Cancel and delete failures carry no reason yet; add values only when something consumes them.

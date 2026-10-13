# 0081 — Leftover Tekton runtime objects are cleaned by our own reconciler

**Status:** accepted · **Date:** 2026-09-23

## Context

`kube.task.deletion` is a point-in-time decision at task end (`Never` default, `OnSuccess`,
`Always`), so under `Never` — and whenever a delete is missed — finished runtime objects accumulate
forever. The Kubernetes Jobs executor already expires its Jobs via `ttlSecondsAfterFinished` from
`kube.task.ttlDays`, but Tekton ships no TTL controller, so on the default executor the same
property was read into a field and never used: nothing ever swept a leftover TaskRun.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Tekton's external pruner (the operator's pruner CronJob or `tkn delete` jobs) | The cluster already runs the Tekton operator | An extra install outside our settings; its retention knob is not `kube.task.ttlDays`, so the two executors diverge and an unpruned cluster is an operator mistake we cannot see |
| B. A dispatcher-side reconciler mirroring the workspace pattern | Cleanup should follow the dispatcher's own configuration and labels | One more scheduled tick listing the namespace's TaskRuns |
| C. Leave `Never` unbounded | Runs are rare and audited by hand | etcd and list latency grow without bound; the TTL property stays a lie on the default executor |

## Decision

Option B. `dispatcher/TaskRuntimeReconciler.java` runs only on the `tekton` executor, and each tick
lists the TaskRuns carrying this dispatcher's own product and tier labels, deletes those with a
terminal `Succeeded` condition whose `completionTime` has outlived `kube.task.ttlDays`, and logs one
`held/deleted` summary; a failed tick warns and the next tick asks again — the same best-effort,
level-triggered shape as `dispatcher/WorkspaceReconciler.java`. It is the only reader of
`kube.task.ttlDays` on the Tekton path; the dead field in `kube/TektonServiceImpl.java` is gone.

## Consequences

- `kube.task.ttlDays` now means the same on both executors: a finished runtime object is removed that many days after completion regardless of `kube.task.deletion` — Jobs natively, TaskRuns by this reconciler.
- The label scope means the reconciler never touches an unlabelled or foreign object; equally, a TaskRun someone created by hand is never cleaned.
- The tick lists the whole labelled set unpaged; revisit with paging if a namespace holds tens of thousands of retained TaskRuns.

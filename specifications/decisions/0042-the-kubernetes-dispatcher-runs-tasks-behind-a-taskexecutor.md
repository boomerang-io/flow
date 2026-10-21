# 0042 — Flow's Kubernetes dispatcher runs tasks behind a `TaskExecutor`, one isolation tier per deployment, and cleans its own leftovers

**Status:** accepted · **Date:** 2026-09-23

## Context

Every task is "run one image to completion with a command or script, a timeout, volumes and a small result", which a
plain Kubernetes Job does as well as Tekton. Some deployments need tasks sandboxed (gVisor, Kata) through the pod's
`runtimeClassName`, and Tekton ships no time-to-live controller, so finished TaskRuns pile up under `kube.task.deletion=Never`.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Runtime: A. Tekton only, or Jobs replacing it | Every cluster has Tekton; or none depends on it | Tekton stays a hard install; or existing deployments break |
| Runtime: B. A narrow `TaskExecutor` with Tekton and Jobs implementations, chosen by configuration | Both must coexist and more runtimes may follow | The interface stays only as wide as what both share |
| Isolation: C. A per-task isolation field routed to a matching dispatcher | Mixed trust tiers behind one dispatcher | A spec field and queue routing; an author can ask for a tier the cluster lacks |
| Isolation: D. One `runtimeClassName` per dispatcher deployment | Trust tiers map to deployments | A second tier is a second deployment |
| Cleanup: E. Tekton's external pruner | The Tekton operator is installed | Retention outside the dispatcher's settings; the two executors diverge |
| Cleanup: F. A dispatcher-side reconciler | Cleanup follows the dispatcher's own settings and labels | One more scheduled tick listing the namespace's TaskRuns |

## Decision

B, D and F. `TaskExecutor` has four methods, `create`, `watch`, `cancel`, `delete`
(`service-dispatcher/.../executor/TaskExecutor.java:12-28`); `dispatcher.executor` selects `tekton` (default) or
`kube-jobs` (`kube/TektonServiceImpl.java:56-57`, `kube/KubeJobsExecutor.java:70-71`), a Job whose timeout is
`activeDeadlineSeconds` and whose results are its termination message (`:205-206,231-232`). Both apply
`dispatcher.tasks.runtimeClassName` to every task (`KubeJobsExecutor.java:219-220`, `TektonServiceImpl.java:410-414`);
a deployment needing another tier registers as its own dispatcher, and claims already route by registered task type
(`dispatcher/DispatcherService.java:357-358` in `service-core`). On Tekton, `service-dispatcher/.../TaskRuntimeReconciler.java:51-89`
hourly deletes this dispatcher's labelled TaskRuns that finished more than `kube.task.ttlDays` (7) ago, as Jobs expire by
`ttlSecondsAfterFinished`. What decided it: no field, route or install ahead of a real need, and one retention for both.

## Consequences

- A cluster runs Flow tasks without Tekton; a task image is portable, as both executors share environment, volumes,
  isolation and result cap. Each in-flight task blocks one virtual thread in `watch`.
- The reconciler never touches an unlabelled TaskRun, and lists the labelled set unpaged; revisit with paging if a
  namespace holds tens of thousands. Revisit isolation only if one deployment needs mixed tiers.

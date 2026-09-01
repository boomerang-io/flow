# 0046 — Task execution runs behind a `TaskExecutor` interface with Tekton and plain Kubernetes Jobs

**Status:** accepted · **Date:** 2026-08-21

## Context

The dispatcher drove Tekton directly: one class built an inline-spec `TaskRun`, watched it, cancelled it by
overwriting its status and read results from its status. Every Tekton feature actually used amounted to
"run one image to completion with a script or command, a timeout, some volumes and a small result", and
installing Tekton Pipelines was the only reason a cluster needed it. The reference codebases run the same
shape as a plain Kubernetes Job.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep Tekton as the only runtime | Every target cluster already runs Tekton | Tekton stays a hard install dependency; a Docker or sandbox runtime has nowhere to plug in |
| B. Replace Tekton with Jobs outright | No installation depends on Tekton | Breaks existing deployments; loses a working, tested path |
| C. A small interface with Tekton and Jobs as two implementations, selected by configuration | Both must coexist and more runtimes are planned | The interface must stay as narrow as what both share |

## Decision

C. `TaskExecutor` has four methods — `create`, `watch`, `cancel`, `delete`
(`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-27`) — and `dispatcher.executor`
selects exactly one bean: `tekton` (`kube/TektonServiceImpl.java:53`, the default) or `kube-jobs`
(`kube/KubeJobsExecutor.java:65`, a `batch/v1` Job whose timeout is `activeDeadlineSeconds` and whose
results come from the container termination message). `TaskService` calls the interface and nothing else
(`dispatcher/TaskService.java:64-68`). Removing the Tekton dependency for clusters that do not want it,
without breaking the ones that do, decided it.

## Consequences

- A cluster can run Flow tasks with no Tekton install; Tekton remains the default.
- Both executors share the same env vars, volumes, isolation setting and result cap, so a task image is
  portable between them.
- Later runtimes (local Docker, a serverless sandbox) implement the same four methods; if they need more
  than these, the interface grows only then.
- Both implementations still hold one blocked thread per in-flight task during `watch`.

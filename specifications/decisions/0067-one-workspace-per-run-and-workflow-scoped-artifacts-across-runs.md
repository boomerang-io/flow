# 0067 — One workspace per run, provisioned and released by the dispatcher; state across runs is a workflow-scoped artifact

**Status:** accepted · **Date:** 2026-10-13

## Context

A run's tasks share one filesystem that only a dispatcher can create on its infrastructure and that must go when the
run does. Files that outlive a run, or exceed the 1 MB result cap, need another home people can download from.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Volume: A. A `workspaces` list with a persistent per-workflow volume | Cross-run state must be a mounted filesystem | Pins every run to one node under ReadWriteOnce; Kubernetes executors only; reconciled for the workflow's life |
| Volume: B. One `workspace` per run; cross-run state as workflow-scoped artifacts | The run's filesystem is the only volume | Kept files move through an upload and a download task |
| Start: C. The engine provisions storage, or the first task creates the claim | The engine may touch the cluster; pods can wait on a claim | Breaks the dispatcher-only rule; two racing tasks create two claims |
| Release: D. A `finalized` phase or a teardown record on the run | Cleanup outcomes must reach a user | Execution state on the run; a lost callback strands the run and still leaks the volume |
| Artifacts: E. Declared artifacts deleted at run end, or a list on `WorkflowRun` | Artifacts only pass data between tasks | Nothing to download afterwards; or storage totals scan and rewrite every run |

## Decision

B, with provisioning before start and release by reconciliation, both in the dispatcher. Presence of `workspace` is the
switch (`common/model/WorkflowWorkspace.java:9-19`); a task mounts it at `/workspace` unless its node says
`workspace: false` (`engine/DAGUtility.java:259-269`). A run with one is never started at submit
(`workflow/WorkflowRunService.java:796-803`): the provisioning claim takes only ready runs that have one
(`engine/WorkflowRunStateHelper.java:57-102`), and the dispatcher creates one volume keyed by the run id, then calls
`PUT /dispatcher/v1/workflowrun/{id}/start`. `completed` is terminal (`common/enums/RunPhase.java:11-15`): the dispatcher
deletes the volumes whose runs `POST /dispatcher/v1/workspaces/releasable` names as completed or unknown
(`dispatcher/DispatcherService.java:498-521`). An artifact exists once an upload task stores it through a 15-minute
single-object link: a record in `artifacts` reached through its run, whose run-scoped file expires while the record
stays; a workflow-scoped one never expires, is read by name by later runs, and is expired by the next verified upload of
its name (`workflow/ArtifactService.java:206-232,274-284,340-372`). What decided it: only the dispatcher touches
infrastructure, the cluster knows what it still holds, and a named artifact works on every executor.

## Consequences

- A run with a workspace needs a connected dispatcher to start; with none it waits at `ready` until cancelled. A v3
  workflow that declared Workflow storage is refused at submit until saved again (`workflow/WorkflowService.java:1846-1848`).
- The engine cannot say whether a volume was released. Artifact limits are checked after upload, since the link
  precedes the file: an upload that breaks one is deleted and its task fails.

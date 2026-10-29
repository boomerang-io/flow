# 0025 — One workspace per run, provisioned and released by the dispatcher; a run's workspace is its workflow's owner

**Status:** accepted · **Date:** 2026-10-29

## Context

A run's tasks share one filesystem that only a dispatcher can create on its infrastructure and that must go when the run
does; files that outlive a run need another home. The engine also needs the run's owning workspace for its parameter
layer, secret redaction and lock scope, and the operator's task settings for each task run; a workspace's name can
change, and the engine decides only from typed fields (decision 0009).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Volume: a `workspaces` list with a persistent per-workflow volume; or one `workspace` per run with cross-run state as workflow-scoped artifacts | Cross-run state must be a mount; or the run's filesystem is the only volume | Pins every run to one node; or kept files move through an upload and a download task |
| Start and release: the engine provisions; a `finalized` phase on the run | The engine may touch the cluster | Breaks the dispatcher-only rule; execution state on the run |
| Owner: annotations stamped at submit, or typed fields copied onto every run | Nothing reads a run after a rename; the workspace can differ | The engine decides from metadata; or a second record of what the graph holds |
| Owner: derived from the workflow's owner, defaults onto each task run at creation | A run's workspace is always its workflow's | One cached graph read per workflow |

## Decision

One `workspace` per run, provisioned before start and released by reconciliation, both in the dispatcher, and the owner
derived. Presence of `workspace` is the switch (`common/model/WorkflowWorkspace.java:9-19`); a task mounts it at
`/workspace` unless its node says otherwise (`engine/DAGUtility.java:259-269`). A run with one is never started at
submit: the provisioning claim takes it (`engine/WorkflowRunStateHelper.java:57-102`), the dispatcher creates one volume
keyed by the run id and calls `PUT /dispatcher/v1/workflowrun/{id}/start`, and deletes the volumes whose runs `POST
/dispatcher/v1/workspaces/releasable` names as completed or unknown. An artifact is a record in `artifacts` stored
through a 15-minute single-object link; a run-scoped file expires, a workflow-scoped one is read by name by later runs.
`ParamLayerService.owningWorkspace` reads the workspace that owns the run's workflow and caches it per workflow
(`workflow/ParamLayerService.java:91-103`); the parameter layer, the secret values and the lock key `<workspace
id>:<key>` use it, and `DAGUtility.createTaskList` sets each new task run's `timeout`, `spec.image` and `spec.deletion`
from the `task` settings (`engine/DAGUtility.java:74-85,184,246,279,308`).

## Consequences

- A run with a workspace needs a connected dispatcher to start; a v3 workflow that declared Workflow storage is refused
  at submit until saved again. The engine cannot say whether a volume was released.
- A rename keeps a run in flight on its workspace's parameters and lock scope; task settings apply as they stand when the
  run is queued. Revisit if a run ever needs a workspace other than its workflow's.

# 0128 — A run's workspace is its workflow's owner, and task defaults land on the task run

**Status:** accepted · **Date:** 2026-10-21

## Context

The engine needs a run's workspace to choose its parameter layer, the secret values to redact and the lock scope, and
needs the operator's task settings (timeout, default image, deletion policy) for each task run. A workspace's name can
change (`workspace/WorkspaceService.java:270-273`), and the engine decides only from typed fields (decision 0020).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Annotations on the run, stamped at submit with the workspace name and the task settings | Nothing reads a run after its workspace is renamed | The engine decides from metadata; after a rename a run in flight reads another workspace's parameters and locks |
| B. Typed fields on the run: a workspace id and a task-defaults element | The workspace or the defaults can differ from the workflow's | Two fields copied onto every run, a second record of what the graph and the settings already hold |
| C. Derive them: the workspace from the workflow's owner in the relationship graph, the defaults onto each task run's own fields at creation | A run's workspace is always its workflow's, and a task run already has the fields | One cached graph read per workflow; the settings read once per run at queue |

## Decision

C. `ParamLayerService.owningWorkspace` reads the workspace that owns the run's workflow by id and caches it per
workflow, since a workflow never changes workspace (`workflow/ParamLayerService.java:91-103`,
`core/ParamLayerCache.java:92-94`); the parameter layer, the secret values and the lock key `<workspace id>:<key>`
(`engine/TaskExecutionService.java:1061-1069`) all use it. `DAGUtility.createTaskList` reads the `task` settings once
and sets each new task run's `timeout`, `spec.image` and `spec.deletion` from them (`engine/DAGUtility.java:74-85,184,246,279,308`).
An early event's status is the typed `preApprovedStatus` on the task run (`common/entity/TaskRunEntity.java:51`). What
decided it: nothing new is stored, and a rename cannot move a run.

## Consequences

- A rename keeps a run in flight on its workspace's parameters and lock scope.
- The task settings apply as they stand when the run is queued; a run submitted without starting takes them at start.
- Run annotations hold user metadata and the `boomerang.io/generation` and `kind` markers
  (`workflow/WorkflowService.java:1913-1914`); a client's keys under `boomerang.io/` are dropped
  (`workflow/WorkflowRunService.java:826-828`).
- Revisit if a run ever needs a workspace other than its workflow's.

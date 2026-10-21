# 0128 — A run's workspace is its workflow's owner, and task defaults land on the task run

**Status:** accepted · **Date:** 2026-10-21

## Context

The engine decided from run annotations stamped at submit: `boomerang.io/workspace-name` chose the parameter layer,
the redacted secret values and the lock scope; `task-timeout`, `task-default-image` and `task-deletion` set each task
run; an early event's status sat in a `status` annotation on the task run. That broke the rule that the engine
decides from typed fields, and the name was wrong after a rename: a workspace's name can change
(`workspace/WorkspaceService.java:267-268`), so a run in flight looked up parameters under a name that no longer
existed, or that a newer workspace had taken, and its locks no longer matched new runs'.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the annotations, written only by the server | Nothing reads a run after its workspace is renamed | The rule stays broken; the rename problems stay |
| B. Typed fields on the run: a workspace id and a task-defaults element | The workspace or the defaults could differ from the workflow's | Two new fields copied onto every run, a backfill, and a second record of what the graph and the settings already hold |
| C. Derive them: the workspace from the workflow's owner in the relationship graph, the defaults onto the task run's own fields when it is created | A run's workspace is always its workflow's, and a task run already has the fields | One cached graph read per workflow; settings read once per run at queue |

## Decision

C. `ParamLayerService.owningWorkspace` reads the workspace that owns the run's workflow (`workspace --hasWorkflow-->
workflow`), by id, and caches it per workflow, since a workflow never changes workspace
(`workflow/ParamLayerService.java:91`, `core/ParamLayerCache.java:92`); the parameter layer, the secret values and
the lock key `<workspace id>:<key>` all use it. `DAGUtility.createTaskList` reads the `task` settings once and sets
each new task run's `timeout`, `spec.image` and `spec.deletion` from them, as a run's `timeout` field holds its
resolved value (`engine/DAGUtility.java:72-85`). An early event's status is `preApprovedStatus` on the task run.
`_0028__RunSettingsOutOfAnnotations` removes the annotations from stored runs.

## Consequences

- A rename keeps a run in flight on its workspace's parameters and lock scope.
- The task settings apply as they stand when the run is queued, not when it was submitted; a run submitted without
  starting takes them at start.
- Run annotations hold user metadata and the run's `generation` and `kind` markers; `boomerang.io/` stays reserved,
  and a client's keys under it are dropped.
- Revisit if a run ever needs a workspace other than its workflow's.

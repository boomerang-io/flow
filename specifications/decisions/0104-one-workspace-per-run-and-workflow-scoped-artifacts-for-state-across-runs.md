# 0104 — One workspace per run; state across runs is a workflow-scoped artifact

**Status:** accepted · **Date:** 2026-10-13

## Context

A workflow declared a `workspaces` list typed `workflow` or `workflowrun`. The `workflow` type was a persistent
volume keyed by the workflow: it pinned every run to one node under ReadWriteOnce, only Kubernetes executors
could mount it, and the dispatcher had to reconcile it for the life of the workflow. With it gone the list had one
legal entry, and the only external author (Cheer) ever declared one. Files that must survive between runs still
need a home.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the list with one type `workflowrun` | A second volume type is expected soon | The API carries a type with one value; the three-way inheritance rule stays; Flow 4's duplicated form shape had already produced three bugs |
| B. One `workspace` object on the workflow, run and task run; a boolean on the task node | The per-run filesystem is the only volume | Rewrites the dispatcher contract and the v3 migrator in the same release |
| C. Keep the `workflow` volume and fix its reconciliation | Cross-run state must be a mounted filesystem | Keeps every cost above; still unavailable to non-Kubernetes executors |

## Decision

B, with cross-run state moved to artifacts uploaded with `scope: workflow`. Presence of `workspace` is the switch
(`lib-common/.../model/WorkflowWorkspace.java`); a task mounts it unless its node says `workspace: false`
(`engine/DAGUtility.java:229`); the dispatcher provisions one claim keyed by the run id and the releasable query names
runs only (`dispatcher/DispatcherService.java:389-408`). A workflow-scoped artifact is downloadable by any later run of
its workflow by name, never expires, and is replaced by the next verified upload of that name, which expires the
previous record (`workflow/ArtifactService.java:69-72,312`). The comparable systems all key cross-run state by name, not by
volume, and a keyed artifact works on every executor. v3 workflows that declared Workflow storage are marked on
upgrade and refused at submit until saved again, so the loss is loud rather than silent.

## Consequences

- One default mount, `/workspace`; upgraded v3 workflows keep `/workspace/workflowrun` pinned explicitly.
- The `quotas` group has one storage cap for the run workspace (`max.workspace.storage`); kept artifacts hold the
  artifact quota until replaced or deleted.
- Revisit if a second backing for the run
  workspace arrives (an artifact-synced workspace for executors without volumes), which fits as a field on the
  object, not a second list entry.

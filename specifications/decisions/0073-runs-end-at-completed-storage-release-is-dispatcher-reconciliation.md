# 0073 — A run ends at `completed`; releasing its storage is the dispatcher's own reconciliation

**Status:** accepted · **Date:** 2026-09-15

## Context

`RunPhase` carried a sixth value, `finalized`, one step past `completed`. It existed for one job: a
run that declared workspaces was claimed a second time by a dispatcher, which deleted the run-scoped
volume and then called `PUT /workflowrun/{id}/finalize`, and a watcher sweep finalised the
workspace-less runs no dispatcher would ever claim. So the run record carried cleanup state, every
phase guard had to name two terminal values, and a run whose dispatcher died before the callback sat
`completed` forever while its volume leaked anyway.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `finalized`, or inline the finalize into the delete callback | The callback is reliable and someone reads the value | Nobody reads it; a lost callback still leaks the volume and now also strands the run |
| B. A typed teardown record on the run (`releasedAt`, outcome) | Cleanup outcomes must be shown to a user or queried | New execution state on the run, a new public/internal split to police, and the cluster still disagrees with it after any crash |
| C. No run state at all: the dispatcher reconciles what it holds against the engine | Cleanup is a fact about the resource, not about the run | The engine cannot report whether a volume was released |

## Decision

Option C. The dispatcher lists the volumes it actually holds and asks
`POST /api/v1/dispatcher/workspaces/releasable` which of their owners are finished — a run that is
completed or gone, a workflow that is deleted or gone (`dispatcher/DispatcherService.java:288`) —
then deletes those; the run record says nothing about release, and `completed` is terminal
(`lib-common/.../enums/RunPhase.java:11-14`). Every comparable system does the same: Kubernetes
Jobs, Tekton, Argo Workflows and Azure Container Apps Jobs all stop at a terminal state and clean up
by TTL or garbage collection against the live resource. None of them models cleanup as a further
state of the run, because the cluster — not the record — is what still holds the thing.

## Consequences

- Phase guards, the finalize transition, its sweep, its two routes and the teardown half of the workflow queue are all gone; a crashed dispatcher no longer strands a run, and a new dispatcher releases volumes it never provisioned.
- The engine cannot answer "was this run's storage released?"; only the cluster can. Revisit if that outcome has to reach a user.
- 0050's remaining reason to expose `phase` is `queued` — the one position `status` cannot express. Closing that gap is now the whole of the public/dispatcher model split.
- Databases carrying the old value are rewritten forward by `_0043__RunPhaseFinalizedIsCompleted`; nothing reads `finalized` any more.

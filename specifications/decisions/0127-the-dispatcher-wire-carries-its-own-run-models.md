# 0127 — The dispatcher wire carries its own run models

**Status:** accepted · **Date:** 2026-10-20

## Context

The task and workflow queues and the start and end answers returned the public `TaskRun` and `WorkflowRun`, so
every field added for the webapp or a host — run history, retry lineage, approval flags — also reached every
dispatcher, and the contract, the dispatcher software development kit (SDK) and any hand-written dispatcher had to
track it. A dispatcher reads 15 of the task run's 26 fields and 6 of the workflow run's 27.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One model for both surfaces | Both audiences read the same fields | Every public field is a protocol field; the SDK grows with the webapp |
| B. One model, a `@JsonView` per surface | The split is small and stable | A property without a view is in every view, so a new public field reaches dispatchers unless its author remembers |
| C. Wire records built from the public model at the controller | The audiences differ | Two small records to keep; a field a dispatcher needs is added on purpose |

## Decision

C. `DispatchTaskRun` and `DispatchWorkflowRun` (`service-core/.../dispatcher/model/`) carry only what Flow's
Kubernetes dispatcher, the SDK runtime and the in-process dispatcher read. `DispatcherControllerV1` maps the
public model the service has already prepared, keeping its status and headers
(`dispatcher/DispatcherControllerV1.java:260-265`), so claiming, the spec fill, the artifact link and the slug
`taskRef` in `DispatcherService` are unchanged. The schemas take the record names, so the whole-product OpenAPI
document holds both shapes without a name clash.

## Consequences

- A field added to a public run model no longer reaches a dispatcher. One a dispatcher needs is added to the record,
  the contract and the SDK together, as an additive change.
- The SDK's `TaskRun` and `WorkflowRun` lost the fields no dispatcher read (`dispatcher-contract.md`, "Changes");
  removing response fields on `/dispatcher/v1` was allowed only because 5.0.0 has not shipped.
- `phase` on the public models no longer serves a dispatcher; whether it stays is a question for the public
  surface (`api-contract.md`).

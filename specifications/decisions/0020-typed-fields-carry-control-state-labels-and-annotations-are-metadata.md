# 0020 — Typed fields carry control and execution state; labels and annotations are metadata, and the server's labels sit under `boomerang.io/`

**Status:** accepted · **Date:** 2026-10-21

## Context

Runs and task runs carry free-form `labels` and `annotations`. Annotations are not indexed and a `boomerang.io/*` key
is stored escaped (`core/config/MongoConfiguration.java:36-37`), so queue and sweep queries cannot use them. An embedder
groups by the model an `ai` task used and the item a for-each child ran for, which no client-set label can carry.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| State: A. Control state in `boomerang.io/*` annotations | A few keys, never queried | Un-indexable keys; orchestration coupled to a metadata map |
| State: B. Typed fields for anything the engine reads or queries | The engine pages, indexes and compares-and-sets on it | Each new control field is a schema change |
| Server metadata: C. Server labels under a reserved `boomerang.io/` prefix | Grouping and filtering, which labels already serve and index | Labels stop being the client's alone; a value must never carry a secret |
| Server metadata: D. The same as filterable annotations | Labels stay the client's alone | Two new indexes, and filters on values that may be objects |
| Server metadata: E. A `labels` param on the run-workflow node, or a child workflow per agent | A node author picks the label; or no Flow change | Mixes metadata into params; or the embedder edits a copy per agent |

## Decision

B and C. Anything the engine reads to decide, or queries and indexes on, is a typed field: retry lineage is
`initiatedByRef` plus `trigger` and the count `retryCount` (`common/entity/WorkflowRunEntity.java:71-76`); claims,
deadlines and pause are `claim`, `timeoutAt`, `retry`, `waitUntil` and `pauseRequestedAt`
(`common/entity/TaskRunEntity.java:76-88`); an early event's status is `preApprovedStatus` (`:51`); a run's workspace
and task defaults are derived, not stamped (decision 0128). `ServerLabel` reserves the prefix
(`common/model/ServerLabel.java:10-32`): admission sets `boomerang.io/model` from the resolved `model` param unless it
is secret (`engine/TaskExecutionService.java:228-238`); the fan-out sets `boomerang.io/item` on each item task run, and the run-workflow task
on a for-each child, when the item is text holding no secret value of the run's parameter layers (`:747-755`, `:1179-1188`,
`engine/ParameterManager.java:341-345`); every client-set label under the prefix is dropped
(`workflow/WorkflowRunService.java:825`, `workflow/WorkflowService.java:643`). The engine reads neither label to decide
anything. Kubernetes (spec and status versus annotations), Tekton, Argo and Temporal draw the same line.

## Consequences

- Claim, sweep and pause queries run on indexed fields; `RunStatus` stays closed. `ControlStateFieldsTest` pins that
  the retired annotation keys are never written.
- `groupBy=label:boomerang.io/model` and `label:boomerang.io/item` serve per-model and per-agent Insights and filter the
  run and task-run queries; the model is the one asked for at admission, and a nested child takes its own item.

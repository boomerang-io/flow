# 0020 — Control and execution state are typed fields; annotations and labels are metadata only

**Status:** accepted · **Date:** 2026-07-24

## Context

Runs carry a free-form `annotations: Map<String,Object>` in the `boomerang.io/*` namespace, and earlier code kept
retry lineage, retry counts and the timeout cause there. MongoDB cannot take `.` in a map key, so those keys were
stored escaped (`boomerang#io/...`), could not be indexed, and were invisible to the queue and sweep queries.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep control state in annotations | The set of control keys is small and never queried | Un-indexable escaped keys; orchestration coupled to a Kubernetes-shaped metadata map; the datastore fights every new key |
| B. Typed fields for anything the engine reads or queries; annotations and labels for metadata only | The engine must page, index and compare-and-set on the state | Each new control field is a schema change (an appended loader unit) |

## Decision

Option B. Anything the engine reads to make a decision, or queries and indexes on, MUST be a typed field; labels
and `boomerang.io/*` annotations are non-identifying metadata (UI, catalogue, user tags). This is the norm in every
comparable system (Kubernetes spec/status versus annotations, Tekton, Argo, Temporal, Airflow, n8n). Retry lineage
became `initiatedByRef` + `trigger`, the retry count became `retryCount`
(`service-core/src/main/java/io/boomerang/common/entity/WorkflowRunEntity.java:72-77`), and the timeout cause needed
no field because it only ever selected a `statusMessage`. `ControlStateFieldsTest` pins that the retired keys are
never written; `PublicRunModelSerialisationTest` pins that control fields never serialise publicly.

## Consequences

- Claim, sweep and pause queries run on indexed typed fields (`claim`, `timeoutAt`, `retry`, `waitUntil`, `pauseRequestedAt`).
- `RunStatus` stays a closed enum; pause and supersede are orthogonal fields, never status values.
- Nothing is read from annotations. The task settings are applied to each task run's own fields when it is
  created (`engine/DAGUtility.java:71-84`); a run's workspace is its workflow's owner in the relationship graph
  (`workflow/ParamLayerService.java:91`); an early event's status is `preApprovedStatus` on the task run. The
  parameter layers left annotations with decision 0093 and are read from their stores.

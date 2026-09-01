# 0045 — A pass-by-reference artefact store for large payloads

**Status:** proposed · **Date:** 2026-08-27

## Context

The engine now rejects oversize params and results with "pass large values by reference" (decision 0041),
but the platform offers no reference mechanism beyond the per-run or per-workflow workspace volume:
cluster-only, provisioned block storage with bare path strings and no integrity check. Argo (artifacts to
object storage) and Conductor (automatic externalisation above a threshold) are the precedents; Tekton
deliberately has none.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Workspace volumes only | Every executor mounts the same shared volume | No integrity, no cross-executor passing, provisioned storage per run |
| B. One central object store, per-run key prefix, presigned URLs per declared artefact, recorded SHA-256 | Executors without shared volumes; evidence needs content hashes | New reference format on `RunResult`, an `artifacts` declaration on the task spec, store deployment |
| C. Per-workspace storage provisioned by the platform | Tenants demand physically separate buckets | Provisioning cost and lifecycle per tenant; rejected in the walkthrough |

## Decision

B is the design, deferred until one of its triggers is met (boomerang-io/flow#319): a production workflow
fails on the engine caps with a genuine need to move larger data between tasks; an executor without shared
volumes (sandbox, VM) needs task-to-task data; or the evidence ledger needs recorded output hashes. The
shape recorded there: a single bucket with `wfrun-<runId>/<name>` keys deleted at finalize, short-lived
single-object presigned URLs injected as `ARTIFACT_<NAME>_URL` so tasks never hold store credentials, a
`flowblob://` reference with hash and size on the result, and hash verification on consume. Nothing is
built; `TaskRunService.end` still fails an oversize result outright
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:765-773`).

## Consequences

- The reference format, the artefact declaration and the hash fields are data-model changes and MUST be
  presented for discussion before implementation, together with encryption at rest (flow#315).
- Until then, large data moves only through workspace volumes on executors that mount them.

# 0040 — Parameters reach a task by engine substitution and environment variables, within engine-enforced caps

**Status:** accepted · **Date:** 2026-08-25

## Context

A task must receive its parameters the same way on Tekton, Kubernetes Jobs and any later executor, and a plain
vendor image (`curl`, `alpine`) must use them without a Flow library. Each substrate has its own hard limit (128 KiB
per environment string, a 4096-byte termination message), so without a Flow cap an oversize task fails differently.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Delivery: `/params/<name>` files from a projected ConfigMap | Every task uses the Flow task library | A plain image never reads them; one extra object per task |
| Delivery: one `PARAMS` variable holding the map as JSON | Only the catalogue reads params | A plain image cannot parse it |
| Delivery: a `PARAM_<NAME>` variable per param plus `PARAM_NAMES`, and engine substitution of `$(params.x)` into script, command, arguments and envs | Any image, any executor | Names fold to `[A-Z0-9_]`; substituted text is visible in the pod spec |
| Caps: each substrate's own limit | One executor | A different failure per executor, some of them silent truncation |
| Caps: each executor checks before submit | The dispatcher owns the contract | Duplicated logic; the task is already claimed when it fails |
| Caps: the engine checks params at admission and results at end | Any number of executors | Two values to tune per install |

## Decision

Per-param variables with engine substitution, and engine caps. The engine resolves a task's params before admission
and fills the spec's references into the copy a dispatcher receives at claim
(`service-core/src/main/java/io/boomerang/engine/ParameterManager.java:144-238,288-314`). The dispatcher exports
`PARAM_<NAME>` and `PARAM_NAMES`, task-defined variables winning on a clash
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:124-163`). Resolved params over
`flow.engine.task.params.max-bytes` (16384) end the task `invalid` before it is claimable
(`engine/TaskExecutionService.java:211-226`); results over `flow.engine.task.results.max-bytes` (1048576, a storage
guard) end it `failed` with `ResultsTooLarge` and are not stored (`engine/TaskRunService.java:1037-1051`). A non-Flow
image understands only its command line and its environment, and one engine message reads the same everywhere.

## Consequences

- Every executor delivers identical inputs; none substitutes on its own. Beneath the engine guard each dispatcher
  applies its substrate's limit with the same reason, on Kubernetes the 4096-byte termination message
  (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:497-508`).
- Structured or large inputs and outputs travel by reference: a workspace path or an artifact (decision 0067).
- Folded names can collide (`my-key`, `MY_KEY`); decision 0044 rejects them at save.

# 0040 — Parameters reach a task by engine-side substitution and environment variables

**Status:** accepted · **Date:** 2026-08-22

## Context

A task container must receive its parameters the same way on Tekton, Kubernetes Jobs and any later
executor, and a plain vendor image (`curl`, `alpine`) must be able to use them without a Flow library. Until
this decision the engine substituted `$(params.x)` only inside the task's own param values, Tekton's
controller substituted the script and arguments, and a per-task ConfigMap projected `/params/<name>` files —
so the Jobs executor received unsubstituted scripts and a plain image had no usable channel.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. `/params/<name>` files from a projected ConfigMap | The task uses the Flow task library | A plain image never reads `/params`; 1 MiB ConfigMap ceiling; one extra object per task |
| B. One `PARAMS` env var holding the whole map as JSON | Only the catalogue reads params | A plain image cannot parse JSON from an env var; 128 KiB single-string ceiling; no secret story |
| C. One `PARAM_<NAME>` env var per param plus `PARAM_NAMES` | Any image, Flow-aware or not | Names are folded to `[A-Z0-9_]`, so a manifest is needed to round-trip the original names |
| D. Engine-side substitution of `$(params.x)` into script, command, arguments and envs | Any image and any executor | The substituted text is visible in the pod spec |

## Decision

C and D together are the contract; A and B were removed. The engine resolves references into the TaskRun's
params and spec fields before admission (`service-core/src/main/java/io/boomerang/engine/ParameterManager.java:111,121-136`)
and persists the resolved copy with the admission compare-and-set
(`engine/TaskExecutionService.java:181`). The dispatcher exports `PARAM_<NAME>` and `PARAM_NAMES`, with
explicit task env vars winning on collision (`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:111-149`).
Universality decided it: a non-Flow image understands only its command line and its environment.

## Consequences

- Every executor delivers identical inputs; the Jobs executor needs no substitution of its own.
- Structured or large inputs belong on a workspace mount, with the param carrying the path.
- The task library moved from `/params` files to the environment (`@boomerang-io/task-core`).
- Folded names can collide (`my-key` and `MY_KEY`); decision 0044 rejects such names at save.

# 0016 — Parameters are layered as in v3, resolved by the engine from their stores, and delivered as environment variables

**Status:** accepted · **Date:** 2026-10-29

## Context

A task must receive its parameters the same way on every executor, and a plain vendor image must use them without a Flow
library. `$(params.x)` is v3's flattened view of the global, team, workflow and context layers, modelled on UrbanCode
Deploy; scoped values are needed at run time without copying secrets onto the run; names reach a container folded to
`PARAM_<NAME>`, so `myKey` and `my-key` collide; and a result or a webhook payload is untrusted text.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Delivery: `/params/<name>` files or one `PARAMS` JSON variable; or a `PARAM_<NAME>` variable per param plus `PARAM_NAMES`, engine substitution into the spec, and engine caps | Every task uses the Flow library; or any image on any executor | A plain image never reads them; or names fold to `[A-Z0-9_]` |
| Layering: the task's own params are the nearest layer | A task param composes from a sibling | `url: $(params.url)` reads itself; upgraded v3 workflows fail |
| Layering: a task param reads global → workspace → workflow → context; only the spec reads the task's params (v3) | Upgraded v3 workflows keep working | No composing from a sibling, which v3 never offered |
| Values: resolved onto every task run at start, or read from their stores at run start and each admission | One set per run; or live values | Resolved secrets on task runs that never execute; or an edit reaches tasks not yet admitted |
| Names: exact and case-sensitive (Tekton, Argo), or case-insensitive with folded variants rejected at save (GitHub Actions) | An unfolded channel; a folded one | Two params overwrite one variable; or unique only up to the fold |

## Decision

Per-param variables with engine substitution and caps, v3 layering, stores read live, and case-insensitive names.
`resolveParamLayers` resolves a task's params before admission from layers built without the task's own params
(`service-core/src/main/java/io/boomerang/engine/ParameterManager.java:144-238`, `:534-571`), the secret taint follows
the same layers (`:459-490`), and `fillSpec` fills the spec's references, the task's params included, at claim
(`:288-314`). The dispatcher exports `PARAM_<NAME>` and `PARAM_NAMES` (`kube/KubeHelperService.java:124-163`); params
over `flow.engine.task.params.max-bytes` end the task `invalid` and results over `flow.engine.task.results.max-bytes`
end it `failed` (`engine/TaskExecutionService.java:211-226`, `engine/TaskRunService.java:1037-1051`). Layers are read
from their stores through a 10-second cache (`workflow/ParamLayerService.java:140-180`); a task result and a supplied
run param are inserted as written (`ParameterManager.java:80-86,579-641`). References match case-insensitively, names
that fold alike fail with `PARAM_NAME_COLLISION` at save (`workflow/WorkflowService.java:1580-1588`), and an empty value
is valid.

## Consequences

- `x: $(params.x)` passes the workflow's `x` through; a param naming only a sibling is reported as undeclared. Large
  data travels by workspace path or artifact (decision 0025). Parameter layering is in the review gate in `CLAUDE.md`.
- No parameter values sit on the run document; `team.params` goes in the next major version; `names` is reserved.

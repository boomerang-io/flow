# 0044 — Parameter names match case-insensitively and colliding variants are rejected at save

**Status:** accepted · **Date:** 2026-08-26

## Context

Parameters reach a container as `PARAM_<NAME>` environment variables, where the name is upper-cased and
every character outside `[A-Za-z0-9_]` becomes `_` (decision 0040). That fold is lossy: `myKey`, `my-key`
and `MY_KEY` all become `PARAM_MY_KEY`, and the catalogue's own params are camelCase (`privateKey`,
`spreadsheetId`), so forcing lowercase would break existing workflows.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Exact, case-sensitive matching (Tekton, Argo, Airflow) | The delivery channel is not folded | Two params can silently overwrite one env var |
| B. Case-insensitive matching plus rejection of case or separator variants (GitHub Actions) | The delivery channel folds names | Names are unique only up to the fold; the merge must keep declared casing |
| C. Enforce lowercase names | Nothing existing uses mixed case | Breaks the catalogue's camelCase params and every workflow using them |

## Decision

B. `$(params.x)` resolves over a case-insensitive view of the parameter layers
(`service-core/src/main/java/io/boomerang/engine/ParameterManager.java:238`), the node-value merge is
case-insensitive with the declared casing winning
(`lib-common/src/main/java/io/boomerang/common/util/ParameterUtil.java:57-66`), and names that fold to the
same env var fail with `PARAM_NAME_COLLISION` at workflow save
(`service-core/.../workflow/WorkflowService.java:1593-1599`) and task save (`workflow/TaskService.java:366-374`).
GitHub Actions is the one mainstream system with a folded env channel, and it pairs insensitivity with a
uniqueness rule for exactly this reason.

## Consequences

- `PARAM_NAMES` stays, because JavaScript destructuring in the task library is case-sensitive.
- The dispatcher keeps its dispatch-time collision check as a backstop for definitions saved earlier
  (`service-dispatcher/.../kube/KubeHelperService.java:131-140`).
- Any variant of `names` is a reserved parameter name (`ParameterUtil.java:85-89`).

# 0044 — Parameter names match case-insensitively with colliding variants rejected at save, and an empty value is valid

**Status:** accepted · **Date:** 2026-09-02

## Context

Parameters reach a container as `PARAM_<NAME>` variables, upper-cased with every character outside `[A-Za-z0-9_]`
folded to `_` (decision 0040), so `myKey`, `my-key` and `MY_KEY` collide; the catalogue's own params are camelCase
(`privateKey`, `spreadsheetId`). A value can also legitimately be empty: a substitution may resolve to nothing, and
emptiness can mean something to a workflow.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Names: exact, case-sensitive matching (Tekton, Argo, Airflow) | The delivery channel is not folded | Two params silently overwrite one variable |
| Names: case-insensitive matching plus rejection of folded variants (GitHub Actions) | The delivery channel folds names | Names are unique only up to the fold; the merge must keep declared casing |
| Names: lowercase only | Nothing uses mixed case | Breaks the catalogue's camelCase params |
| Empty values: rejected at save | Every parameter always needs a value | Breaks substitutions that resolve to empty |
| Empty values: accepted; a task that needs one fails its run naming the parameter | Requirements are per task | A definition defect surfaces at run time |

## Decision

Case-insensitive names with variants rejected, and empty values accepted. References resolve over a case-insensitive
view of the layers (`service-core/src/main/java/io/boomerang/engine/ParameterManager.java:789-790`); the node-value
merge keeps the declared casing (`common/util/ParameterUtil.java:123-144`); names that fold alike fail with
`PARAM_NAME_COLLISION` at workflow save (`workflow/WorkflowService.java:1580-1588`) and catalogue task save
(`workflow/TaskService.java:362-372`). GitHub Actions, the one mainstream system with a folded channel, pairs
insensitivity with the same rule. Save checks names only (invalid, colliding, undeclared); a value may be empty or
absent (`workflow/WorkflowService.java:1565-1610`). A run-workflow node with no target fails its own run with
"Parameter 'workflowRef' resolved to no value" (`engine/TaskExecutionService.java:1116-1123`).

## Consequences

- `PARAM_NAMES` stays: the task library's JavaScript destructuring is case-sensitive.
- The dispatcher keeps a collision check as a backstop
  (`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:143-155`).
- `names` in any variant is reserved, as it folds to `PARAM_NAMES` (`ParameterUtil.java:175-179`).
- Empty values reach the container as empty `PARAM_*` values; nothing fails at save for want of a value.

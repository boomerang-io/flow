# 0068 — An empty workflow task parameter value is valid; required-ness is the task's run-time concern

**Status:** accepted · **Date:** 2026-09-02

## Context

Decision 0066 rejected a node parameter saved without a value, reasoning that it could only be a defect. The
maintainer overruled it: emptiness can be meaningful to a workflow, a substitution can legitimately resolve to
empty, and empty values were supported before. The defect 0066 reacted to (a run-workflow node whose target was
never set failing with an unreadable message) is a run-time reporting problem, not a save-time validity problem.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep rejecting a value-less parameter at save | every parameter must always carry a value | breaks substitutions that resolve to empty and any workflow where empty is meaningful |
| B. Accept empty/absent values; the task that requires a value fails its run with a message naming the parameter | emptiness is data; requirements are per task | a definition defect surfaces at run time rather than at save |

## Decision

Option B. The save-time rejection, its error code and its message were removed
(`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java`, `validateDeclaredParams`);
`runWorkflow` and `runScheduledWorkflow` fail their task with
"Parameter 'workflowRef' resolved to no value" instead of throwing on a null read
(`service-core/src/main/java/io/boomerang/engine/TaskExecutionService.java`). Name validation is unchanged:
invalid names, colliding names and undeclared names are still rejected at save.

## Consequences

- Empty and absent values persist as authored and reach the container as empty `PARAM_*` values.
- A task with an unmet value requirement fails its own run with the parameter named; nothing fails at save.
- Catalogue entries whose parameters the engine reads stay declared (the `run-workflow` and
  `run-scheduled-workflow` declarations from decision 0066's fix remain).

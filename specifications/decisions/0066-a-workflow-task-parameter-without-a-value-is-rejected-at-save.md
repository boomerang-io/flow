# 0066 — A workflow task parameter without a value is rejected at save

**Status:** accepted · **Date:** 2026-09-02

## Context

A `run-workflow` node whose `workflowRef` parameter arrived with no value was saved as a name-only
placeholder and failed only at run time ("Submitting RunWorkflow Request for ref: ."). The save path already
rejects invalid names, colliding names and undeclared names with typed errors; a value-less parameter was the one
defect it let through.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Drop the parameter and log a warning | callers are trusted to notice logs | the run still fails later with a less specific message; the only silent path in an otherwise strict validator |
| B. Reject the save with a typed error naming the task and parameter | the workflow author must fix the definition | a caller that relied on placeholders now gets a 400 |

## Decision

Option B. `WorkflowService.validateDeclaredParams` throws `WORKFLOW_TASK_PARAM_MISSING_VALUE` (code 1211) for any
node parameter whose value is null (`service-core/src/main/java/io/boomerang/workflow/WorkflowService.java`,
`validateDeclaredParams`), before the existing name checks, whatever the referenced catalogue entry declares.
The catalogue side was fixed with it: `run-workflow` and `run-scheduled-workflow` now declare the parameters the
engine reads, both in the seed and, for existing installs, by the loader change unit
`_0040__DeclareRunWorkflowParams`.

## Consequences

- A definition that would fail at run time for a missing value fails at save with the task and parameter named.
- Clients MUST send a value for every node parameter they include; omit the parameter instead of sending a
  placeholder.
- Trigger to revisit: a client needs to persist an intentionally empty parameter — then an explicit empty
  string, not null, is the contract.

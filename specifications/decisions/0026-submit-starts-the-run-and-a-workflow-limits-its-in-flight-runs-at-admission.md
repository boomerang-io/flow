# 0026 — Submit starts the run by default, and a workflow limits its in-flight runs at admission

**Status:** accepted · **Date:** 2026-10-29

## Context

Nearly every caller of submit means "run now": the web app, a run-workflow task's child, a fired schedule, a webhook.
The only concurrency limit was the workspace quota over every workflow, so an embedder's index rebuilds overlapped when
two runs of one workflow were admitted together. The limit has to hold under simultaneous submits without changing the
closed `RunStatus` enum.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Start: `start=false` by default | A submit is a staging step | A caller that forgets the flag leaves a parked run nothing starts |
| Start: `start=true` by default; `?start=false` parks | Submit means run, as callers and peers expect | An API caller that stages runs must ask for it |
| Limit: a per-(workflow, key) slot document taken by `$inc` | An exact mutex is required | A new collection; a counter that drifts |
| Limit: a count before admission and a recount after, the younger run stepping back, as the quota does | Exceeding the limit for the instant between two admissions is acceptable | Two counts per admission; a park compare-and-set |
| Limit: a new status for a parked run; a keyed limit from a param or label | A distinct filter; one run per tenant | Breaks the closed enum; a typed key and an index |

## Decision

`start` defaults to true (`workflow/WorkspaceWorkflowControllerV2.java:306-312`); a child run, a fired schedule and
webhook and event triggers start too (`engine/TaskExecutionService.java:1173`, `schedule/ScheduleJob.java:76`,
`flow.workflowrun.auto-start-on-submit`). A run without a workspace starts at once; one with a workspace stays pending
until the dispatcher provisions and starts it (`workflow/WorkflowRunService.java:792-808`, decision 0025). A workflow
carries `concurrency: {limit, overflow}` (`common/model/WorkflowConcurrency.java:18-19`): at admission the engine counts
its runs in `ready` or `running` on `workflow_ref_status`, at the limit parks the run as `waiting/pending` with a message
or, with `overflow: skip`, completes it `cancelled` with `ConcurrencyLimit`, and after admitting recounts and steps back a
run not among the oldest `limit` (`engine/WorkflowExecutionService.java:91-116`, `:135-165`,
`engine/WorkflowRunStateHelper.java:181-217`). A run's completion re-admits the oldest parked run
(`engine/ConcurrencyAdmissionListener.java:33-55`); the `readmitParkedRuns` sweep is the fallback.

## Consequences

- Submitting from any client runs the workflow; staging a run is a deliberate `?start=false`.
- A parked run counts against the workspace quota, which keeps precedence. The limit holds between two simultaneous
  admissions only after the recount; an exact mutex reopens the slot document with a reproduced incident.
- A per-value limit is the `acquirelock` task until a case shows its accounting is wrong for it.

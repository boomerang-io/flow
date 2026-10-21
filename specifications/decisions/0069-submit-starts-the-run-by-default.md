# 0069 — Submit starts the run by default; parking is the explicit opt-out

**Status:** accepted · **Date:** 2026-09-02

## Context

A submit either starts a run or parks it for a later `PUT .../workflowrun/{id}/start`. Nearly every caller means "run
now": the web app's Run, a run-workflow task's child, a fired schedule, a webhook. Comparable systems run by default
and make deferral the explicit act (Tekton's `PipelineRunPending`, Temporal's `startDelay`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. `start=false` by default | A submit is a staging step | Every "run now" caller must remember the flag, and one that forgets leaves a parked run nothing starts |
| B. `start=true` by default; `?start=false` parks | Submit means run, as callers and peers expect | An API caller that stages runs must ask for it |

## Decision

B. `POST .../workflow/{name}/submit` defaults `start` to true (`workflow/WorkspaceWorkflowControllerV2.java:306-312`).
Internal callers start too: a run-workflow task's child (`engine/TaskExecutionService.java:1173`), a fired schedule
(`schedule/ScheduleJob.java:76`), and webhook and event triggers through `flow.workflowrun.auto-start-on-submit`, true
by default (`service-core/src/main/resources/application.properties:38`, `event/WebhookEventService.java:56-57`).
Starting means one thing everywhere: a run without a workspace starts at once, and a run with one stays pending until
the dispatcher provisions and starts it (`workflow/WorkflowRunService.java:792-808`, decision 0067).

## Consequences

- Submitting from any client runs the workflow without a flag; staging a run is a deliberate `?start=false`.
- An integration that stages runs MUST pass `?start=false`; trigger-created runs park only when the property is false.

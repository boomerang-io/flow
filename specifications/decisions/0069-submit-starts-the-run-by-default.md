# 0069 — Submit starts the run by default; parking is the explicit opt-out

**Status:** accepted · **Date:** 2026-09-02

## Context

`POST .../workflow/{name}/submit` defaulted to `start=false`, so a submit only created a parked run. Every
caller that meant "run now" had to remember `start=true`, and three forgot: the web app's Run it, the
run-workflow task's child submission, and fired schedules — all silently masked while dispatchers started
parked runs by accident, and all broken the moment that accident was removed. Comparable systems run by
default and make deferral the explicit act (Tekton starts a PipelineRun unless `spec.status:
PipelineRunPending` is set; Cloud Run's `jobs execute` runs; Temporal's start starts, with `startDelay` as
the opt-out).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep `start=false` as the default | a submit is a staging step | every "run now" caller must remember the flag; three already forgot |
| B. Default `start=true`; `?start=false` parks | submit means run, as callers and peers expect | an API caller that relied on the old default to stage runs now starts them |

## Decision

Option B. The submit route defaults `start` to true
(`service-core/src/main/java/io/boomerang/workflow/WorkspaceWorkflowControllerV2.java`, the `start`
request parameter); fired schedules submit with start=true (`schedule/ScheduleJob.java`). The start=true
semantics are unchanged: a run without workspaces starts at once, a run with workspaces waits for the
dispatcher to provision and start it (decision 0067), and `?start=false` still parks a run for a later
`PUT /workflowrun/{id}/start`.

## Consequences

- Submitting from any client runs the workflow without a flag; staging a run is a deliberate
  `?start=false`.
- An integration that depended on the old default for staging MUST add `?start=false`.
- The webhook and event triggers follow the same default: `flow.workflowrun.auto-start-on-submit` defaults
  to true, with false as the explicit parking opt-out.

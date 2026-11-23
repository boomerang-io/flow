# 0026 — Submit starts the run by default, and a run over a concurrency limit waits in its workspace's queue

**Status:** accepted · **Date:** 2026-11-23

## Context

Nearly every caller of submit means "run now": the web app, a run-workflow task's child, a fired schedule, a webhook. Two limits bound what runs at
once: a workflow's own `concurrency`, so an embedder's index rebuilds do not overlap, and the workspace's concurrent-run quota, which refused a submit
over it with `429`, so a schedule's fire or a webhook delivery was lost. Both must hold under simultaneous submits with the `RunStatus` enum closed.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Start: `start=false` by default | A submit is a staging step | A caller that forgets the flag leaves a staged run nothing starts |
| Start: `start=true` by default; `?start=false` stages | Submit means run, as callers and peers expect | An API caller that stages runs must ask for it |
| Over the workspace limit: refuse with `429` | Callers retry and nothing must be kept | Fires and deliveries are dropped; the limit reads as an outage |
| Over either limit: park the run `waiting/pending` in one queue per workspace, admitted oldest first, capped and aged out | A burst should be absorbed and shown | A count and a recount per limit per admission; a queue quota and an age setting to run |
| A per-workflow slot document taken by `$inc`; a new status for a parked run | An exact mutex; a distinct filter | A new collection and a counter that drifts; breaks the closed enum |

## Decision

`start` defaults to true (`workflow/WorkspaceWorkflowControllerV2.java:363-369`); a child run, a fired schedule and webhook and event triggers start
too (`engine/TaskExecutionService.java:1403`, `schedule/ScheduleJob.java:76`, `flow.workflowrun.auto-start-on-submit`). Admission counts the
workflow's runs in flight against `concurrency.limit` and the workspace's against `max.workflowrun.concurrent`, before `tryAdmit` and again after it,
the younger run stepping back (`engine/WorkflowExecutionService.java:106-150`, `engine/WorkflowRunStateHelper.java:220-258`,
`workflow/QuotaService.java:493-516`). A run over either parks as `waiting/pending` with a message, holding no slot and no deadline, or with
`overflow: skip` ends `cancelled` with `ConcurrencyLimit` (`engine/WorkflowExecutionService.java:173-221`,
`engine/WorkflowRunStateHelper.java:199-216`). A completion drains the workspace's queue oldest first, passing over a workflow still at its own limit,
and a sweep is the fallback (`engine/ConcurrencyAdmissionListener.java:34-57`, `engine/WorkflowExecutionService.java:254-281`). The queue is capped by
the `max.workflowrun.queued` quota, a full one refusing the submit with `QUOTA_EXCEEDED`, and a run parked longer than `workflowrun`/`queue.max.age`
ends `cancelled` with `QueueExpired` (`workflow/QuotaService.java:377-427`, `engine/WorkflowExecutionService.java:287-312`). A child run is never held
by the workspace limit: its parent holds a slot. What decided it: a parked run is a record the caller and the operator can see, and a refusal is not.

## Consequences

- Submitting from any client runs the workflow or queues it; `429` means the month is spent, the queue is full or the requested workspace is too
  large. A schedule's fire dropped after its attempts leaves a `SubmitRefused` run on the workflow's Activity.
- A newer run that recounts before an older one is admitted keeps its slot, so a burst can overshoot either limit by its racers; an exact mutex, a
  slot document, reopens with an incident. A per-value limit is the `acquirelock` task until a case shows its accounting is wrong for it.

# 0026 — A submit always starts its run, and a run over a concurrency limit waits in its workspace's queue

**Status:** accepted · **Date:** 2026-11-23

## Context

Nearly every caller of submit means "run now": the web app, a run-workflow task's child, a fired schedule, a webhook. Two limits bound what runs at
once: a workflow's own `concurrency`, so an embedder's index rebuilds do not overlap, and the workspace's concurrent-run quota, which refused a submit
over it with `429`, so a schedule's fire or a webhook delivery was lost. Both must hold under simultaneous submits with the `RunStatus` enum closed.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Start: a `start` flag, `false` staging the run for a later start route | A submit is a staging step | A staged run nothing starts; a second route and a state to recover |
| Start: a submit always starts its run, or queues it | Submit means run, as callers and peers expect | A caller that wanted to stage a run cannot |
| Over the workspace limit: refuse with `429` | Callers retry and nothing must be kept | Fires and deliveries are dropped; the limit reads as an outage |
| Over either limit: park the run `waiting/pending` in one queue per workspace, admitted oldest first | A burst should be absorbed and shown | A count and a recount per limit per admission |
| A per-workflow slot document taken by `$inc`; a new status for a parked run | An exact mutex; a distinct filter | A new collection and a counter that drifts; breaks the closed enum |

## Decision

A submit always starts its run, and so do a retry, a child run, a fired schedule and webhook, event and GitHub triggers; there is no `start` flag and
no route that starts a run (`workflow/WorkflowService.java:708-729`, `workflow/WorkflowRunService.java:1143-1154`). Admission counts the workflow's runs in
flight against `concurrency.limit` and the workspace's against `max.workflowrun.concurrent`, before `tryAdmit` and again after it, the younger run
stepping back (`engine/WorkflowExecutionService.java:107-151`, `workflow/QuotaService.java:623-646`). A run over either parks as `waiting/pending`
with the holding limit as its reason, `ConcurrencyLimit` or `WorkspaceConcurrencyLimit`, holding no slot and no deadline, or with `overflow: skip`
ends `cancelled` with `ConcurrencyLimit` (`engine/WorkflowExecutionService.java:174-226`, `engine/WorkflowRunStateHelper.java:202-230`). A completion
drains the workspace's queue oldest first, passing over a workflow still at its own limit and, with every slot taken, still admitting child runs; a
sweep is the fallback (`engine/WorkflowExecutionService.java:294-322`). How many may wait and for how long are quotas, recorded with the other
workspace quotas. What decided it: a parked run is a record the caller and the operator can see, and a refusal is not.

## Consequences

- Submitting from any client runs the workflow or queues it; a `429` is a quota, never a concurrency limit. A schedule's fire dropped after
  its attempts leaves a `SubmitRefused` run on the workflow's Activity.
- A newer run that recounts before an older one is admitted keeps its slot, so a burst can overshoot either limit by its racers; an exact mutex, a
  slot document, reopens with an incident. A per-value limit is the `acquirelock` task until a case shows its accounting is wrong for it.

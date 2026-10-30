# 0048 — A finally task is a task node linked from End with the `finally` condition

**Status:** accepted · **Date:** 2026-10-30

## Context

Clean-up, notification and release steps must run after a workflow ends however it ends: succeeded, failed,
cancelled or timed out. A success or failure edge from the last task covers one outcome at a time and never a
cancel, and a task placed after End had no meaning to the engine. Tekton's `finally` and Kestra's `afterExecution`
are the models: steps outside the graph that run once its outcome is known.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A `finally` list on the workflow, drawn as a zone beside End | The steps must be visible without opening anything | A new canvas element and a second representation of tasks the engine, export and templates must all read |
| B. End owns the list behind its configure modal | No canvas change at all | Hidden; nowhere to show the steps' statuses on the run graph |
| C. An ordinary task node whose single dependency is `end` with a new execution condition `finally` | The graph stays one connected picture and nothing new is stored | End stops being terminal for the engine; the condition must be held to that one use |

## Decision

C. `ExecutionCondition.Finally` serialises as `finally` (`common/enums/ExecutionCondition.java:9-15`). Saving and
submitting hold a finally task to one dependency, on `end`, and let nothing depend on it, so finally tasks never
chain (`workflow/WorkflowService.java:1248-1283`). When the graph ends, by its own completion, a cancel or a
timeout, the run takes its final status, reason and message, stamps `graphEndedAt` and stays in the `running` phase, the stage's
deadline becomes the `workflowrun` setting `finally.timeout`, and the finally tasks are queued in parallel
(`engine/TaskExecutionService.java:685-729`, `engine/WorkflowRunStateHelper.java:322-353`); the run completes when
the last of them ends, a finally task that failed, timed out or was invalid turning a succeeded run into `failed`
with the reason `FinallyFailed` (`:737-766`). In the stage a cancel does nothing more, while a purge and the
stage's deadline both complete the run at once and end the finally tasks (`engine/WorkflowExecutionService.java:238-241,355-372`).
A finally task reads `$(context.params.workflowrun-status)` and `workflowrun-status-reason`. The editor draws it
with a purple border, a `Finally` badge and no output port, on a purple link from End's new output port, so the
same pattern as the switch and for-each nodes says what it is.

## Consequences

- Export, templates, the run view and the API carry finally tasks as tasks; nothing new is stored.
- A consumer of run events sees the terminal status with phase `running` first and `completed` later; one that
  acts on the end of a run filters on the phase.
- Not built: a dotted placeholder on the canvas for an empty finally, and a placeholder's removal on first use.

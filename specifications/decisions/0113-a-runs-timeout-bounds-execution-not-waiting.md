# 0113 — A run's timeout bounds execution, not waiting; a wait is indefinite unless the task says otherwise

**Status:** accepted · **Date:** 2026-10-16

## Context

A run's deadline is `timeoutAt` = start + budget, reaped by the watcher for every running, unpaused run
(`engine/WorkflowRunStateHelper.java:268-281`). A run whose tasks are all waiting on an approval, an event or a
child run is still running, so the deadline counts through the wait; with the 30-minute platform default, every
workflow with a human gate is timed out unless its author sizes the budget for the longest wait, which makes the
guard meaningless. Resume after a pause has the same flaw: nothing moves the deadline (`:258-266`). The products
embedding Flow need an analysis that executes for about an hour but whose gates can sit for days.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep one wall-clock deadline over the whole run (Tekton) | No human gates | Gated workflows need day-long budgets; the guard no longer catches runaway work |
| B. Keep the deadline but give every wait its own deadline too, and add a run-age ceiling | Installs that must bound every run absolutely | Three clocks and two new settings for a problem two engines solve with one |
| C. Stop the run clock while the run is properly waiting; a wait is indefinite unless its task declares a timeout (Temporal, Kestra, Windmill) | Any workflow with a gate; the embedding products | A gate nobody answers leaves a run active until cancelled |

## Decision

C. A run is waiting when every in-flight task is `waiting`; a branch still executing keeps the clock running.
The run carries one internal field, `waitingSince`, the same shape as `pauseRequestedAt`: the watcher skips a
run that has it, and when work resumes the deadline moves forward by the time spent waiting. Resume after a
pause gets the same treatment. A task that enters `waiting` with a declared `timeout` is given a deadline of its
own and reaped by the task sweep; one without is indefinite, which is already every task's default
(`seed/settings.json` `task`/`default.timeout` = 0). The run's budget keeps its meaning at submit and under the
workspace ceiling (`workflow/RunTimeoutPolicy.java`); it now bounds execution only, and the reported duration
excludes waits. One field and no new settings decided it.

## Consequences

- A gated workflow needs a budget for its work, not for its approvers; the embedding products submit an hour.
- A run with an unanswered gate stays active until cancelled; Activity shows it running.
- `waitingSince` is execution state and never serialises into a public run model.
- Revisit, with a reproduced case: an absolute run-age ceiling, if an install needs every run bounded regardless
  of state.

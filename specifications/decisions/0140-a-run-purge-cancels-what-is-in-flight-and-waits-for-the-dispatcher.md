# 0140 — A run purge takes the lineage, cancels what is in flight, and waits for the dispatcher to let go

**Status:** accepted · **Date:** 2026-10-29

## Context

An embedding product must erase a finished run on demand, prompts, outputs, logs and artifacts included, and
retention (decision 0019) is not erasure. The run's retries and child runs hold the same material, a run may still be
in flight when its owner is deleted, and a cancelled task's document is the terminate order its dispatcher polls for.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Refuse a run in flight; the caller cancels, waits and calls again | The caller already tracks its runs | Every embedding product keeps a restart-safe "purge later" ledger, which is what it is trying to stop owning |
| B. Cancel and delete at once | No dispatcher holds a pod | The task document goes before the dispatcher's terminate poll reads it, so the pod runs on until it ends on its own |
| C. Cancel, stamp the run, and let a sweep delete it once the dispatcher has released its terminated tasks | A pod is to be stopped before its order disappears | One typed field and one sparse index; the caller sees `scheduled` rather than `purged` for a few seconds |
| D. One call per run, the caller walks lineage | Lineage is the caller's to know | A plain delete of a run a retry still reuses would leave a carried-over task run pointing at nothing |

## Decision

C with the lineage walked server-side. `WorkflowRetentionService.purge` collects the run and every run whose
`initiatedByRef` names it or one of its task runs, transitively, and removes them deepest first through the retention
sweep's own per-run purge (`workflow/WorkflowRetentionService.java:120-146,175-198,311`). A member in flight is
cancelled through the ordinary cancel, which ends its tasks off the caller's thread; a member whose tasks have not
settled (one still ending, or a cancelled or timed-out one a dispatcher still holds, `claim.by` set) is stamped
`purgeRequestedAt` (`engine/WorkflowRunStateHelper.java:336`) and the `purgeRequestedRuns` sweep removes it once they
have, or ten minutes after the stamp (`:149-172`). A label purge takes one
sweep page of the workspace's matching runs and reports `remaining` (`workflow/WorkflowRunService.java:492-518`). What
decided it: a dispatcher learns of a termination only from the task document, so the document outlives the order.

## Consequences

- One call erases an analysis, a collection or a workspace's runs by label; a caller repeats a label purge until
  `remaining` is 0 and needs no ledger of its own.
- A purge on a run in flight answers `scheduled`, and the documents leave within a sweep of the dispatcher's
  terminate poll; a dispatcher that never polls again holds them for the grace, no longer.
- The purge crosses no workspace: a child run in another workspace is reached through lineage, as retention is.
  Revisit if a cross-workspace child must stay.

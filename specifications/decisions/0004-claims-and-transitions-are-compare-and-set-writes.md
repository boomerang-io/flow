# 0004 — Claims and contended transitions are compare-and-set writes, with no locks or version retries

**Status:** accepted · **Date:** 2026-07-23

## Context

Several `service-core` instances, and the recovery sweeps on each, act on the same runs at once. Two claimants must
not both win a task, and a winner-only side effect (queue the dependants, finish the run, requeue a task) must run
once. A whole-document `save()` is last-writer-wins and silently rolls back a concurrent claim, deadline or result.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A distributed lock around find-then-update, or around each handler | One writer at a time is acceptable | A lock collection and lease to keep correct; a crashed holder blocks the run until the lock expires |
| B. `@Version` on the entity, retrying the save on conflict | Code that must keep whole-document saves | Guards only `save()`, so it invites `save()` back; "unchanged since I read it" is weaker than "currently in state X" |
| C. Compare-and-set (CAS): one `findAndModify` whose query names the expected prior state and returns the pre-image | Any number of instances and sweeps | Every write is a guarded field update, and a loser must be a no-op |

## Decision

C, with no lock library and no `@Version`. Execution-state writes are guarded field updates, and every run transition
goes through one CAS primitive that publishes only when it wins (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:972-1002`,
`engine/WorkflowRunStateHelper.java:594-605`); a null pre-image means another caller won, and the handler returns
without side effects (`engine/TaskExecutionService.java:270-275`, `:622-627`, `engine/WorkflowExecutionService.java:109-112`).
A claim is the same write: `findClaimable` pages eligible ids oldest first (`TaskRunService.java:121-154`), and
`tryClaim` takes each by id, re-checking status, phase, the absence of `claim.by` and the retry backoff, and setting
`claim.by`, `claim.at`, the first lease, `$inc claim.seq` and `timeoutAt` together (`:327-365`); only what the poll won
is handed out (`dispatcher/DispatcherService.java:428-440`). A workflow run's provisioning claim has the same shape
(`WorkflowRunStateHelper.java:57-103`). A CAS names the state a side effect depends on, which is exactly the property a
winner-only action needs; paging first lets a claimant skip what it cannot take.

## Consequences

- The claim block is the only ownership record: a dispatcher's start and end are fenced on `claim.by` (`claim.seq` is
  not on the wire, `TaskRunService.java:1295-1316`), every requeue and abandon on `claim.seq` (`:963-967`).
- Whole-document `save()` remains for creating a document and for merging a caller's request into a workflow run before
  its handler runs (`workflow/WorkflowRunService.java:874`, `:966`, `:1142`); a dispatcher's start and end merges are
  field-scoped updates (`TaskRunService.java:1064-1068,1152-1157`).
- Editing definitions (workflows, tasks) is outside this decision and MAY adopt `@Version` for edit conflicts.
- A dead claimant's work is recovered by the deadline and lease sweeps, not by a lock expiry (decisions 0007, 0023).

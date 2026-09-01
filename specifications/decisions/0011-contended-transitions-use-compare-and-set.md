# 0011 — Contended transitions use compare-and-set, not @Version retries

**Status:** accepted · **Date:** 2026-07-22

## Context

Every transition handler used to load the run, mutate it and call `repository.save()`, a whole-document
last-writer-wins replace. Under two or more instances (or a sweep overlapping the live path), concurrent handlers
silently rolled back each other's claims, deadlines and results.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Add `@Version` and retry the save on conflict | code that must keep whole-document saves | protects only `save()`, so it invites `save()` back; "unchanged since I read it" is weaker than "currently in state X"; retry loops re-implement the guard imperatively |
| B. Compare-and-set (CAS): a `findAndModify` whose query states the expected prior state and returns the pre-image | every winner-only side effect (queue dependants, finish the run, spawn a retry) | every write must be expressed as a guarded field update; a loser must be handled as a no-op |
| C. A per-run distributed lock around each handler | a single-instance deployment | the lock library was not exclusive, and a crashed holder blocks the run |

## Decision

Option B, with no `@Version` anywhere. Every execution-state write goes through `findAndModifyPreImage`
(`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:669`, `WorkflowRunStateHelper.java:331-334`);
a null pre-image means another caller won and the handler returns without side effects
(for example `TaskExecutionService.java:181-187`, `:463-473`, `WorkflowExecutionService.java:193-197`). The reason
that decided it: a CAS query names the state the side effect depends on, which is exactly the property a
winner-only action needs.

## Consequences

- The engine runs with no distributed lock library and no leader; overlapping instances and sweeps are safe by
  construction, and a duplicate hint costs one primary-key no-op.
- Whole-document `save()` survives only for caller-side request merges before a handler runs
  (`TaskRunService.java:718`, `:779`) and for the fixed-field `updateStatusAndSaveTask` fallback paths.
- Definition CRUD (workflows, tasks) is outside this decision; it MAY adopt `@Version` for edit conflicts.

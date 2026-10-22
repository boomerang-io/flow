# 0082 — A task runs once per item through a `foreach` setting on the task

**Status:** accepted · **Date:** 2026-09-26

## Context

A workflow needs to run one task once per item of a list known only at run time, such as the files an earlier task
produced. Each item needs its own status, the run's workspace, and a result the next task can read.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A child workflow per item | The body is several tasks and each item may run in isolation | The body becomes a separate workflow to author; a child does not share the parent run's workspace |
| B. A container node holding several tasks, repeated per item | Per-item bodies of several tasks are common | The engine repeats a group of graph nodes per item: the largest change to the graph advance and the canvas |
| C. A setting on one task, repeated per item in the same run | One task repeats and the rest of the graph stays as drawn | One task per loop; a multi-task body is a child workflow |

## Decision

C, the shape of Tekton's matrix. `WorkflowTask.foreach.items` holds a JSON array literal or one reference
(`common/model/WorkflowTask.java:50`). At admission the engine resolves it, moves the parent to running through its own
compare-and-set rather than a claim, and creates one ordinary claimable task run per element named `<name>[<index>]`,
with `parentRef`, `index`, the parent's params plus `item` and `index`, and the parent's workspace
(`engine/TaskExecutionService.java:189-195,663-783`). When every item is terminal the parent completes through the
ordinary completion compare-and-set, collecting each declared result, or each produced one when it declares none, into
an array in item order (`:785-837`). It won because it reuses every existing mechanism: claiming, leases, timeouts,
retry, pause and the graph advance see nothing new, and the unique `{workflowRunRef, name}` index needs no change.

## Consequences

- Dispatcher-run task types and `runworkflow` (one child per item, decision 0075) may run for each item. Saving refuses
  other types, a literal array over the `workflowrun` setting `max.foreach.items` (256), and a task that declares `item`
  or `index` (`workflow/WorkflowService.java:126-137,1604-1648`); a resolved array over the cap fails the task.
- Any failed item fails the task, as in Tekton; an item that times out times out the run
  (`engine/TaskExecutionService.java:602-613`); combined results over 16 MB fail the task with `RESULTS_TOO_LARGE`.
- A task name ending in `[<digits>]` is refused, so an item never collides with another task
  (`workflow/WorkflowService.java:1401-1410`).
- Not built: batching, a failure-tolerance rule, a per-task parallel limit, rerunning only failed items, and a
  multi-task body (option B). Reopen with real workflows that need them.
- The webapp's "For each" palette item just creates an ordinary task with the setting on (`design-system.md`).

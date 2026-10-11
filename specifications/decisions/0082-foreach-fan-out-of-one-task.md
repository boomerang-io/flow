# 0082 — A task runs once per item through a `foreach` setting on the task

**Status:** accepted · **Date:** 2026-09-26

## Context

A workflow could not run one task once per item of a list an earlier task produced: fan-out was fixed in the graph,
so a variable number of batches, files or repositories needed a hand-drawn branch per item. Each item needs its own
status, the run's workspace, and a result the next task can read.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A child workflow per item | The body is several tasks and each item may run in isolation | The body becomes a separate workflow to author; each child needs the parent run's workspace, which runs do not share |
| B. A container node holding several tasks, repeated per item | Per-item bodies of several tasks are common | The engine repeats a group of graph nodes per item; the largest change to the graph advance and the canvas |
| C. A setting on one task, repeated per item in the same run | One task is repeated and the rest of the graph stays as drawn | One task per loop; a multi-task body still needs a child workflow |

## Decision

C, the shape of Tekton's matrix. `WorkflowTask.foreach` (`lib-common/.../model/WorkflowTask.java:50`) holds an array
literal or one reference. At admission the engine resolves it, moves the one graph vertex, the parent, to running
through its own compare-and-set, and creates one ordinary claimable task run per element named `<name>[<index>]` with
`parentRef` and `index` (`service-core/.../engine/TaskExecutionService.java:593-701`). An item's end completes the
parent through the ordinary completion compare-and-set once every item is terminal, collecting each declared result
into an array in item order (`:709-750`). It won because it reuses every existing mechanism: claiming, leases,
timeouts, retry, pause and the graph advance see nothing new, and the unique `{workflowRunRef, name}` index needs no
change.

## Consequences

- Any dispatcher-run task type gains fan-out; items share the run's workspace; downstream reads one array.
- Any failed item fails the task, as in Tekton; an `always` connection carries on. An item timeout times out the run.
- `[` and `]` are reserved in task names so an item can never collide with another task.
- Not built: batching, a failure-tolerance rule, a per-task parallel limit, rerunning only failed items, and a
  multi-task body (option B). Reopen with real workflows that need them.

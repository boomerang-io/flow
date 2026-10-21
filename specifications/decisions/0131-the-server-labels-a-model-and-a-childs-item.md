# 0131 — The server labels an AI task's model and a for-each child's item

**Status:** accepted · **Date:** 2026-10-21

## Context

Insights groups task runs by fields Flow owns and by labels (decision 0129). Two groupings an embedder needed had no
label: the model an AI task used, and which item a for-each run-workflow child ran for (an agent, a file). A child
copies its parent run's labels, so every child of one for-each carried the same ones, and a node cannot declare a
label per item.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Server labels under a reserved `boomerang.io/` prefix: `model` on an AI task run, `item` on a for-each child | Grouping and filtering, which labels already serve and index | Labels stop being only the client's; a value must never carry a secret |
| B. The same as annotations, made filterable | Keeping labels the client's alone | Two new indexes, filters on values that may be objects, and annotations stop being purely descriptive |
| C. A `labels` param on the run-workflow node, resolved per item | A node author choosing the label | Labels are not inputs; it mixes metadata into the task's params |
| D. One child workflow per agent, grouped by workflow | No Flow change | The embedder generates a copy per agent and edits all of them |

## Decision

A (`service-core/src/main/java/io/boomerang/common/model/ServerLabel.java`). Admission sets `boomerang.io/model` from
the resolved `model` param unless it is secret-typed (`engine/TaskExecutionService.java:230-238`); the run-workflow
task sets `boomerang.io/item` on its child when the item is text that contains no secret value of the run's parameter
layers (`:1153-1166`, `engine/ParameterManager.java:354`). Every place a client sets labels drops keys under the
prefix. The engine reads neither label to decide anything.

## Consequences

- `groupBy=label:boomerang.io/model` and `label:boomerang.io/item` serve per model and per agent; the run and
  task-run queries filter on them.
- The model is the one asked for at admission, so a failed or timed-out call has it; the model a gateway actually
  served stays the `model` result the usage read groups by.
- A nested child takes its own item, not its parent's.

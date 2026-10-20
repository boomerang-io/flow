# 0125 — A child run receives only the params its node passes

**Status:** accepted · **Date:** 2026-10-20

## Context

A `runworkflow` task submits its child with a set of params. The designer's Run Workflow modal lists the child's
declared params so the author links each one to a value or a reference, and saving now accepts them. The engine
also copied every parent run param into the child, then wrote the node's params over them, so a child received
values its author never linked.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Only the node's params, minus `workflowRef` and `wait` | Inputs are linked in the modal or the definition | A child relying on a same-named parent param gets its own default |
| B. Every parent run param, the node's written over them | A child is meant to see all of its parent's context | Every parent value reaches the child unasked, secrets included, coupled by name alone |

## Decision

A. `TaskExecutionService.childParams` submits the child with copies of the task's own params minus `workflowRef`
and `wait` (`engine/TaskExecutionService.java:1228-1234`); under for-each the item's `item` and `index` are among
them. One reason decided it: what a child receives is what its author linked, as in v3, where the child got the
node's inputs minus `workflowId`, and in the v4 engine. The copy of the parent run's params was added in 2025 and
never shipped in a release.

## Consequences

- A child's inputs are visible on the node, and a parent's secrets reach a child only when a node passes them.
- A child that needs a parent value takes it as a declared param, linked as `$(params.x)` on the node.
- Revisit if an author needs a child to inherit a whole context, such as every label-like param, without listing
  each one.

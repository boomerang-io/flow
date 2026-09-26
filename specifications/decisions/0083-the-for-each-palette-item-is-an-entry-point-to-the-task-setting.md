# 0083 — The "For each" palette item is an entry point to the task setting, not a node of its own

**Status:** accepted · **Date:** 2026-09-26

## Context

Running one task once per item is a `foreach` setting on any workflow task (decision 0082). Most
drag-and-drop editors (Power Automate, Dify, Langflow, Make) give a loop its own shape on the
canvas, so users look for one in the palette. The task modal today is a flat list of parameters
with no settings section (`client-web/src/Features/Reactflow/components/shared/inputs.tsx:39-75`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Setting on the task only, in a new Settings tab | The stored model is all that matters | Hard to find: nothing in the palette says a loop exists |
| B. A "For each" node wired by an edge to the task it repeats | Users expect a loop node | An edge would mean either "runs after" or "is the body of"; the canvas and the stored workflow disagree |
| C. A "For each" container that tasks are dropped into | Bodies of several tasks are common | The engine repeats a group of tasks per item; the largest engine and canvas change |
| D. Setting on the task (A), plus a "For each" palette item that creates a task with the setting on | One task is repeated per item | Two ways to reach the same setting |

## Decision

D. Dropping "For each" opens a dialog that picks the task and its items, then creates an ordinary
task with `foreach` set. Switching on "Run for each item" in any task's Settings tab stores
exactly the same thing. The palette item is only a way in: the canvas, the stored workflow and
anything authored by hand all have one `foreach` key on the task, and nothing records which path
made it.

## Consequences

- The canvas matches the data model: one node per task, a badge where `foreach` is set. A workflow
  written as JSON (or YAML, once workflows accept it; today only task definitions do,
  `specifications/api-contract.md` "YAML content negotiation") needs no editor-only structure.
- The task modal gains Parameters | Settings tabs, which also gives per-task timeout and retry a home.
- A body of several tasks per item is two fan-out tasks over the same items, with a wait between them.
  Revisit the container (C) when more than one real workflow needs each item's steps to run independently.

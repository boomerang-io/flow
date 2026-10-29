# 0044 — A run records the person or token that started it

**Status:** accepted · **Date:** 2026-10-29

## Context

`initiatedByRef` carried lineage only: the schedule that fired a run, the run it retried, or the task run that started
it as a child. A run a person started through the webapp or the API recorded nobody, so the run page showed a robot
and Activity's "Initiated By" column read a field the API never sent.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Stamp the caller on `initiatedByRef` for runs a person or a token starts; derive the name on read | One field can say what started a run | `initiatedByRef` widens from lineage to "what started this run" |
| B. A new stored `initiatedBy {kind, ref}` beside the lineage field | Lineage and initiator must stay apart | A new stored field on every run, the public API and the SDK |
| C. Show "Manual" in the webapp and leave the data alone | Who started a run does not matter | The question stays unanswerable |

## Decision

A. The public submit stamps the caller, the same actor a changelog records (`workflow/WorkflowService.java`,
`workflow/TaskService.java` `currentActorRef`): a user's id, or a key or global token's name; nothing under security
off. Schedules, child runs and retries keep their lineage. Reads add the derived `initiatedByName`, a user's display
name or the token name, resolved in one lookup per page (`workflow/WorkflowRunService.java`).

## Consequences

- The run page and Activity name who started a run; runs started before this show nothing, as before.
- Retention's lineage checks look up run and task-run ids, so a user id in `initiatedByRef` never matches them.
- `initiatedByName` is derived like `initiatedByWorkflowRunRef` and is in `contracts/api-v2.yaml` and the SDK's model.

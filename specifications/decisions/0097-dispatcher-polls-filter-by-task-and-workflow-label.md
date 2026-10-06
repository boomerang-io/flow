# 0097 — A dispatcher's poll filters by task type, task and workflow label, resolved to typed refs

**Status:** accepted · **Date:** 2026-10-06

## Context

One engine can serve dispatchers that should each take only part of the work: ARCHIE sizes seven pools, each a
long poll sent with its free slots (0095). The poll needs to name its work. Decision 0020 says the engine
decides on typed fields only, and a task run's labels stack the task's, the node's and the run's, so a run label
could re-route every task in a run.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A typed `pool` field on the task definition, node and task run | Pools are a product concept | New fields, an index, a materialisation change; a pool concept Flow does not otherwise need |
| B. Select on task-run labels | Labels are already set | Crosses 0020; any run label overrides the task's |
| C. Filter on fields Flow has: type, task slug, and a label on the workflow definition resolved to workflow ids | Default | A filtered scan after the claim index; a label read once to resolve ids |
| D. As C, but workflow names or globs instead of a label | Names follow a convention | A rename silently re-routes work |

## Decision

C. `ClaimFilterService` resolves `task` slugs to task ids and `workflowLabel` to the ids of the workflows
carrying it, cached 30 s; `TaskRunService.findClaimable` and the termination pages add `taskRef in` and
`workflowRef in` after the claim index (`service-core/.../engine/TaskRunService.java:97-119,181-189`). Claims,
sweeps and fencing read typed fields only; a label on the workflow definition scopes which work a poll asks
for, and is never read once the claim query runs.

## Consequences

- No new fields, indexes or migrations; 0020 stands, with this one bounded use of a definition label.
- An unfiltered dispatcher still competes for filtered work; reserving it is deferred (flow#499).
- Large shared queues may need indexes led by `taskRef` and `workflowRef`; build them on load-test evidence.

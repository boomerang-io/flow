# 0094 — A dispatcher polls again at once, for its free slots only, narrowed by typed filters

**Status:** accepted · **Date:** 2026-10-06

## Context

The engine claims work when it answers a poll, and a claimed task is pinned to its dispatcher with its timeout running.
A wait between polls adds to every hop; a dispatcher that claims more than its cluster can start holds work another
could run; and one engine serves dispatchers that should each take only part of the work.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Pacing: a fixed 5 s wait after every poll | Pickup latency does not matter | Up to 5 s per hop, and a ceiling of about 4 claims a second |
| Pacing: wake the waiting polls when a task becomes ready | Hops well under 1 s are needed | Touches admission; wakes only the polls held by the same instance |
| Capacity: no cap, letting Kubernetes queue the pods; or capacity read from the cluster or admitted by Kueue | One dispatcher on a large cluster; a shared cluster already running Kueue | Pending pods burn their timeout on one dispatcher; Kubernetes reads on every poll, and Flow's timeout runs while a Job is suspended |
| Routing: a typed `pool` field on tasks and runs, or task-run labels | Pools are a product concept, or labels are already set | New fields and an index; labels cross decision 0020, and one run label re-routes every task in the run |

## Decision

A poll starts again at once after one that brought work or was held its 30 s, and waits until 5 s after it started only
when it failed or came back empty at once (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:104-123`);
the engine re-checks every 1 s, after an error too (`service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:365-418`).
A task pool counts a task from its claim until the engine takes its end and polls with `limit` = its free slots
(`InFlightTasks.java:8-33`); while full, a second poll with `limit=0` still brings termination orders (`Dispatcher.java:259-282`).
The engine claims at most `limit`, never above a page of 20 (`DispatcherService.java:356`, `:377-402`); Flow's Kubernetes
dispatcher holds 25 (`flow.dispatcher.task.max-in-flight`, 0 is no cap), as GitLab Runner, Buildkite, Airflow and
Temporal cap by slots. A poll may name task types, task slugs and one label on the workflow definition, resolved to task
and workflow ids and cached 30 s (`dispatcher/ClaimFilterService.java:40-69`); the claim and termination pages add
`taskRef in` and `workflowRef in` after the claim index (`engine/TaskRunService.java:120-131`, `:198-227`). A filter that matches
nothing answers at once with no content (`DispatcherService.java:359-362`).

## Consequences

- A ready task is claimed within about 1 s on every hop; work a full dispatcher cannot run stays claimable by others.
- Claims, sweeps and fencing read typed fields only; a definition label scopes a poll and is never read once the claim
  query runs. An unfiltered dispatcher still competes for filtered work.
- A wrongly sized slot count under-uses or overruns a cluster. Waking polls, capacity read from the cluster and indexes
  led by `taskRef` or `workflowRef` wait for load-test evidence.

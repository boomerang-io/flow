# 0095 — A dispatcher claims only what it has slots for

**Status:** accepted · **Date:** 2026-10-05

## Context

The engine claims work at the moment it answers a poll, up to a page of 20, and the dispatcher had no cap. A
claimed task is pinned to that dispatcher with its timeout running, so a dispatcher handed more than its
cluster can start holds work another dispatcher could run. Reconnecting at once (0094) removed the 5 s gap
that had been limiting it by accident.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. No cap; let Kubernetes queue the pods | The dispatcher is the only one and its cluster is large | Pending tasks burn their timeout and cannot move to another dispatcher |
| B. A fixed slot count per dispatcher; each poll asks for the free slots (`?limit=`) | Default | The operator sizes one number |
| C. Capacity read from the cluster (quota, nodes, pending time) | A shared cluster where a fixed count both under-uses and overruns | Kubernetes reads on every poll; quota is optional and often shared |
| D. Create Jobs suspended and let Kueue admit them | Clusters that already run Kueue | Flow's own timeout runs while suspended; an optional dependency |

## Decision

B, as GitLab Runner, Buildkite's Kubernetes controller, Airflow and Temporal do. `flow.dispatcher.task.max-in-flight`
(25; 0 is no cap) counts tasks from hand-off until the runtime object finishes, Pending included
(`service-dispatcher/.../dispatcher/TaskSlots.java`). The task poll sends `limit` = free slots, and the engine
claims no more than that, never above a page; `limit=0` still returns termination orders, which free slots
(`service-core/.../dispatcher/DispatcherService.java:247-320`).

## Consequences

- Work a full dispatcher cannot run stays claimable by others; ARCHIE's per-pool caps use the same `limit`.
- A wrongly sized count under-uses or overruns a cluster. Revisit C when a load test shows that.
- Selecting claims by pool, ARCHIE's other half of the same ask, is a separate data-model decision.

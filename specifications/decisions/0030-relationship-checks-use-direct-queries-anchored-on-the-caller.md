# 0030 — Relationship checks use direct queries anchored on the caller, not an in-memory graph

**Status:** accepted · **Date:** 2026-07-23

## Context

Access to workspaces, workflows and runs is decided by walking the `rel_nodes`/`rel_edges` collections from the
caller's node to the target. With several `service-core` replicas, a copy of that graph held per instance answers
from stale data until it is rebuilt, and a stale answer here is a wrong access decision, not a slow one.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-instance in-memory graph, rebuilt on mutation | One replica; deep, frequently walked graphs | Replicas disagree on access until each rebuilds |
| B. Direct MongoDB queries: a level-by-level walk anchored at the caller's node | The hierarchy is shallow (`root → workspace → object`, `user → workspace`) | A few indexed queries per decision |
| C. A shared cache (for example Redis) in front of the queries | Very high check volume | A new runtime dependency and an invalidation protocol |

## Decision

B. `check` and `filter` read live MongoDB, one edge query and one node batch-load per level from the caller's node
(`service-core/src/main/java/io/boomerang/core/RelationshipService.java:355-414,467-525,666-744`); only node
resolutions are memoised, for one HTTP request (`:93-133`). Any instance sees every committed write at once, which
`RelationshipServiceTest` pins with two service instances on one database
(`service-core/src/test/java/io/boomerang/core/RelationshipServiceTest.java:262-280`).

## Consequences

- Adding replicas needs no cache coordination; the indexes `rel_nodes{type,ref}`, `rel_nodes{type,slug}`,
  `rel_edges{from,label}` and `rel_edges{to,label}` carry the load (`migration/_0021__Indexes.java:146-159`).
- Revisit with `$graphLookup` if the hierarchy grows beyond two levels, or with a shared cache if a load test shows
  check volume is the bottleneck.

# 0012 — Relationship checks use direct queries anchored on the caller, not an in-memory graph

**Status:** accepted · **Date:** 2026-07-23

## Context

Access to workspaces, workflows and runs is decided from the `rel_nodes`/`rel_edges` collections, from the caller's
node to the target. With several `service-core` replicas, a copy of that graph held per instance answers from stale
data until it is rebuilt, and a stale answer here is a wrong access decision, not a slow one. Runs hang from their
workspace as leaves and outnumber every other node, so a walk that reads them costs memory in proportion to history.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-instance in-memory graph, rebuilt on mutation | One replica; deep, frequently walked graphs | Replicas disagree on access until each rebuilds |
| B. Direct MongoDB queries anchored at the caller's node, reading only the edges that can lead to the target | The hierarchy is shallow (`root → workspace → object`, `user → workspace`) | A few indexed queries per decision |
| C. A shared cache (for example Redis) in front of the queries | Very high check volume | A new runtime dependency and an invalidation protocol |

## Decision

B. `check` and `filter` read live MongoDB on every request; only node resolutions are memoised, for one HTTP request
(`service-core/src/main/java/io/boomerang/core/RelationshipService.java:96-181`). `findNodes` (`:950-1018`) reads a
named run from its own incoming edge and the caller's edge to that run's workspace (`:1036-1084`), starts a walk
confined to named workspaces at those workspaces (`:987-995,1013-1034`), reads the task catalogue from the root's
`hasTask` edges (`:1086-1116`), and otherwise walks level by level with the `hasWorkflowRun` edges left out on the
`from_label` index (`:1118-1124`). No answer depends on the number of runs. `RelationshipLookupEquivalenceTest` pins
every lookup to the full walk's answer for every token class; `RelationshipServiceTest` pins that two instances on
one database agree at once.

## Consequences

- Adding replicas needs no cache coordination; the indexes `rel_nodes{type,ref}`, `rel_nodes{type,slug}`,
  `rel_edges{from,label}` and `rel_edges{to,label}` carry the load (`migration/_0021__Indexes.java:152-165`).
- The shortcuts hold because no edge starts at a run or a workflow; an edge that does must come with its own lookup.
- Revisit with `$graphLookup` if the hierarchy grows beyond two levels, or with a shared cache if a load test shows
  check volume is the bottleneck.

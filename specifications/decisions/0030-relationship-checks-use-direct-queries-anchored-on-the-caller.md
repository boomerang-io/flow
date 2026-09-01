# 0030 — Relationship checks use direct queries anchored on the caller, not an in-memory graph

**Status:** accepted · **Date:** 2026-07-23

## Context

Access to workspaces, workflows and runs is decided by walking the `rel_nodes`/`rel_edges` collections from
the caller's node to the target. The previous implementation loaded those collections into a per-instance
graph object that was rebuilt on every mutation; with several `service-core` replicas, an edge written on one
instance was invisible on the others until they rebuilt.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-instance in-memory graph, rebuilt on mutation | one replica; deep, frequently traversed graphs | replicas disagree on authorization until each rebuilds — a stale cache here is a wrong access decision, not a slow one |
| B. Direct MongoDB queries: a level-by-level walk anchored at the caller's node | the hierarchy is shallow (`root → workspace → object`, `user → workspace`) | 3–7 indexed queries per decision; needs `$graphLookup` if the hierarchy deepens |
| C. Shared cache (for example Redis) in front of the queries | very high check volume | a new runtime dependency and an invalidation protocol to get right |

## Decision

Option B. Every `check()` and `filter()` reads live MongoDB: one edge query plus one node batch-load per
level, anchored at the caller's own node, with only a per-HTTP-request memo
(`service-core/src/main/java/io/boomerang/core/RelationshipService.java:31-51`, `check()` at `:364`,
`findNodes()` at `:603`). The deciding reason is correctness under replication: any instance sees every
committed write immediately, which `RelationshipServiceTest` pins with two service instances sharing one
database (`service-core/src/test/java/io/boomerang/core/RelationshipServiceTest.java:262-264`).

## Consequences

- Adding replicas needs no cache coordination; the loader-managed indexes on `rel_nodes{type,ref}`, `rel_nodes{type,slug}`, `rel_edges{from,label}` and `rel_edges{to,label}` carry the load.
- Hot execution data (runs, task runs) SHOULD carry `workspaceId` directly rather than pay a walk per read.
- Revisit with `$graphLookup` if the relationship hierarchy ever grows beyond two levels.

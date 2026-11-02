# 0049 — A workspace claims its one link to a template with a graph edge

**Status:** accepted · **Date:** 2026-11-02

## Context

A workspace runs a template through its own link, made on first use. The check for an existing link was a read before an insert, so concurrent first uses, such as the children of one for-each `runworkflow` task, each made a link. Workflows carry no workspace field to index: ownership is the `hasWorkflow` edge.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The link's workflow id derived from (workspace, template), insert-only | No graph change | A deleted link keeps its id until the watcher prunes it, so a re-adoption in between collides |
| B. A typed `workspace` field on workflows and a unique index on it with `templateRef` | The owner belongs on the workflow anyway | New field, index and a backfill change unit |
| C. An in-process lock around the check and the insert | One service-core instance | Two instances still race |
| D. A `linksTemplate` claim edge from the workspace to the link, its `_id` naming (workspace, template) | Several instances, no new field or index | A losing caller waits for the winner's link; a claim left by a dead creator needs a stale check |

## Decision

D. MongoDB's `_id` admits one claim per (workspace, template), so exactly one caller creates the link, under the id its claim names; the others wait for it (`workflow/WorkflowService.java:2319-2385`, `core/RelationshipService.java:289-342`). Deleting the link deletes its node and so the claim, which A could not do. The edge joins a workspace and its own workflow, nodes `hasWorkflow` already joins, so the walk, which follows every edge, reaches nothing new.

## Consequences

- Concurrent first uses make one link; re-adopting after a delete works at once.
- A claim whose link never appeared is taken over after 60 s, so a first use can wait that long after a crash.
- Links made before this have no claim; they are found by the existing read and are never claimed again.

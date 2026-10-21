# 0129 — Insights group task runs on one route, with exact percentiles

**Status:** accepted · **Date:** 2026-10-21

## Context

An embedder needed task-level timings over windows from an hour to 90 days: per model, per agent, per task kind,
per time bucket, the slowest task runs, and the wall clock of the runs that share a label. Insights grouped task
runs only by name, within one workflow (`WorkflowRunInsightService.workflowDetail`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One route with a generic key (`task`, `type`, `workflow`, `param:<name>`, `label:<key>`), time buckets and label spans | Any embedder's own param and label names | More query parameters to validate |
| B. Purpose-built routes: per model, per agent, slowest | One consumer whose keys never change | Flow would name an embedder's params; each new question needs a new route |
| C. Percentiles in MongoDB (`$percentile`) rather than in Java | Windows too large to stream | Approximate, needs MongoDB 7.0 or later, disagrees with the summary's tiles |

## Decision

A, with percentiles computed in Java by the nearest-rank method the summary already uses
(`service-core/src/main/java/io/boomerang/workflow/WorkflowRunInsightService.java:652`). One aggregation streams a
small document per counted task run on the `workflowRunRef`-first index (`:383`), and the rows, buckets and spans
are tallied as it streams (`:188`). The webapp's Insights page reads it only once a grouping is chosen.

## Consequences

- An embedder answers its questions with its own param and label names; Flow names none of them.
- A request reads every counted task run of the period, and a response past 1,000 rows is truncated. Revisit with
  option C or a rollup collection (decision 0089) when a load test shows the read too slow.
- A task run's end is its start plus its duration, which is exact for its last attempt; a requeued task's earlier
  attempts are not in a span.

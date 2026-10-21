# 0129 — Insights group task runs on one route, by fields Flow owns and by labels

**Status:** accepted · **Date:** 2026-10-21

## Context

An embedder needed task-level timings and usage over windows from an hour to 90 days: per model, per agent, per task
kind, per time bucket, the slowest task runs, and the wall clock of the runs that share a label. Insights grouped
task runs only by name, within one workflow (`WorkflowRunInsightService.workflowDetail`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One route grouping by `task`, `type`, `workflow` or a label, the server's model and item labels included, with usage summed per row | Callers group by what Flow owns, or by labels, which exist to be grouped on | A dimension Flow does not own must reach a label first |
| B. A, plus grouping by a named param and showing params on the slowest | Callers whose dimensions live only in task inputs | Params vary by task and can carry a secret: a secret substituted into an object or array keeps that param's type, so a type check alone shows it; a `model` param is the model asked for, not the one served |
| C. Purpose-built routes: per model, per agent, slowest | One consumer whose keys never change | Flow would name an embedder's concepts; each new question needs a route |
| D. Percentiles in MongoDB (`$percentile`) rather than in Java | Windows too large to stream | Approximate, needs MongoDB 7.0 or later, disagrees with the summary's tiles |

## Decision

A, with percentiles computed in Java by the nearest-rank method the summary already uses
(`service-core/src/main/java/io/boomerang/workflow/WorkflowRunInsightService.java:636`). One aggregation streams a
small document per counted task run, with its labels and the usage results an `ai` task reports, on the
`workflowRunRef`-first index (`:375`), and rows, buckets, spans and usage are tallied as it streams (`:188`). Model
and agent are labels the server sets (decision 0131). The webapp reads the route only once a grouping is chosen.

## Consequences

- An embedder answers its questions with labels; Flow names none of its concepts and reads no task input.
- A request reads every counted task run of the period, and a response past 1,000 rows is truncated. Revisit with
  option D or a rollup collection (decision 0089) when a load test shows the read too slow.
- A task run's end is its start plus its duration, exact for its last attempt; a requeued task's earlier attempts
  are not in a span.

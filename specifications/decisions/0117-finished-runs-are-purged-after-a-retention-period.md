# 0117 — Finished runs are purged after a retention period, by a sweep that follows lineage

**Status:** accepted · **Date:** 2026-10-16

## Context

A finished run and everything it owns — task runs with their params and results, actions, artifacts and task
logs — stayed forever; only a deleted workflow's prune removed them (`engine/WorkflowWatcher.java` `pruneDeletedWorkflows`).
Audit events and artifacts already expire on settings, and the products embedding Flow keep prompts and tool
calls on task runs as audit evidence, so the Activity store needed a bound of its own. A retried run reuses its
origin's task runs by reference (decision 0114), so the origin cannot go before the retry does.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A TTL index on `workflow_runs.creationDate`, applied at startup like the audit trail | Documents with nothing attached | Task runs, actions, artifacts, logs and the relationship node are left behind; an in-flight run or a reused origin is deleted on its date |
| B. A watcher sweep that pages finished runs past retention, skips any run a later run still names, and deletes each one's dependants before the run | What a run owns must go with it, and lineage must hold | A pass per interval and one existence check per candidate; a backlog drains over passes |
| C. No retention: operators delete runs themselves | Small installs | The store grows with every run; quota counting and Activity reads slow with it |

## Decision

B. One setting, `retention.days` on the `workflowrun` document (365, read at each pass, floored at 60 so a
month's quota counts stay whole; `seed/settings.json:456`, `WorkflowWatcher.runRetentionDays`), and one sweep on
its own hourly clock (`flow.watcher.retention-interval-ms`, `WorkflowWatcher.purgeExpiredRuns`) that pages
`completed` runs created before the cutoff on the existing `{phase, creationDate}` index, keeps any run still
named in another run's `initiatedByRef` (the `{initiatedByRef, phase}` index), and deletes the run's run- and
log-scoped artifacts (`workflow/ArtifactService.deleteForRun`), actions, task runs, relationship node and then the
run. Every delete is by ref and idempotent, so a pass that dies halfway is finished by the next. One pass is
bounded to twenty pages because the scheduler is single-threaded and the recovery sweeps share it. What decided
it: a run is a graph of documents, not one, and lineage is the one rule a TTL cannot express.

## Consequences

- Activity is bounded without any operator action; the setting is the only knob and lives beside the run limits.
- An origin run outlives its retention by as long as any retry or child that names it is kept.
- A settings document built before the key existed reads as 365 until it is rebuilt, like the log settings.
- Workflow-scoped artifacts and audit events are never purged by this sweep.
- Not built, each waiting for an ask with a case: a per-workspace override, a retention hold on one run, and a
  purge of one run through the API.

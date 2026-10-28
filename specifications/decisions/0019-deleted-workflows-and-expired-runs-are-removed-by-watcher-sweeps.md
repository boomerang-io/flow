# 0019 — Deleted workflows and expired runs are removed by watcher sweeps that never touch live work

**Status:** accepted · **Date:** 2026-10-16

## Context

A run is a graph of documents: task runs, actions, artifacts, logs and a relationship node. Deleting a workflow must
not orphan its in-flight runs, finished runs must not pile up forever, and a run a later run points at must outlast it.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Delete: cascade through the workflow's documents at once | Nothing is in flight | Running work is orphaned mid-run |
| Delete: a `deleted` status set by compare-and-set; the watcher cancels in-flight runs, then hard-deletes everything | Delete means delete | Pruned runs are unrecoverable; only the audit trail survives |
| Retention: a time-to-live (TTL) index on `workflow_runs.creationDate`, like the audit trail | A run is one document | Task runs, actions, artifacts and logs are left behind; an in-flight run or a reused origin goes on its date |
| Retention: a watcher sweep that pages finished runs past retention, skips any a later run names, and deletes each run's dependants first | What a run owns goes with it and lineage holds | A pass per interval and one existence check per candidate; a backlog drains over passes |

## Decision

The tombstone and the sweep. `WorkflowService.delete` is one guarded update to `deleted` (`workflow/WorkflowService.java:1975-1987`);
the scoped delete also removes schedules, tokens, actions and the relationship node at once (`:691-708`), and submit
refuses a non-active workflow (`:1786-1788`). The watcher's 30-second sweep cancels a deleted workflow's in-flight runs
(`engine/WorkflowWatcher.java:336-351`); once none is in flight, `pruneDeletedWorkflows` purges each run, then the
artifacts, task runs, revisions, schedules, actions, node and workflow (`workflow/WorkflowRetentionService.java:127-167`).
`purgeExpiredRuns` runs hourly (`flow.watcher.retention-interval-ms`, `engine/WorkflowWatcher.java:191-201`): it pages
`completed` runs created before the cutoff on `{phase, creationDate}`, keeps any run another run names in
`initiatedByRef`, and deletes each run's run- and log-scoped artifacts, actions, task runs, node and then the run, at
most 20 pages of 50 a pass (`workflow/WorkflowRetentionService.java:87-125,182-189`). The cutoff is the `workflowrun`
setting `retention.days` (365, read each pass, floored at 60; `:169-180`, `service-core/src/main/resources/seed/settings.json:454-461`).
Every delete is by ref and idempotent, so the next pass finishes a broken one; audit records are never touched. What
decided it: a status marker and a sweep wind work down safely, and lineage is a rule a TTL cannot express.

## Consequences

- A deleted workflow's runs finish `cancelled`, and its documents leave within a sweep of its last run finalising.
- Purging begins at first start and no value turns it off, so upgraders export history first (`README.md:171-175`).
- The monthly run quota is the larger of the audit count and the live count (`workflow/QuotaService.java:222-230`,
  decision 0070); the 60-day floor keeps a month's live count whole. Workflow-scoped artifacts are never purged here.
- Not built, each waiting for a case: a per-workspace retention, a hold on one run, and purging one run by API.

# 0071 — Deletion completes: the prune sweep hard-deletes once runs finalise, with no flag

**Status:** accepted · **Date:** 2026-09-03

## Context

Workflow delete is a tombstone: the status flips to deleted, submit rejects new runs, and the watcher
cancels in-flight runs (decision 0019). The prune half was a no-op behind
`flow.watcher.retention.enabled`, so a deleted workflow's documents accumulated forever and delete
never actually removed anything.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the flag; prune only when a retention policy is configured | age-based run retention is a real, designed feature | deletion never completes by default; the flag guards a policy that does not exist |
| B. Prune unconditionally once a deleted workflow has no in-flight runs | delete means delete; run history for deleted workflows is not a kept promise | pruned run documents are unrecoverable; only the audit trail survives |

## Decision

Option B. `WorkflowWatcher.pruneDeletedWorkflows`
(`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:284`) pages deleted workflows,
skips any with a run still in flight (the cancel sweep finishes first, so live work is never pruned),
and hard-deletes the task runs, workflow runs, revisions, leftover actions, schedules and relationship
node, then the workflow document. Audit records are never touched. The flag is gone: a retention
policy, if ever wanted, is a feature to design, not a switch guarding a no-op.

## Consequences

- Delete completes: a deleted workflow's documents leave the database within a sweep interval of its runs finalising.
- Pruned runs leave the live counts, so workspace quotas no longer see a deleted workflow's runs; the audit trail is the surviving record of them.
- Age-based retention for live workflows' runs remains unbuilt; revisit when a deployment asks for it.

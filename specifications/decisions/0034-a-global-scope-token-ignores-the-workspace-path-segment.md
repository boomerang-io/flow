# 0034 — A global-scope token ignores the workspace path segment by design

**Status:** accepted · **Date:** 2026-08-24

## Context

Workspace-scoped routes such as `/api/v2/workspace/{workspace}/workflowrun/{id}` pass `{workspace}` to
`RelationshipService.check()` as an intermediate containment, so a member of workspace A cannot read a run
by naming it under workspace B. For a `global`-class token `check()` returns `true` before any walk, so the
segment is never compared. This was raised as a possible privilege escalation.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Walk the containment for global tokens too | a global token should be told when it names the wrong workspace | extra queries on every admin call, for an error the caller can trivially avoid by using the right URL |
| B. Keep `global` as "everything, no walk" | global scope means the whole platform | the path segment is not validated for admins |

## Decision

Option B. `check()` is `case global: return true;`
(`service-core/src/main/java/io/boomerang/core/RelationshipService.java:417-419`), while `filter()` still
anchors a global caller at `root` and walks so list endpoints return real data (`:509-513`). The deciding
reason is that a global token is already permitted to reach the same object through its true workspace URL,
so ignoring the segment grants nothing the caller does not already hold.

## Consequences

- Admin tooling MAY address any object under any workspace path; the response is the same object.
- Ownership writes MUST NOT trust the path segment for a global caller: run creation confirms the workspace contains the workflow first, and retries resolve the owner from the original run's edge (`workflow/WorkflowService.java:561-572`, `workflow/WorkflowRunService.java:394-400`).
- `key` tokens are unaffected: they anchor at their own workspace node and the walk enforces containment.

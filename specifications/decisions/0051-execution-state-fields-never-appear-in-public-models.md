# 0051 — Execution-state fields never appear in the public run models

**Status:** accepted · **Date:** 2026-08-18

## Context

The engine keeps claim, deadline, retry and pause state on `WorkflowRunEntity` and
`TaskRunEntity` so that any instance can claim, fence and reap work. If those fields reached
`/api/v2`, clients would build on scheduling internals that change with the execution model, and
the entities could no longer be reshaped freely.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Public models extend the entities and hide fields with `@JsonIgnore` | Fewest classes | One forgotten annotation leaks a field; the entity shape becomes the API shape by default |
| B. Standalone public POJOs with an explicit field list, pinned by a serialisation test | The contract must be stable and reviewable | A mapping step on every read; the two lists must be kept in step by hand |

## Decision

Option B. `WorkflowRun` and `TaskRun` are standalone classes
(`lib-common/src/main/java/io/boomerang/common/model/TaskRun.java:19-23`), and
`service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:43-76` fails
the build if `claim`, `timeoutAt`, `retry`, `retryAfter`, `waitUntil`, `pauseRequestedAt`,
`agentRef` or `dispatcherRef` is serialised. Pause is exposed only as the derived boolean `paused`
(`WorkflowRun.java:52-54`; test `:80-87`).

## Consequences

- Entity fields can be added or renamed without an API change; the reverse also holds, so every new public field is a deliberate edit to the model class.
- Adding an execution-state field means adding it to the forbidden set in the test, not just to the entity.
- `phase` is the one lifecycle field still shared with the public models (see 0050).

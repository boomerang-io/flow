# Horizontal Scaling — Locking and Ownership Validation

**Status:** 🔴 Analysis required  
**Owner:** Claude Code  
**Depends on:** Read alongside `service-consolidation.md`  
**Last updated:** —

---

## Brief

Validate whether the current locking and ownership model in `service-engine` is safe under
horizontal scaling — multiple instances of the same service competing to process the same
WorkflowRuns and TaskRuns. Produce a written assessment with specific gaps identified and
a hardening plan. Do not begin implementation until the assessment is reviewed.

### What to do

1. **Map every lock and ownership assertion in the codebase.** For each one record:
   - Where it is (class, method)
   - What it is protecting (which resource, which state transition)
   - The lock mechanism used (MongoDB TTL lock, `@Version` optimistic lock, Quartz lock, none)
   - Whether it is safe under N concurrent instances
   - The failure mode if the lock is not held (duplicate execution, lost update, split-brain)

2. **Audit the distributed lock implementation.** The current lock is in `LockManagerImpl.java`
   using `alturkovic/distributed-lock` with a MongoDB TTL backend. Assess:
   - What granularity is the lock held at (per-workflow, per-task, global)?
   - What is the TTL and is it appropriate for the longest-running operation it protects?
   - What happens if the lock holder crashes — does the TTL expire correctly?
   - Is the lock re-entrant? Does it need to be?
   - Under what concurrency level does lock contention become a throughput problem?

3. **Audit the Quartz cluster configuration.** Quartz uses the MongoDB job store for scheduling.
   Assess:
   - Is Quartz clustering enabled and correctly configured for multi-instance deployment?
   - Are scheduled workflow triggers protected from firing on every instance simultaneously?
   - What is the `misfireThreshold` and is it appropriate?
   - Is the Quartz MongoDB job store compatible with the MongoDB version in use?

4. **Audit the CloudEvents consumer.** When multiple instances are running:
   - Does every instance consume every event, or is there consumer group partitioning?
   - If every instance processes every event, what prevents duplicate WorkflowRun transitions?
   - Is the `WorkflowTransitionService` event handler idempotent under concurrent execution?
   - What message broker is in use (in-process Spring events vs external broker)?

5. **Audit `WorkflowWatcher` (the reconciler).** The `@Scheduled` reconciler runs on every
   instance. Assess:
   - When N instances all call `reconcile()` simultaneously, what happens?
   - Does the optimistic `@Version` lock on `WorkflowRunEntity` prevent double-transition?
   - Is there a leader-election or instance-claim mechanism, or does every instance reconcile
     the full queue?
   - What is the performance impact of N instances all querying for orphaned runs every 60s?

6. **Identify the ownership model.** When a TaskRun is picked up by an agent poller:
   - Is there a claim/lease mechanism that prevents two agent instances picking the same TaskRun?
   - How long is the claim TTL?
   - What happens if the agent instance that claimed a TaskRun crashes mid-execution?
   - Does the reconciler detect and recover unclaimed/expired TaskRuns?

7. **Validate the `@Version` optimistic locking coverage.** Spring Data's `@Version` field
   on `WorkflowRunEntity` provides last-write-wins conflict detection. Assess:
   - Is `@Version` present on all entities that require concurrent modification protection?
   - Is the `OptimisticLockingFailureException` caught and retried at the correct level?
   - Are there state transitions that bypass the versioned save path (e.g. `$set` updates
     that do not go through the Spring Data repository)?

8. **Produce the gap list and hardening plan.** For each gap identified, specify:
   - Severity (blocks safe horizontal scaling / degrades under scale / acceptable risk)
   - The fix (what mechanism to use, what the correct granularity is)
   - Whether it must be fixed before the service-consolidation merge or can follow

### What not to do

- Do not implement fixes during the analysis phase — document them in the hardening plan
- Do not change lock granularity or TTL values without validating against the longest
  operation in the codebase
- Do not introduce a new locking library without benchmarking it against the existing one

---

## Architecture Context

### Current locking mechanisms

| Mechanism            | Library / Implementation                          | Scope                               |
| -------------------- | ------------------------------------------------- | ----------------------------------- |
| Distributed lock     | `alturkovic/distributed-lock` (MongoDB TTL index) | Coarse — protects task pickup       |
| Optimistic lock      | Spring Data `@Version` on `WorkflowRunEntity`     | Per-entity write conflict detection |
| Quartz cluster lock  | MongoDB job store (Quartz built-in)               | Schedule trigger deduplication      |
| CloudEvents ordering | Unknown — to be determined during analysis        | Event processing deduplication      |

### The ARCHIE reference implementation

ARCHIE's queue architecture (see `specifications/archie-patterns.md`) has solved these
problems in production. The key patterns to adopt:

**Versioned writes for all WorkflowRun state transitions.**
Every state change goes through a repository save that includes the `@Version` field.
`OptimisticLockingFailureException` is caught and the transition is retried with a fresh
read. This makes all transition handlers safe to call concurrently.

**Idempotent reconcile() — safe to call from N instances simultaneously.**
Before creating a TaskRun for a DAG step, check whether a non-SUPERSEDED SUCCEEDED run
already exists for that step. If it does, skip. This means N concurrent reconcile() calls
converge to the correct state rather than creating N duplicate TaskRuns.

**TaskRun claim via status transition.**
A TaskRun is "claimed" by transitioning it from QUEUED to RUNNING in a single versioned
write. If two pollers attempt this simultaneously, one gets `OptimisticLockingFailureException`
and backs off. No separate lock document is required.

**WorkflowWatcher skips PAUSED runs.**
The reconciler does not attempt to advance PAUSED WorkflowRuns. This prevents the reconciler
from racing with a manual resume operation.

### Horizontal scaling target architecture

The target model for v5 scaled deployments:

```
Load Balancer
  ├── flow instance 1  ┐
  ├── flow instance 2  ├── compete via optimistic lock on WorkflowRunEntity
  └── flow instance N  ┘
        │
        ▼ CloudEvents partitioned by workflowRunId
  ├── agent instance 1  ┐
  ├── agent instance 2  ├── compete via TaskRun status claim
  └── agent instance N  ┘
```

Partitioning CloudEvents by `workflowRunId` means one flow instance owns a given workflow
run's event stream at any point in time. This eliminates the concurrent reconcile problem
for most transitions — the owning instance handles the event, the reconciler is only needed
for crash recovery.

This model must be validated against the current CloudEvents consumer implementation.

---

## Known Issues

- `LockManagerImpl.java` uses a coarse lock that may be held across long-running TaskRun
  operations — this needs to be validated
- There is no documented claim/lease mechanism for TaskRun pickup — this is a gap
- The `WorkflowWatcher` reconciler interval and query scope under multi-instance deployment
  is undocumented
- Quartz clustering configuration for multi-instance deployment is not validated in the
  current test suite

---

## Assessment (To Be Completed by Claude Code)

> This section is empty. Claude Code will populate it after completing the analysis above.
> The assessment must include:
>
> **Lock inventory table** — every lock/ownership assertion in the codebase, classified by
> mechanism, granularity, and safety under N instances.
>
> **Gap list** — specific gaps with severity ratings.
>
> **Hardening plan** — sequenced fixes, each tagged as:
>
> - `BEFORE-MERGE` — must be resolved before service consolidation
> - `WITH-MERGE` — can be addressed as part of the consolidation work
> - `POST-MERGE` — acceptable to defer, documented risk

---

## Decisions

> Record design decisions here as they are made. Use the format:
>
> **DD-01: [Title]**  
> Decision: ...  
> Rationale: ...  
> Rejected alternatives: ...  
> Date: ...

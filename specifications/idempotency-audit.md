# Q-127 Idempotency Audit — Transition Handlers (2026-07-22)

**Status:** ✅ Audit complete. This is the **Phase 3 gating list** — the alturkovic locks
cannot be deleted (and the level-triggered watcher cannot re-drive transitions) until the
ranked items in §2 are fixed. All refs under
`service-engine/src/main/java/io/boomerang/`.

## Structural findings (frame everything)

- **F1 — No `@Version` anywhere.** Every `repository.save()` is a full-document
  last-writer-wins replace. Rule 3 (versioned/CAS writes) is violated by every handler;
  concurrent writes lose whole-document updates (results, retry-count annotations,
  `statusOverride`, `isAwaitingApproval`).
- **F2 — Today's locks are NOT actually mutually exclusive.** `LockManager.acquireLock`
  (`LockManager.java:157-166`) does non-atomic exists-then-acquire, and the token supplier
  is deterministic (`token == key`, `:120-121`) — alturkovic `SimpleMongoLock.acquire`
  succeeds when the stored token equals the supplied token, so a racing acquirer with the
  same key **also succeeds**, and `releaseLock(key, key)` deletes anyone's lock. The system
  already runs on best-effort locks; "SAFE-ONLY-UNDER-LOCK" below means "mostly works
  single-instance".

## 1. Audit table

Verdicts: SAFE · UNSAFE-DUP (duplicate side effect) · UNSAFE-RACE (lost update/corruption)
· SAFE-ONLY-UNDER-LOCK.

| # | Handler | Guards today | Concurrent behaviour (post-lock world) | Verdict | Failure artifact | Fix shape |
|---|---|---|---|---|---|---|
| 1 | `WorkflowExecutionService.queue` :64 | none | Both insert TaskRuns per step | UNSAFE-RACE | Duplicate TaskRun per DAG step (breaks `findFirstByName…`) | Unique index `(workflowRunRef, name)`; DuplicateKey = already-created |
| 2 | `executeWorkflowAsync` :143 | lock; re-read + phase guard | Both set running, queue first tasks, schedule timeout | SAFE-ONLY-UNDER-LOCK | Duplicate first-task exec; two timeout jobs (feeds #5) | CAS `pending/queued→running`; winner-only |
| 3 | `WorkflowExecutionService.end` :115 | none | Same-value write | SAFE | — | `$set phase=finalized` + precondition |
| 4 | `WorkflowExecutionService.cancel` :120 | **no status/phase check** | Can stomp a just-succeeded run | UNSAFE-RACE | Lost terminal status; double CloudEvent | CAS `phase in [pending,queued,running]→completed` |
| 5 | `timeoutWorkflow` :230 | re-read + `phase==running` (lock after guard) | Both pass guard → **both auto-retry** | UNSAFE-RACE | **Duplicate retry WorkflowRun**; stale retry-count | CAS returning old doc; retry on winner; `$inc` retry-count |
| 6 | `TaskExecutionService.queue` :77 | stale-entity phase guard; queue leaves phase=pending | Double `execute()` of system tasks | UNSAFE-DUP | Duplicate child workflows/schedules/actions | Admission CAS `pending/notstarted→ready`; re-read by id |
| 7 | `TaskExecutionService.start` :155 | stale guard; execute dispatched after lock release | Double execute + double timeout futures | UNSAFE-RACE | Double running-transition | CAS at execute entry (#8) |
| 8 | `TaskExecutionService.execute` :226 | **none** ("handled in start()") | Full re-execution of type switch | UNSAFE-DUP | The multiplier for #12/15/16/18 | CAS `pending/queued→running`; return if no match |
| 9 | `TaskExecutionService.end` :322 | stale guard; locks but **no re-read inside**; advance after save | 2nd save overwrites final status; **both run finishedAll+executeNextStep** | UNSAFE-RACE (even with locks) | Wrong terminal status; join queued twice; double finishWorkflow | **The gating fix**: `findAndModify(running→completed)` returning pre-image; winner-only advance; advance = reconcile |
| 10 | `finishWorkflow` :870 | lock only | Double CloudEvent | SAFE-ONLY-UNDER-LOCK | Dup wfRun-completed event | CAS `running→completed` |
| 11 | `executeNextStep`/`canExecuteTask` :905/:978 | dep re-read; queue decision unguarded | Join task queued by both parents | UNSAFE-RACE | Fan-in task runs twice | Solved by #6 admission CAS |
| 12 | `createActionTask` :792 | none | **New ActionEntity every call** | UNSAFE-DUP | Duplicate approval records per gate | Unique index `actions(taskRunRef)`; find-before-create |
| 13 | `saveWorkflowParam` :840 | lock (entity read before lock) | **Appends result again — even sequentially** | UNSAFE-DUP | Param double-append | `$pull`+`$push` by name, or CAS + unique-name merge |
| 14 | `updatePendingAprovalStatus` :508 | none | Recompute OK; full-doc save races | SAFE (calc) / racy save | Lost concurrent fields | `$set` single field |
| 15 | `runWorkflow` :633 | none | **New child WorkflowRun every call** | UNSAFE-DUP | Duplicate child execution | Idempotency key: child annotated with parent taskRunId + unique index |
| 16 | `runScheduledWorkflow` :676 | none | **New schedule every call** | UNSAFE-DUP | Duplicate future run | Idempotency key (taskRunRef) |
| 17 | `createSleepTask` :548 | none | Sleeps again (thread) | SAFE-ish | Doubled latency; crash-unsafe | Scheduled end, not `Thread.sleep` |
| 18 | `processWaitForEventTask` :764 | none — unconditional `status=waiting` save | Re-arms eventwait after completion; races event delivery | UNSAFE-RACE | **Wedged run** | CAS: waiting only if running+notstarted/ready |
| 19 | `updateTaskRunForTopic` :460 | — | Same-value write | SAFE | — | Good example |
| 20 | `timeoutTaskAsync` :439 | re-read + phase==running ✓ | Races #9's end | Guard good; outcome depends on #9 | Succeeded recorded timedout | Fixed by #9 CAS |
| 21 | `WorkflowRunService.run/submit` :379 | fresh insert | Caller-level duplicates | SAFE per-invocation | Dup runs on redelivery | Request idempotency key (needed for async ingress) |
| 22 | `WorkflowRunService.start` :390 | downstream guard | `workspaces.addAll` duplicates | minor UNSAFE-DUP | Dup workspace entries | Unique-merge |
| 23 | `WorkflowRunService.retry` :474 | none | **New cloned run every call** | UNSAFE-DUP | Duplicate retry run | Unique `(retry-of, retry-count)` or CAS marker |
| 24 | `WorkflowRunService.event` :539 | status filter | Non-waiting branch double-appends results; double-delivery → double end | UNSAFE-DUP | Dup results on eventwait | `addUniqueResults`; event-id dedup |
| 25 | `WorkflowRunService.timeout` :454 | **none** — unconditional `status=timedout` | Stomps a succeeded run | UNSAFE-RACE | Succeeded recorded timedout | CAS `phase==running` precondition |
| 26 | `WorkflowRunService.finalize` :419 | none | Same-value | SAFE | — | Add precondition |
| 27 | `AgentService.register` :56 | none | **New AgentEntity every restart** | UNSAFE-DUP | Unbounded duplicate agents | Upsert unique `(name, host)` |
| 28 | `AgentService.getWorkflowQueue` :88 | agentRef guard in update only | **find-then-update returns FIND result** — claim loser still dispatches; completed runs redelivered to every agent every poll | UNSAFE-RACE | Duplicate dispatch feeding #2 | Per-doc `findAndModify` claim loop + `claimedBy/claimedAt` + epoch (ARCHIE pattern) |
| 29 | `AgentService.getTaskQueue` :148 | same | Same race; terminal tasks redelivered every poll | UNSAFE-RACE | Duplicate dispatch feeding #7/#9 | Same findAndModify claim |
| 30 | `*UpdateInterceptor` aspects :33-74 | DB old-vs-new diff | Both writers see pre-transition doc → **double CloudEvent**; `@Before` = publish-before-commit → phantom events | UNSAFE-DUP | Dup/phantom status events | Transactional outbox keyed `(ref, from, to)` unique |
| 31 | Audit `@AfterReturning` :58-94 | none | Dup audit rows per dup save | UNSAFE-DUP (minor) | Duplicate audit entries | Follows from CAS-everywhere |

## 2. Phase 3 gating list (ranked — fix BEFORE lock deletion)

1. **#9 `end` completion CAS** — the graph-advance funnel; winner-only advance, advance = reconcile.
2. **#28/#29 agent claim atomicity** — the *producer* of duplicate dispatch into everything else.
3. **#6/#8 task admission CAS** — makes duplicate dispatch harmless.
4. **#2 start CAS** — pending→running winner-only.
5. **#5/#23 timeout+retry idempotency** — duplicate runs are user-visible artifacts.
6. **#12/#15/#16 side-effect idempotency keys** (actions, child workflows, schedules).
7. **#13/#24/#22 append dedup** (params, event results, workspaces).
8. **#1 unique index `task_runs(workflowRunRef, name)`** — rule 4's enforcement point.
9. **#30 event outbox** — CAS retries must not double-fire CloudEvents.
10. **#4/#25 terminal-status CAS protection** (cancel/timeout cannot stomp completed runs).

## 3. Patterns to copy (already idempotent)

`timeoutTaskAsync` (re-read + phase guard — the only handler following rules 1+2);
`timeoutWorkflow`'s entry guard shape; `updateTaskRunForTopic` + `WorkflowExecutionService.end`
(same-value writes); `updatePendingAprovalStatus` (level-triggered recompute, wrong write
primitive); `DAGUtility.createTaskList` (natural-key lookup before create — needs only the
unique index).

## 4. Cross-cutting fixes (whole classes at once)

1. **Pass ids, not entities** across `@Async` boundaries (`TaskExecutionClient`/
   `WorkflowExecutionClient` hand stale snapshots) — re-read-by-id at handler entry fixes
   rule 1 for all 30 handlers.
2. **Field-scoped atomic updates** (`updateFirst`/`findAndModify` `$set`) for status/phase;
   `@Version` on `WorkflowRunEntity` for remaining multi-field writes.
3. **Unique indexes**: `task_runs(workflowRunRef, name)`, `actions(taskRunRef)`,
   `agents(name, host)`, request-idempotency-key on `workflow_runs`.
4. **CloudEvent outbox** replacing the aspect-on-save pattern (exactly-once per transition).
5. **Claim ownership + fencing at handler entry** (`claimedBy/claimedAt/epoch` checked at
   `start`/`end`) — the backstop rejecting dispatches from superseded claims.

# Entity Diff — v4 (`main`) → v5 (`feat-v5`)

**Status:** ✅ Reviewed + actioned (2026-08-18). **Evolving** — re-run the diff when an entity changes.

Field-level comparison of every `*Entity` between the v4 line (`main`, `ebff44e3`) and the v5
line, with the reason each element was added, the loader-migration side effects, and the
dispositions taken. Method: `git diff main HEAD -- <entity>` + `git log -S<field>` for the
introducing commit + grep of readers/writers + a read of every `service-loader` change unit.

## 1. Headline

Of 22 entities: **12 byte-identical** (module move only), **3 pure renames**, **4 gained
fields**, **3 new**, **3 removed**. `Workflow`, `WorkflowRevision`, `WorkflowTemplate`, `Task`,
`TaskRevision`, `Action` are unchanged.

## 2. Fields added to existing entities

| Entity | Field | Type | Why | Commit |
|---|---|---|---|---|
| WorkflowRun | `claim` | `RunClaim{by,at,leaseExpiresAt,seq}` `@JsonIgnore` | E4-B atomic per-doc claim + `claim.seq` fencing; `by` = the registered dispatcher id holding the claim | `6b9b1bd4`, `f58533c8` |
| WorkflowRun | `timeoutAt` | `Date` `@JsonIgnore` | E4-C durable deadline for the watcher reap sweep | `89c53f73` |
| WorkflowRun | `pauseRequestedAt` | `Date` | E4-D pause as a flag, never a `RunStatus` (H15) — **being removed in a separate stream** | `8c148453` |
| WorkflowRun | `retryCount` | `Long` `@JsonIgnore` | DD-08 `boomerang.io/retry-count` annotation → typed field | `c989b82c` |
| TaskRun | `claim` | `RunClaim` `@JsonIgnore` | as above | `6b9b1bd4` |
| TaskRun | `timeoutAt` | `Date` `@JsonIgnore` | as above; deadline anchored to execution start (`aded50b0`) | `89c53f73` |
| TaskRun | `retry` | `RunRetry{after,count}` `@JsonIgnore` | E4-C typed backoff on requeue | `89c53f73` |
| TaskRun | `waitUntil` | `Date` `@JsonIgnore` | E4-F durable `sleep`/`acquirelock` wait re-driven by the watcher | `e3f43bd9` |
| WorkflowSchedule | `nextFireAt` | `Date` | E5 claim-based `ScheduleWatcher`; the advance-CAS on it is the exactly-once fence | `1b7a57e2` |
| WorkflowSchedule | `lastFiredAt` | `Date` | E5 observability (write-only in the backend; surfaced on the API model) | `1b7a57e2` |
| WorkflowSchedule | `retryCount` | `int` | E5 restores JobRunr's 3-retry behaviour (was `fireAttempts`) | `1b7a57e2`, `b355c478` |
| Token | `actorKind` | `SERVICE/AGENT/WORKFLOW` | T6-1 dispatcher token is a real Flow token | `ebe7366d` |
| Token | `createdBy` | `String` | T6-1 server-injected principal | `ebe7366d` |
| Token | `lastUsedAt` | `Date` | T6-1 throttled last-used stamp on the dispatcher auth path | `ebe7366d` |

**Removed again in this review (2026-08-18):** `WorkflowRunEntity.dispatcherRef` /
`TaskRunEntity.dispatcherRef` (added by `9e85d44a` as the DD-06 rename of `agentRef`). They were a
byte-for-byte duplicate of `claim.by` — written with the same value on every claim, cleared
together on every requeue, one reader (`WorkflowWatcher.reapClaimsFromGoneDispatchers`, now
reads `claim.by`). `_0031` unsets both spellings; `_0015` no longer renames `agentRef`.

Enum additions: `WorkflowStatus.deleted` (tombstone delete — ruled "no `tombstonedAt`"),
`TriggerEnum.retry` (DD-08 lineage via `initiatedByRef`), `PermissionScope{global,workspace}`,
`TokenActorKind`, `InboxStatus`, `OutboxStatus`.

## 3. Renames / type changes (no new data)

| Change | Ruling | Migration |
|---|---|---|
| `TeamEntity`→`WorkspaceEntity`, `teams`→`workspaces`, `TeamType/Status`→`WorkspaceType/Status` | DD-01 | `_0016` |
| `AgentEntity`→`DispatcherEntity`, `agents`→`dispatchers` | DD-06 | `_0015` |
| `TokenEntity.type` values `session/user/team/workflow/global` → `session/user/key/global` | T6-3 | `_0028` deletes retired `team`/`workspace`/`workflow` rows |
| `RoleEntity.type` `AuthScope`→`PermissionScope`; values `team`→`workspace` | T6-3 | `_0016` |
| `RelationshipType.TEAM`→`WORKSPACE`, `AuditScope.TEAM`→`WORKSPACE` | DD-01 | `_0016` (`_0013` now seeds `WORKSPACE` directly) |
| Public models `TaskRun`, `WorkflowSchedule`, `WorkflowTemplate` no longer `extends *Entity` | so `claim`/`timeoutAt`/… cannot leak into API responses | — |

## 4. New / removed entities

| Entity | Collection | Why |
|---|---|---|
| **new** `TaskLockEntity{_id=key,holder,workflowRunRef,acquiredAt,expiresAt}` | `task_locks` | E4-F replaces the alturkovic lock behind `acquirelock`/`releaselock` |
| **new** `EventOutboxEntity{refType,ref,from,to,occurredAt,routing,status,attempts,retry,sentAt}` | `events_outbox` | E4-D transactional outbox replaces the CloudEvent aspect interceptors |
| **new** `EventInboxEntity{_id=runId:eventId,topic,requestedStatus,status,receivedAt,processedAt}` | `events_inbox` | E4-D inbound event dedup |
| **removed** `EventQueueEntity` | `event_queue` | superseded by the outbox; collection dropped by `_0031` |
| **removed** engine `AuditEntity` copy | — | E8 fold; `service-core/.../core/audit/AuditEntity` is the single live audit entity |

## 5. Migration-written elements with no entity counterpart — all cleaned up by `_0031`

| Migration | Collection | Field | Purpose |
|---|---|---|---|
| `_0009` | `workflows` | `scope`, `ownerRef` | hand-off to `_0010`/`_0012` graph build |
| `_0011` | `workflow_runs` | `scope`, `ownerRef` | hand-off to `_0012` |
| `_0008` | `users` | `flowTeamRefs` | hand-off to `_0012` memberOf edges |
| `_0007` | `approver_groups` | `workspaceRef` | "discoverability" |
| `_0015` (old revision) | `task_runs`, `workflow_runs` | `dispatcherRef` (and v4 `agentRef`) | claim owner duplicate |

Transient-value fixes: `_0013` writes `audit.scope="WORKSPACE"` directly (no longer relies on
`_0016`); `_0016` no longer rewrites `tokens.type` `team`→`workspace` (a value no v5 enum accepts)
— `_0028` deletes `team`/`workspace`/`workflow` typed tokens either way.

## 6. Anomalies and dispositions (2026-08-18)

| # | Finding | Disposition |
|---|---|---|
| 1 | Entity `@Indexed`/`@CompoundIndex` inert; no loader unit created `workflows.name`, `workflow_revisions{workflowRef,version}`, `tasks.name`, `workflow_templates{name,version}`, `workflow_schedules.nextFireAt` | **Fixed** — `_0033__DefinitionIndexes` (+ `task_revisions{parentRef,version}`, `workflow_schedules{status,nextFireAt}` sweep, `{workflowRef}`); `MigrationUtils.ensureIndexKeys` keeps a v4 auto-built index with the same keys. `task_runs{status,phase}` deliberately not created — no query needs it without `type`/`workflowRunRef` in front. `_0019` javadoc corrected. |
| 2 | `claim.leaseExpiresAt` declared + `lease_sweep` indexed, never written | **Left** — AM-3 (leases deferred), the field is pre-provisioned for worker leases; the only inert index. |
| 3 | `EventOutboxEntity._id` is a generated ObjectId, not the spec's `<refType>:<ref>:<seq>`; no `transitionSeq` on the runs | **Open — G2 gate.** Proposed: `transitionSeq: Long` on `WorkflowRunEntity`/`TaskRunEntity`, `.inc("transitionSeq",1)` inside `findAndModifyPreImage` (all 15 transition CAS sites route through it), `TaskRunTransition`/`WorkflowRunTransition` carry `seq = pre+1`, `CloudEventsBridge` sets `_id` and swallows `DuplicateKeyException`. |
| 4 | `WorkflowRunService.retry()` clone inherited `claim`, `timeoutAt`, `isAwaitingApproval`, `statusOverride`, `results` | **Fixed** — cleared on the clone (`pauseRequestedAt` untouched; being removed). |
| 5 | `waitUntil` written via whole-entity `save()` (last-write-wins over `claim`/`retry`/`timeoutAt`) | **Fixed** — `TaskRunService.tryPark(id, waitUntil)` targeted update fenced on `phase=running`; the two `TaskExecutionService` park sites (`createSleepTask`, `acquireTaskLock`) call it. No transition event is published for the park (pre-existing behaviour). |
| 6 | `event_queue` never dropped; hand-off fields never unset | **Fixed** — `_0031`. |
| 7 | Settings key `teams`/"Team Quotas" not renamed by DD-01; `TEAMS_SETTINGS_KEY` duplicated | **Fixed** — key `workspaces`/"Workspace Quotas" (`_0032`, seed, single `WorkspaceService.WORKSPACES_SETTINGS_KEY`). The `features` flags `teamQuotas`/`teamParameters`/`teamManagement`/`teamTasks` are NOT renamed — the webapp reads `feature["team.quotas"]`; that is a coordinated frontend change. |
| 8 | Stale change-unit numbers in javadoc | **Fixed** (`RelationshipType`, `_0012`, `_0019`). |
| 9 | `retryCount` lacked `@JsonIgnore` | **Fixed**. |

Also noted, not actioned: `WorkflowScheduleEntity.schedulerRef` (dead JobRunr id, kept to avoid a
migration); `TaskLockEntity.workflowRunRef/acquiredAt` and `EventInboxEntity.topic/requestedStatus/processedAt`
are write-only diagnostics; `EventOutboxEntity.retry.count` never written (the counter is `attempts`).

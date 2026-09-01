# Entity Diff — v3 → v4 → v5

**Status:** ✅ Reviewed + actioned (2026-08-18); **Part A added 2026-09-01** from the archived
`boomerang-io/community` `architecture/flow/DataMigration.md` and ADR003. **Evolving** — re-run
the diff when an entity changes.

Scope. **Part A** is the v3→v4 model change as the 2022 community doc described it, reconciled row
by row against the loader change units that actually perform the migration
(`service-loader/src/main/java/io/boomerang/loader/migration/_0004__V3DropDeadCollections.java` …
`_0014__V3DropIntermediates.java`); where the doc and the code differ, the code wins and the
drift is listed in A.4. **Part B** is the field-level v4 (`main`, `ebff44e3`) → v5 (`feat-v5`)
diff, with the reason each element was added, the loader side effects, and the dispositions
taken. Part B method: `git diff main HEAD -- <entity>` + `git log -S<field>` for the introducing
commit + grep of readers/writers + a read of every `service-loader` change unit.

## Part A — v3 → v4 (as described in 2022, as shipped by the v5 loader) (Stable)

The v5 loader migrates v3 data **directly** into the v5 shape in one chain (DD-07). Units
`_0004`…`_0014` are generation-gated to v3 installs (`LegacyGenerationMarker`) and no-op
elsewhere; there is no intermediate v4 state on disk. The tables therefore show `v3 → v5` and
mention the v4 intent only where v5 differs from it. Every v3 count below is from the loader
javadoc's verified dump (`flowabl-live-dump-20231106`, 23 collections).

### A.1 Collection map

| v3 collection | v5 collection | Unit | Note |
|---|---|---|---|
| `task_templates` (embedded `revisions[]`) | `tasks` + `task_revisions` | `_0006` | The doc's flattened one-document-per-version `task_templates` was the v4 beta shape (ADR003 option 2); v5 ships the subset pattern (A.3). |
| `workflows` | `workflows` (reshaped in place) | `_0009` | |
| `workflows_revisions` | `workflow_revisions` | `_0009` | |
| `workflows` where `scope=template` | `workflow_templates` | `_0010` | Extracted, then the source workflow and revisions deleted. Templates are being demoted to static content (`api-contract-trace.md` §2d). |
| `workflows_activity` | `workflow_runs` | `_0011` | All 18 093 runs on the verified dump are migrated — the doc's "not fully migrating Workflow Activity" applies to the task level only. |
| `workflows_activity_task` | — (dropped) | `_0004` | Task-level activity is discarded by design; `task_runs` has no v3 source. |
| `workflows_activity_approval` | `actions` | `_0011` | |
| `workflows_schedules` | `workflow_schedules` | `_0011` | Omitted by the doc. Also reads v3's typo'd `cronSchedlue` key. |
| `teams` | `workspaces` | `_0007` (reshape) + `_0016` (rename) | DD-01. Embedded `approverGroups[]` extracted to `approver_groups`. |
| `users` | `users` + one `type=personal` workspace per user | `_0008` | Ruling M-1; `flowTeams[]` becomes `memberOf` edges in `_0012`. |
| `settings`, `global_config` | `settings`, `parameters` | `_0005` | Omitted by the doc. Legacy 4045 read `global_params`/`values` and matched nothing on real data; v5 reads `global_config`/`value`. |
| `tasks_locks` | `task_locks` (built fresh, nothing carried) | `_0004` drops | The doc says `locks`; v5 is `task_locks` (`service-core/src/main/java/io/boomerang/engine/entity/TaskLockEntity.java:17`), a per-key document whose acquire is a CAS on `expiresAt`. |
| `tokens` | — (dropped; operators re-issue) | `_0004` | v5 `TokenEntity` is a different shape; v4 never had a migration path either. |
| `jobs`, `triggers`, `calendars`, `paused_trigger_groups`, `locks`, `schedulers` | — (dropped) | `_0004` | The Quartz job store. |
| — | `rel_nodes` + `rel_edges` | `_0012` | The doc's `relationships{id,creationDate,relationship,fromType,fromRef,toType}` was the v4 Model 1 shape; it and `relationships_v1` are dropped by `_0014`. Lineage in `gap-register.md` §G. |
| — | `audit` | `_0013` | v5-only; one record per workspace and per workflow so `InsightsService` can resolve deleted objects. |

### A.2 Field maps (compact — rows the loader proves wrong are corrected, not annotated)

**Task catalogue** (`task_templates` → `tasks` + `task_revisions`, `_0006`):

| v3 | v5 | Change |
|---|---|---|
| `_id` | `tasks._id` | preserved verbatim |
| `name` (display) | `tasks.name` (slug) + `task_revisions.displayName` | `trim().toLowerCase().replace(' ', '-')` |
| `nodetype` | `tasks.type` | `templateTask→template`, `customTask→custom`, native types pass through; `sleep` forced to `type=sleep` (legacy 4010) |
| `status`, `verified` | `tasks.status`, `tasks.verified` | passthrough |
| `createdDate` | `tasks.creationDate` | rename |
| — | `tasks.labels` `{}`, `tasks.annotations` `{generation:"3", kind:"Task"}` | added |
| `description`, `category`, `icon` | `task_revisions.*` | task-level in v3, revision-level in v5 (repeated per revision) |
| `revisions[].version` | `task_revisions.version` | stays on the revision (doc: "moved to TaskTemplate") |
| `revisions[].changelog` | `task_revisions.changelog{author,reason,date}` | `userId→author`; `userName` dropped (PII); stays on the revision |
| `revisions[].config` + params | `task_revisions.spec.params[]` | merged (legacy 4043) — there is no separate `config` field |
| `revisions[].{arguments,command,envs,image,results,script,workingDir}` | `task_revisions.spec.*` | passthrough |
| `lastModified`, `enableLifecycle`, `scope`, `flowTeamId` | — | dropped; workspace scoping is the `TEAMTASK` relationship node |

**Workflows** (`workflows`, `_0009`):

| v3 | v5 | Change |
|---|---|---|
| `_id` | `_id` | preserved |
| `name` | `displayName` + slug `name` | legacy 4047 |
| `description` / `shortDescription` | `description` | `shortDescription` folded in when `description` is empty (4021), then dropped |
| `status`, `icon` | same | passthrough |
| `labels[]` `{key,value}` | `labels` `Map<String,String>` | |
| — | `annotations` `{generation:"3", kind:"Workflow"}`, `creationDate` (= v1 revision `changelog.date`) | added |
| `triggers.{manual,scheduler,webhook}.enable` | `triggers.{manual,schedule,webhook,event}.{enabled,conditions[]}` | 4026; `dockerhub`/`slack`/`custom` dropped; `github` left absent |
| `storage{activity,workflow}` | `workflow_revisions.workspaces[]` | written on the **revision**, only when `enabled:true`; `activity→name/type "workflowrun"`, `workflow→"workflow"` |
| `properties` | `workflow_revisions.params[]` | UI config merged into params (4042) — no `config` field |
| `scope`, `flowTeamId`, `ownerUserId`, `tokens` | — | ownership becomes the `workspace --hasWorkflow--> workflow` edge (`_0012`); `scope=user` attaches to that user's personal workspace |

**Workflow revisions** (`workflows_revisions` → `workflow_revisions`, `_0009`): `workFlowId→workflowRef`; `version` `Long→Integer`; `changelog.{userId,userName,reason,date}→changelog.{author,reason,date}`; `dag[]→tasks[]`; `markdown` passthrough; `params[]`/`workspaces[]` come from the owning workflow; `timeout`/`retries` have no v3 source.

**Revision tasks** (`dag[]` → `tasks[]`):

| v3 | v5 | Change |
|---|---|---|
| `taskId` | — | dropped; `name` is the identity |
| `label` | `name` | `start`/`end` hardcoded |
| `type` | `type` | `customtask→custom`; the rest already match `TaskType` |
| `templateId`, `templateVersion` | `taskRef`, `taskVersion` | `taskRef` is the task **id** (`tasks._id` is preserved, so no name hop); legacy 4005/4034 never actually wrote the version — the v5 unit does |
| `properties[]` | `params[]` `{name,value}` | `workflowId→workflowRef` on `runworkflow`/`runscheduledworkflow` tasks (4048) |
| `dependencies[].{taskId,switchCondition,executionCondition}` | `dependencies[].{taskRef (a task name),decisionCondition,executionCondition}` | `conditionalExecution`/`additionalProperties` dropped |
| `metadata.position` | `annotations["boomerang.io/position"]` | |
| `dependencies[].metadata` | left on the document | accepted passthrough cruft — no `boomerang.io/points` annotation exists |
| `config{type,inputs,nodeId,taskId,taskVersion,outputs}` | — | dropped wholesale — no `boomerang.io/nodeId` annotation exists |

**Workflow runs** (`workflows_activity` → `workflow_runs`, `_0011`):

| v3 | v5 | Change |
|---|---|---|
| `workflowId`, `workflowRevisionid`, `workflowRevisionVersion` | `workflowRef`, `workflowRevisionRef`, `workflowVersion` | |
| `status` | `status` | `inProgress→running`, `completed→succeeded`, `failure→failed`; `statusOverride` mapped the same way |
| — | `phase` | always `finalized` — every v3 run is finished |
| `initiatedByUserId` / `initiatedByUserName` | `initiatedByRef` | legacy 4002 computed this and never wrote it |
| `statusMessage` / `error.message` | `statusMessage` | first present wins (the doc's `error ???` row) |
| `trigger` | `trigger` | `scheduler→schedule` |
| `creationDate` | `creationDate`, `startTime` | `startTime = creationDate` (v3 never carried its own) |
| `properties[]`, `outputProperties[]` | `params[]`, `results[]` | |
| `labels[]` | `labels` map; `annotations` `{generation:"3", kind:"WorkflowRun"}` | |
| `teamId`, `userId`, `scope` | — | become the `workspace --hasWorkflowRun--> workflowrun` edge (`_0012`) |
| `switchValue`, `workspaces` | — | dropped / left unset (the doc's `???`) |

**Actions** (`workflows_activity_approval` → `actions`, `_0011`): `workflowId/activityId/taskActivityId → workflowRef/workflowRunRef/taskRunRef`; `type` `task→manual`; `actioners[].actionDate→date`; `teamId` dropped; `approverGroupId` is NOT carried — `ActionEntity.approverGroupRef` is left unset.

**Workspaces** (`teams` → `workspaces`, `_0007` + `_0016`):

| v3 | v5 | Change |
|---|---|---|
| `name` | `displayName` + slug `name` | the doc lacked `displayName` |
| `isActive` | `status` `active`/`inactive` | |
| `higherLevelGroupId` | `externalRef` | |
| — | `type` (`hobby` for real teams, `personal` per user, `system` seeded) | the doc lacked `type` |
| — | `creationDate` (= migration time), `annotations` `{generation:"3"}` | added |
| `labels[]` | `labels` map | |
| `settings.properties[]` | `parameters[]` (`key→name`) | |
| `settings` | — | `WorkspaceEntity.settings` is commented out |
| `approverGroups[]` | `approver_groups{name,creationDate,approvers[]}` | the field is `approvers`, not the doc's `approverRefs`; legacy 4011 lost this data outright |
| `quotas.{maxWorkflowExecutionMonthly,maxWorkflowExecutionTime,maxConcurrentWorkflows}` | `quotas.{maxWorkflowRunMonthly,maxWorkflowRunDuration,maxConcurrentRuns}` + new `maxWorkflowRunStorage` | the doc said "no change" |

**Users** (`_0008`): `_id/email/name/type/status` passthrough; `firstLoginDate→creationDate`; `labels[]→map`; `isFirstVisit`/`hasConsented→settings.*`; `quotas` dropped (defaults land on the personal workspace); `flowTeams[]` becomes `user --memberOf--> workspace` edges.

**Enums.** `RunStatus` v3→v5 as the doc listed (`inProgress→running`, `completed→succeeded`, `failure→failed`, `ready` new) **plus `timedout`** (`lib-common/src/main/java/io/boomerang/common/enums/RunStatus.java:16`). `TeamStatus{active,inactive}` is `WorkspaceStatus` (DD-01).

### A.3 Versioning pattern — ADR003 (proposed 2023-08-14, implemented)

v3 mixed strategies: `task_templates` embedded `revisions[]` (one-to-few); workflows used a
separate revisions collection. ADR003 weighed three options:

| Option | Shape | Verdict |
|---|---|---|
| 1. Single embedded document | parent with `revisions[]` | Rejected — 10+ task versions and 25+ workflow versions made documents large and un-queryable through the v4 query APIs, and the stored entity leaked into the user-facing model. |
| 2. Document per version | every version repeats the common fields | The v4 beta shape (what `DataMigration.md`'s `task_templates` table describes). Rejected — "which version carries the current status" has no single home. |
| 3. Subset pattern | parent = stable fields; child = versioned fields; join on read | **Proposed, and what v5 ships.** |

Code: `TaskEntity` → `tasks` (`lib-common/src/main/java/io/boomerang/common/entity/TaskEntity.java:22`)
+ `TaskRevisionEntity{parentRef, displayName, description, category, icon, version, changelog, spec}`
→ `task_revisions` (`TaskRevisionEntity.java:23-34`); `WorkflowEntity` → `workflows`
(`WorkflowEntity.java:19`) + `WorkflowRevisionEntity{version, workflowRef, tasks, changelog,
markdown, params, workspaces, timeout, retries}` → `workflow_revisions`
(`WorkflowRevisionEntity.java:24-38`). The join is done in the domain services (`TaskService`
header, `service-core/src/main/java/io/boomerang/workflow/TaskService.java:58-60`), and the
`(parent, version)` indexes are loader-managed — `_0033__DefinitionIndexes` (Part B §6 anomaly 1).
ADR003's open question (whether MongoDB rewrites a document on same-size updates) was never
measured and does not matter under the subset pattern: a new version is always a new child insert.

### A.4 Drift — where the 2022 doc and the loader disagree (the code wins)

1. Locks: doc `tasks_locks→locks`; code drops `tasks_locks` and v5 uses `task_locks` with a different, CAS-on-`expiresAt` shape.
2. Relationships: doc `relationships{fromType,fromRef,toType,relationship}`; code `rel_nodes`/`rel_edges`, and `_0014` drops `relationships`/`relationships_v1`.
3. Task template shape: doc = one document per version carrying `config`; code = `tasks` + `task_revisions`, `config` merged into `spec.params`, `version`/`changelog` on the revision.
4. `templateRef` (a name) → `taskRef` (the task id); `templateVersion` → `taskVersion`.
5. `boomerang.io/points` and `boomerang.io/nodeId` annotations never existed; dependency `metadata` is left in place.
6. `approverRefs` → `approvers`; `approverGroupId` is not carried onto `Action`.
7. Workspace quota keys renamed; `type` and `displayName` added; `shortDescription` and `tokens` gone from workflows; triggers reshaped (doc: "no change for now").
8. Workflow activity is fully migrated at the workflow level (doc: "not fully migrating").
9. Collections the doc omitted entirely: `settings`, `global_config`, `workflows_schedules`, `users`, `tokens`, the Quartz store.

## Part B — v4 (`main`) → v5 (`feat-v5`) (Evolving)

### 1. Headline

Of 22 entities: **12 byte-identical** (module move only), **3 pure renames**, **4 gained
fields**, **3 new**, **3 removed**. `Workflow`, `WorkflowRevision`, `WorkflowTemplate`, `Task`,
`TaskRevision`, `Action` are unchanged.

### 2. Fields added to existing entities

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
**Maintainer-confirmed 2026-08-19** — the removal was made outside its brief, reviewed after the
fact, and accepted. DD-06's `agentRef`→`dispatcherRef` rename therefore lands as "the claim owner is
`claim.by`", with no separate dispatcher field on either run entity.

Enum additions: `WorkflowStatus.deleted` (tombstone delete — ruled "no `tombstonedAt`"),
`TriggerEnum.retry` (DD-08 lineage via `initiatedByRef`), `PermissionScope{global,workspace}`,
`TokenActorKind`, `InboxStatus`, `OutboxStatus`.

### 3. Renames / type changes (no new data)

| Change | Ruling | Migration |
|---|---|---|
| `TeamEntity`→`WorkspaceEntity`, `teams`→`workspaces`, `TeamType/Status`→`WorkspaceType/Status` | DD-01 | `_0016` |
| `AgentEntity`→`DispatcherEntity`, `agents`→`dispatchers` | DD-06 | `_0015` |
| `TokenEntity.type` values `session/user/team/workflow/global` → `session/user/key/global` | T6-3 | `_0028` deletes retired `team`/`workspace`/`workflow` rows |
| `RoleEntity.type` `AuthScope`→`PermissionScope`; values `team`→`workspace` | T6-3 | `_0016` |
| `RelationshipType.TEAM`→`WORKSPACE`, `AuditScope.TEAM`→`WORKSPACE` | DD-01 | `_0016` (`_0013` now seeds `WORKSPACE` directly) |
| Public models `TaskRun`, `WorkflowSchedule`, `WorkflowTemplate` no longer `extends *Entity` | so `claim`/`timeoutAt`/… cannot leak into API responses | — |

### 4. New / removed entities

| Entity | Collection | Why |
|---|---|---|
| **new** `TaskLockEntity{_id=key,holder,workflowRunRef,acquiredAt,expiresAt}` | `task_locks` | E4-F replaces the alturkovic lock behind `acquirelock`/`releaselock` |
| **new** `EventOutboxEntity{refType,ref,from,to,occurredAt,routing,status,attempts,retry,sentAt}` | `events_outbox` | E4-D transactional outbox replaces the CloudEvent aspect interceptors |
| **new** `EventInboxEntity{_id=runId:eventId,topic,requestedStatus,status,receivedAt,processedAt}` | `events_inbox` | E4-D inbound event dedup |
| **removed** `EventQueueEntity` | `event_queue` | superseded by the outbox; collection dropped by `_0031` |
| **removed** engine `AuditEntity` copy | — | E8 fold; `service-core/.../core/audit/AuditEntity` is the single live audit entity |

### 5. Migration-written elements with no entity counterpart — all cleaned up by `_0031`

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

### 6. Anomalies and dispositions (2026-08-18)

| # | Finding | Disposition |
|---|---|---|
| 1 | Entity `@Indexed`/`@CompoundIndex` inert; no loader unit created `workflows.name`, `workflow_revisions{workflowRef,version}`, `tasks.name`, `workflow_templates{name,version}`, `workflow_schedules.nextFireAt` | **Fixed** — `_0033__DefinitionIndexes` (+ `task_revisions{parentRef,version}`, `workflow_schedules{status,nextFireAt}` sweep, `{workflowRef}`); `MigrationUtils.ensureIndexKeys` keeps a v4 auto-built index with the same keys. `task_runs{status,phase}` deliberately not created — no query needs it without `type`/`workflowRunRef` in front. `_0019` javadoc corrected. |
| 2 | `claim.leaseExpiresAt` declared + `lease_sweep` indexed, never written | **Left** — AM-3 (leases deferred), the field is pre-provisioned for worker leases; the only inert index. |
| 3 | `EventOutboxEntity._id` is a generated ObjectId, not the spec's `<refType>:<ref>:<seq>`; no `transitionSeq` on the runs | **Deferred — maintainer-ruled 2026-08-21.** This is the outbox creation-loss window (§7). Not built: consistent with the "do not build ahead of proven need" precedent already applied to the retry classes and to leases (AM-3). The design stands if the window is ever observed: `transitionSeq: Long` on `WorkflowRunEntity`/`TaskRunEntity`, `.inc("transitionSeq",1)` inside `findAndModifyPreImage` (all 15 transition CAS sites route through it), `TaskRunTransition`/`WorkflowRunTransition` carry `seq = pre+1`, `CloudEventsBridge` sets `_id` and swallows `DuplicateKeyException`. |
| 4 | `WorkflowRunService.retry()` clone inherited `claim`, `timeoutAt`, `isAwaitingApproval`, `statusOverride`, `results` | **Fixed** — cleared on the clone (`pauseRequestedAt` untouched; being removed). |
| 5 | `waitUntil` written via whole-entity `save()` (last-write-wins over `claim`/`retry`/`timeoutAt`) | **Fixed** — `TaskRunService.tryPark(id, waitUntil)` targeted update fenced on `phase=running`; the two `TaskExecutionService` park sites (`createSleepTask`, `acquireTaskLock`) call it. No transition event is published for the park (pre-existing behaviour). |
| 6 | `event_queue` never dropped; hand-off fields never unset | **Fixed** — `_0031`. |
| 7 | Settings key `teams`/"Team Quotas" not renamed by DD-01; `TEAMS_SETTINGS_KEY` duplicated | **Fixed** — key `workspaces`/"Workspace Quotas" (`_0032`, seed, single `WorkspaceService.WORKSPACES_SETTINGS_KEY`). The `features` flags `teamQuotas`/`teamParameters`/`teamManagement`/`teamTasks` are NOT renamed — the webapp reads `feature["team.quotas"]`; that is a coordinated frontend change. |
| 8 | Stale change-unit numbers in javadoc | **Fixed** (`RelationshipType`, `_0012`, `_0019`). |
| 9 | `retryCount` lacked `@JsonIgnore` | **Fixed**. |

Also noted, not actioned: `WorkflowScheduleEntity.schedulerRef` (dead JobRunr id, kept to avoid a
migration); `TaskLockEntity.workflowRunRef/acquiredAt` and `EventInboxEntity.topic/requestedStatus/processedAt`
are write-only diagnostics; `EventOutboxEntity.retry.count` never written (the counter is `attempts`).

### 7. Known limitation — the outbox creation-loss window (Stable)

**Accepted, deliberately not fixed (maintainer-ruled 2026-08-21).** Documented here so it is a known
property of the system rather than a surprise.

**What happens.** A transition commits via a single-document `findAndModify` CAS. Only the CAS winner
then calls `publish(...)`, and a synchronous `@EventListener` on `CloudEventsBridge` inserts the
`events_outbox` row. **No transaction spans those two steps** — verified: there is no `@Transactional`,
no `@TransactionalEventListener`, and no `MongoTransactionManager` anywhere in `service-core`. If the
process dies after the CAS commits and before the insert lands, that event is lost permanently.

**Scope of the weakness — creation, not delivery.** Once a row exists, delivery is sound:
`OutboxDispatcher` drains `status=pending` under a status CAS with bounded retry and a `dead` terminal
state, so rows that exist are delivered at-least-once. The gap is strictly that a row may never be
created.

**Blast radius.** Outbound CloudEvents only (`flow.events.sink`, off by default). The engine does not
read the outbox to make decisions — the DAG advance is level-triggered and the `WorkflowWatcher`
sweeps re-drive state from the runs themselves — so a lost row cannot stall or corrupt an execution.
It costs an external observer one status notification.

**Why not fixed.** The window is narrow (microseconds between two adjacent statements), the
consequence is an external notification rather than execution state, and no instance has been observed
hitting it. Building `transitionSeq` ahead of that evidence repeats the mistake the retry-class design
already made — see §6 anomaly 3 for the design to build if it is ever observed.

**What would change the ruling.** Any report of a missing terminal-status CloudEvent, or a consumer
being made load-bearing on outbox delivery (e.g. billing, audit-of-record, an external scheduler).
Both make the window a correctness issue rather than an observability one.

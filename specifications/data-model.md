# Data Model

Boomerang Flow stores everything in one MongoDB database: 24 application collections owned by `lib-common` and
the feature packages of `service-core`, plus 3 loader-owned bookkeeping collections. Definitions use the subset
pattern, runs carry typed control fields, and every index and schema change is applied by `service-loader`.

## Collection naming
Every collection is named `<prefix>_<name>`; the prefix comes from `flow.mongo.collection.prefix` (default
`flow`, `service-core/src/main/resources/application.properties:58`). Each entity declares
`@Document(collection = "#{@mongoConfiguration.fullCollectionName('<name>')}")`, resolved by
`service-core/src/main/java/io/boomerang/core/config/MongoConfiguration.java:16-27` (a blank prefix gives the
bare name); the loader applies the same rule (`service-loader/src/main/java/io/boomerang/loader/CollectionNames.java:13-16`),
so both MUST get the same prefix. Map keys containing `.` are stored with `#` (`MongoConfiguration.java:38`),
so the annotation `boomerang.io/status` is on disk as `boomerang#io/status`.

## Collections
| Collection | Holds | Entity (owner package) |
| --- | --- | --- |
| `workflows` + `workflow_revisions` | Workflow parent (name, status, triggers, labels, annotations) + one document per version (tasks, params, timeout, retries) | `WorkflowEntity`, `WorkflowRevisionEntity` (`lib-common`; used by `workflow`) |
| `workflow_templates` | Starter workflow templates, `version` on the single document | `WorkflowTemplateEntity` (`lib-common`; `workflow`) |
| `tasks` + `task_revisions` | Task parent (name, type, status, verified, labels, annotations) + one document per version (`parentRef`, display fields, `version`, `spec`) | `TaskEntity`, `TaskRevisionEntity` (`lib-common`; `workflow`) |
| `workflow_runs` | Execution record of one workflow run | `WorkflowRunEntity` (`lib-common`; `engine`) |
| `task_runs` | Execution record of one task in a run; the claim queue | `TaskRunEntity` (`lib-common`; `engine`) |
| `workflow_schedules` | Cron and one-off schedules: `nextFireAt`, `lastFiredAt`, `retryCount` | `WorkflowScheduleEntity` (`lib-common`; `schedule`) |
| `actions` | Manual approvals and task actions awaiting a person | `ActionEntity` (`lib-common`; `workflow`) |
| `task_locks` | Per-key lock documents for the `acquirelock` / `releaselock` tasks | `TaskLockEntity` (`engine`) |
| `events_outbox`, `events_inbox` | Outbound CloudEvents awaiting delivery (transactional outbox); inbound event receipts with processing status | `EventOutboxEntity`, `EventInboxEntity` (`event`) |
| `dispatchers` | Registered dispatcher workers and the task types they serve | `DispatcherEntity` (`dispatcher`) |
| `workspaces` | Workspaces (personal, team, system): settings, quotas, parameters | `WorkspaceEntity` (`workspace`) |
| `approver_groups` | Named approver sets used by approval tasks | `ApproverGroupEntity` (`workspace`) |
| `users`, `tokens`, `roles` | User accounts; hashed bearer tokens with principal, permissions, expiry; the five permission roles | `UserEntity`, `TokenEntity`, `RoleEntity` (`core`) |
| `settings` | Instance settings grouped by `key`, each with a `config` list | `SettingEntity` (`core`) |
| `audit` | One flat event per audited attempt: CloudEvents-style envelope (`type`, `source`, `time`, `subject`), actor (`actorId`/`actorName`/`actorType`), `workspaceId`, `action`, resource (`resourceType`/`resourceId`/`resourceName`), `outcome`, `level`, `payload`; expires by TTL | `AuditEventEntity` (`core.audit`) |
| `rel_nodes`, `rel_edges` | The relationship graph (schema below) | `RelationshipNodeEntity`, `RelationshipEdgeEntity` (`core`) |
| `parameters` | Global parameters | `GlobalParamEntity` (`workflow`) |
| `integrations`, `integration_templates` | Installed integrations and their catalogue | `IntegrationsEntity`, `IntegrationTemplateEntity` (`integrations`) |
| `sys_changelog_loader`, `sys_lock_loader`, `sys_migration_state` | Flamingock audit log and lock; the recorded install generation | loader only (`LoaderApplication.java:55-56`, `LegacyGenerationMarker.java:28`) |

## Versioned definitions: the subset pattern
Workflows and tasks are a parent document (fields with limited change scope) plus one child per version, joined
on read by the domain services (`service-core/src/main/java/io/boomerang/workflow/TaskService.java:57-60`). The child
points at its parent: `WorkflowRevisionEntity.workflowRef` + `version` (`lib-common/src/main/java/io/boomerang/common/entity/WorkflowRevisionEntity.java:30-31`),
`TaskRevisionEntity.parentRef` + `version` (`TaskRevisionEntity.java:27,32`). A new version is a new child insert.
`workflow_templates` keeps `version` on the one document (`WorkflowTemplateEntity.java:31`).

## Runs: control state, labels, annotations
Anything the engine reads to decide, or queries on, MUST be a typed field; labels and annotations MUST NOT carry control state.
| Kind | Fields | Where |
| --- | --- | --- |
| Control state (typed, `@JsonIgnore`, never on the public model) | `claim{by, at, leaseExpiresAt, seq}`, `timeoutAt`, `retry{after, count}` (task), `waitUntil` (task), `pauseRequestedAt`, `retryCount` (workflow) | `WorkflowRunEntity.java:53-77`, `TaskRunEntity.java:65-81`, `RunClaim.java:19-22`, `RunRetry.java:17-18` |
| Lineage (typed, serialised) | `trigger`, `initiatedByRef` (a retried run points at the run it retries) | `WorkflowRunEntity.java:72-73` |
| Status (typed, serialised) | `status` (closed `RunStatus` enum), `phase`, `statusMessage`, `statusReason` (task runs only; a closed string set of causes such as `OOMKilled`, `DeadlineExceeded`, `LeaseExpired`), `statusOverride` | `WorkflowRunEntity.java:41-44`, `TaskRunEntity.java:54` |
| User labels | `labels: Map<String,String>`, keyed `<prefix>/<name>`; queryable on every v2 list endpoint | `WorkflowRunEntity.java:33`, `TaskRunEntity.java:40` |
| Annotations | `annotations: Map<String,Object>` in the `boomerang.io/*` namespace | `WorkflowRunEntity.java:34`, `TaskRunEntity.java:41` |

`claim.leaseExpiresAt` is written by the dispatcher heartbeat (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java`, `renewLeases`) and unset on requeue (`:583`);
crash recovery keys on a lapsed lease, dispatcher staleness and `timeoutAt`. Two tests pin the split: `PublicRunModelSerialisationTest` (no control field serialises,
`service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:45-51`) and `ControlStateFieldsTest`
(`retry-of`, `retry-count`, `timeout-cause` are never written as annotations).

`boomerang.io/*` keys written to stored documents (`grep -rn '"boomerang.io/' service-core/src/main`):
| Key | Written on | By | Read by the engine? |
| --- | --- | --- | --- |
| `generation`, `kind` | workflows, tasks, workflow runs, task runs | `WorkflowService.java:1488,1676,1789`, `TaskService.java:646,713`, `DAGUtility.java:152-153` | No |
| `position` | each task in a workflow revision (canvas coordinates) | `WorkflowService.java:1136` | No |
| `workspace-name` | workflow run at submit; copied to task runs | `WorkflowService.java:648`, `DAGUtility.java:157-160` | Yes — `TaskExecutionService.java:760` |
| `task-timeout`, `task-default-image`, `task-deletion` | workflow run at submit (workspace executor settings) | `WorkflowService.java:631-637` | Yes — `DAGUtility.java:198-247` |
| `global-params`, `context-params`, `workspace-params` | workflow run at submit; stripped from read payloads | `WorkflowService.java:642-644`, `WorkflowRunService.java:977-979` | Yes — `ParameterManager.java:179-192` |
| `status` | task run, by the inbound-event handler (escaped key) | `TaskRunService.java:357-370` | Yes — `TaskExecutionService.java:931` |

Not stored: `icon`, `params`, `category`, `displayName`, `version`, `verified` exist only in Tekton YAML exports
(`workflow/tekton/TektonConverter.java:46-51`); `product`, `tier`, `*-ref`, `workspace-type`, `selector` are Kubernetes
labels set by the dispatcher (`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:231-306`).

## Relationship graph schema
`rel_nodes` and `rel_edges` are plain documents; the authorization reference describes the walk over them.
| Collection | Document | Notes |
| --- | --- | --- |
| `rel_nodes` | `{_id, creationDate, type, ref, slug, data: Map<String,String>}` | `_id` is `<type>:<ref>` (`service-core/src/main/java/io/boomerang/core/entity/RelationshipNodeEntity.java:38`). `type` is a `RelationshipType` label: `root`, `workspace`, `user`, `workflow`, `workflowrun`, `approvergroup`, `integration`, `schedule`, `teamtask`, `task`. |
| `rel_edges` | `{_id, creationDate, from, label, to, data: Map<String,String>}` | `from`/`to` are node ids; `label` is a `RelationshipLabel`: `contains`, `ownerOf`, `memberOf`, `hasIntegration`, `hasWorkflow`, `hasWorkflowRun`, `hasTask`, `hasTaskRun`, `hasApproverGroup` (`RelationshipEdgeEntity.java:26-31`). |

The `root:root` node and the `system` workspace are seeded by the loader (`_0002`, `_0003`).

## Indexes
Indexes exist only because a loader change unit created them (`MigrationUtils.ensureIndex`, by name,
`service-loader/src/main/java/io/boomerang/loader/migration/MigrationUtils.java:30-33`). `spring.data.mongodb.auto-index-creation=false`
(`application.properties:56`) makes every entity `@Indexed` / `@CompoundIndex` inert; they have drifted and MUST NOT be read as the inventory.

| Change unit | Collections | Indexes |
| --- | --- | --- |
| `_0017__RunIndexes` | `task_runs`, `workflow_runs`, `workflows` | first-in-first-out claim page, `timeout_sweep`, `wait_sweep`, `paused_lookup`, unique `node_uniqueness` on `(workflowRunRef, name)` after a dedupe pass (`_0017__RunIndexes.java:109-123`) |
| `_0018__EventAndLockIndexes` | `events_outbox`, `events_inbox`, `task_locks` | delivery lookup, `sent_ttl` / `received_ttl` expiry, `lease_ttl` |
| `_0019__DomainIndexes` | `actions`, `dispatchers`, `users`, `workflows`, `workflow_revisions`, `tasks`, `task_runs`, `workflow_runs` | action uniqueness per task run, dispatcher registration, `email_unique`, creation-date sorts, `label_wildcard` |
| `_0026__TokenIndexes` | `tokens` | `token_hash_lookup` on the stored hash |
| `_0030__WorkspaceSearchIndexes` | `workspaces` | `name_lookup`, `display_name_lookup` |
| `_0033__DefinitionIndexes` | `workflows`, `workflow_revisions`, `workflow_templates`, `tasks`, `task_revisions`, `workflow_schedules` | `name_lookup`, `(parent, version)` lookups, `fire_sweep` |
| `_0036__RelationshipAndAuditIndexes` | `rel_nodes`, `rel_edges`, `audit`, `users` | `type_ref`, `type_slug`, `from_label`, `to_label`, `email_lookup` (its audit scope lookups are dropped again by `_0042`) |
| `_0042__AuditEventRestructure` | `audit` | `createdAt_ttl` (365-day TTL; `audit.retentionDays` applied at startup, floored at 60), `time_desc`, `workspace_time`, `actor_time`, `resource_time` |
| `_0037__SweepIndexes` | `task_runs`, `workflow_runs`, `actions` | `status_sweep`, `claimed_sweep` for the watcher and dispatcher polls |

## Migrations
`service-loader` runs every pending change unit on Flamingock and exits non-zero on failure, so a deployment runs
it once as a pre-deploy job before `service-core` starts (`LoaderApplication.java:15-20,44-63`; `docker-compose.yml:124-125`
gates on `service_completed_successfully`). Units live in `service-loader/src/main/java/io/boomerang/loader/migration/`,
run in numeric order and are idempotent. `_0001` detects the install generation (v3 / v4 / fresh) from the legacy
Mongock changelog (`InstallGeneration.java:31-54`) and records it once in `sys_migration_state` (`LegacyGenerationMarker.java:28-41`);
v3-only and v4-only units read that marker. The chain is the in-place upgrade path from v3: it MUST NOT be collapsed
into a fresh baseline, and schema changes MUST be appended as new units. `V3DumpMigrationTest` runs the whole chain
against a real v3 dump (`service-loader/src/test/java/io/boomerang/loader/V3DumpMigrationTest.java:39-40`).

| Unit | Gate | Does |
| --- | --- | --- |
| `_0001__BaselineAndGenerationDetect` | all | Detects v3 / v4 / fresh from `sys_changelog_flow`; records it in `sys_migration_state` |
| `_0002__SeedRelationshipRoot` | all | Seeds the `root:root` graph node |
| `_0003__SeedSystemWorkspace` | all | Seeds the `system` workspace and its graph nodes and edges |
| `_0004__V3DropDeadCollections` | v3 | Drops the Quartz store, `tokens`, `tasks_locks`, `workflows_activity_task` |
| `_0005__V3MigrateSettings` | v3 | `settings` + `global_config` → `settings` + `parameters` |
| `_0006__V3MigrateTaskCatalogue` | v3 | `task_templates` with embedded revisions → `tasks` + `task_revisions` |
| `_0007__V3MigrateWorkspaces` | v3 | Reshapes `teams` in place; extracts `approver_groups` |
| `_0008__V3MigrateUsers` | v3 | Reshapes `users`; creates one personal workspace per user |
| `_0009__V3MigrateWorkflows` | v3 | Reshapes `workflows`; `workflows_revisions` → `workflow_revisions` |
| `_0010__V3ExtractWorkflowTemplates` | v3 | Workflows with `scope=template` → `workflow_templates` |
| `_0011__V3MigrateRuns` | v3 | `workflows_activity` → `workflow_runs`; approvals → `actions`; schedules → `workflow_schedules` |
| `_0012__V3BuildRelationshipGraph` | v3 | Builds `rel_nodes` / `rel_edges` for the migrated data |
| `_0013__V3SeedAudit` | v3 | Creates one per-object `audit` record per workspace and per workflow (dropped again by `_0042`) |
| `_0014__V3DropIntermediates` | v3 | Drops the consumed v3 collections (`workflows_activity`, `relationships`, …) |
| `_0015__DispatcherRename` | all | Renames the worker-tier fields from `agent` to `dispatcher` on stored runs |
| `_0016__WorkspaceRename` | all | Renames `teams` → `workspaces` and `team` → `workspace` in graph ids, types and scopes |
| `_0017` – `_0019` | all | Index units (table above) |
| `_0020__SeedRoles` | all | Seeds the five roles |
| `_0021__SeedSettings` | all | Seeds the seven instance settings documents |
| `_0022__SeedTaskCatalogue` | all | Seeds the 87 out-of-the-box tasks and their 130 revisions |
| `_0023__SeedTemplates` | all | Seeds the starter workflow templates and the integration templates |
| `_0024__V4RepairTaskVersions` | v4 | Repairs task version numbering left inconsistent by the v4 loader |
| `_0025__V4RepairWorkflowAudit` | v4 | Creates the workflow-scope per-object `audit` records the v4 loader never wrote (dropped again by `_0042`) |
| `_0026__TokenIndexes` | all | Index unit (table above) |
| `_0027__V4DropResidualCollections` | all | Drops the leftover JobRunr collections (`<prefix>jr_*`, `<prefix>_sch_*`) |
| `_0028__TokenClassRestructure` | all | Restructures tokens by actor kind; deletes team-, workspace- and workflow-typed tokens |
| `_0029__AddGitHubOAuthSettings` | all | Adds the GitHub OAuth client settings entries |
| `_0030__WorkspaceSearchIndexes` | all | Index unit (table above) |
| `_0031__DropOrphanedFieldsAndCollections` | all | Removes migration hand-off fields (`scope`, `ownerRef`, `flowTeamRefs`, `workspaceRef`, `agentRef`, `dispatcherRef`) and `event_queue` |
| `_0032__WorkspaceQuotaSettingsKey` | all | Renames the workspace quota settings key |
| `_0033__DefinitionIndexes` | all | Index unit (table above) |
| `_0034__WorkspaceFeatureFlagSettingsKeys` | all | Renames the four feature-flag settings entries |
| `_0035__AddAuthSettings` | all | Seeds the `auth` settings document (`oidc.issuer`, `oidc.clientId`) |
| `_0036`, `_0037` | all | Index units (table above) |
| `_0038__NormaliseUserEmails` | all | Lower-cases every `users.email` so the equality index serves lookups |
| `_0039__RepointWorkerFlowImages` | all | Repoints catalogue tasks off the retired `worker-flow` image |
| `_0040__DeclareRunWorkflowParams` | all | Declares the params the `run-workflow` and `run-scheduled-workflow` catalogue tasks read |
| `_0042__AuditEventRestructure` | all | Drops the per-object `audit` records and their indexes, creates the flat-event indexes (table above), seeds the `audit` settings document |

## Not built
The engine-read `task-*`, `*-params`, `workspace-name` and `status` annotations are planned to move to typed fields; nothing enforces the `<prefix>/<name>` label convention in code.

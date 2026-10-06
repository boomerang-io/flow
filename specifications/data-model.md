# Data Model

Boomerang Flow stores everything in one MongoDB database: 25 application collections owned by `lib-common` and
the feature packages of `service-core`, plus 3 migration bookkeeping collections. Definitions use the subset
pattern, runs carry typed control fields, and every index and schema change is applied by the change units in
`service-core/src/main/java/io/boomerang/migration/`.

## Collection naming
Every collection is named `<prefix>_<name>`; the prefix comes from `flow.mongo.collection.prefix` (default
`flow`, `service-core/src/main/resources/application.properties:77`). Each entity declares
`@Document(collection = "#{@mongoConfiguration.fullCollectionName('<name>')}")`, resolved by
`service-core/src/main/java/io/boomerang/core/config/MongoConfiguration.java:16-27` (a blank prefix gives the
bare name); the change units apply the same rule through `migration/CollectionNames.java:15-32`, built from the same
property (`CollectionNames.java:26-28`). Map keys containing `.` are stored with `#` (`MongoConfiguration.java:38`),
so the annotation `boomerang.io/status` is on disk as `boomerang#io/status`.

## Collections
| Collection | Holds | Entity (owner package) |
| --- | --- | --- |
| `workflows` + `workflow_revisions` | Workflow parent (name, status, triggers, labels, annotations) + one document per version (tasks, params, timeout, retries) | `WorkflowEntity`, `WorkflowRevisionEntity` (`lib-common`; used by `workflow`) |
| `workflow_templates` | Starter workflow templates, `version` on the single document. Read-only content: written only by migrations (`_0020__SeedTemplates` seeds them, `_0011__V3ExtractWorkflowTemplates` imports a v3 database's `scope=template` workflows) and never by the API | `WorkflowTemplateEntity` (`lib-common`; `workflow`) |
| `tasks` + `task_revisions` | Task parent (name, type, status, verified, labels, annotations) + one document per version (`parentRef`, display fields, `version`, `spec`) | `TaskEntity`, `TaskRevisionEntity` (`lib-common`; `workflow`) |
| `workflow_runs` | Execution record of one workflow run | `WorkflowRunEntity` (`lib-common`; `engine`) |
| `task_runs` | Execution record of one task in a run; the claim queue | `TaskRunEntity` (`lib-common`; `engine`) |
| `artifacts` | One file an upload task attached to a run: its run, task run and workflow references, size, SHA-256, store key, status and expiry (see "Artifacts" below) | `ArtifactEntity` (`lib-common`; `workflow`) |
| `workflow_schedules` | Cron and one-off schedules: `nextFireAt`, `lastFiredAt`, `retryCount` | `WorkflowScheduleEntity` (`lib-common`; `schedule`) |
| `actions` | Manual approvals and task actions awaiting a person | `ActionEntity` (`lib-common`; `workflow`) |
| `task_locks` | Per-key lock documents for the `acquirelock` / `releaselock` tasks | `TaskLockEntity` (`engine`) |
| `events_outbox`, `events_inbox` | Outbound CloudEvents awaiting delivery (transactional outbox): `status`, `attempts`, `retry.after`, `sentAt`, and on a failure `lastError` (capped at 1024 characters) and `deadAt`; inbound event receipts with processing status | `EventOutboxEntity`, `EventInboxEntity` (`event`) |
| `dispatchers` | Registered dispatcher workers and the task types they serve | `DispatcherEntity` (`dispatcher`) |
| `workspaces` | Workspaces (personal, team, system): settings, quotas, parameters | `WorkspaceEntity` (`workspace`) |
| `approver_groups` | Named approver sets used by approval tasks | `ApproverGroupEntity` (`workspace`) |
| `users`, `tokens`, `roles` | User accounts; hashed bearer tokens with principal, permissions, expiry; the five permission roles | `UserEntity`, `TokenEntity`, `RoleEntity` (`core`) |
| `settings` | Instance settings grouped by `key`, each with a `config` list | `SettingEntity` (`core`) |
| `audit` | One flat event per audited attempt: CloudEvents-style envelope (`type`, `source`, `time`, `subject`), actor (`actorId`/`actorName`/`actorType`), `workspaceId`, `action`, resource (`resourceType`/`resourceId`/`resourceName`), `outcome`, `level`, `payload`; run lifecycle events (`workflowrun` CREATE on admission, UPDATE on completion) ride the engine's transition listener; expires by TTL | `AuditEventEntity` (`core.audit`) |
| `rel_nodes`, `rel_edges` | The relationship graph (schema below) | `RelationshipNodeEntity`, `RelationshipEdgeEntity` (`core`) |
| `parameters` | Global parameters | `GlobalParamEntity` (`workflow`) |
| `integrations`, `integration_templates` | Installed integrations and their catalogue | `IntegrationsEntity`, `IntegrationTemplateEntity` (`integrations`) |
| `sys_migration_changelog`, `sys_migration_lock`, `sys_migration_state` | Flamingock's change log and lock; the recorded install generation | migrations only (`migration/MigrationConfiguration.java:42-43`, `LegacyGenerationMarker.java:27`) |

## Versioned definitions: the subset pattern
Workflows and tasks are a parent document (fields with limited change scope) plus one child per version, joined
on read by the domain services (`service-core/src/main/java/io/boomerang/workflow/TaskService.java:57-60`). The child
points at its parent: `WorkflowRevisionEntity.workflowRef` + `version` (`lib-common/src/main/java/io/boomerang/common/entity/WorkflowRevisionEntity.java:30-31`),
`TaskRevisionEntity.parentRef` + `version` (`TaskRevisionEntity.java:27,32`). A new version is a new child insert.
`workflow_templates` keeps `version` on the one document (`WorkflowTemplateEntity.java:31`).

A revision's declared params are `AbstractParam` (`lib-common/.../model/AbstractParam.java`), which carries the
value alongside the UI metadata the canvas renders: `type` (a `ConfigType` label), `options` for `select`, and
nullable `min` / `max` / `step` for `slider`. The three range fields are absent from every param of any other
type, so no stored document changed when they were added.

## Runs: control state, labels, annotations
Anything the engine reads to decide, or queries on, MUST be a typed field; labels and annotations MUST NOT carry control state.
| Kind | Fields | Where |
| --- | --- | --- |
| Control state (typed, `@JsonIgnore`, never on the public model) | `claim{by, at, leaseExpiresAt, seq}`, `timeoutAt`, `retry{after, count}` (task), `waitUntil` (task), `pauseRequestedAt`, `retryCount` (workflow) | `WorkflowRunEntity.java:53-77`, `TaskRunEntity.java:65-81`, `RunClaim.java:19-22`, `RunRetry.java:17-18` |
| Lineage (typed, serialised) | `trigger`, `initiatedByRef` (a retried run points at the run it retries) | `WorkflowRunEntity.java:72-73` |
| For-each items (typed, serialised) | `parentRef` (the parent task run's id) and `index` (position from 0), set only on the items of a for-each task, which are named `<name>[<index>]` | `TaskRunEntity.java:68-69` |
| For-each setting (typed, `@JsonIgnore`) | `foreach{items}` on a parent task run, copied from the workflow task (`WorkflowTask.foreach`); the engine overwrites `items` with the resolved array at fan-out, and recovery re-creates missing items from it | `TaskRunEntity.java:72`, `lib-common/.../model/WorkflowTaskForeach.java` |
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

The `root:root` node and the `system` workspace are seeded by `_0003__SeedRelationshipRoot` and
`_0004__SeedSystemWorkspace`.

## Indexes
Indexes exist only because `_0021__Indexes` built them: its `INVENTORY` is the whole set, the same on an empty and an
upgraded database (`service-core/src/main/java/io/boomerang/migration/_0021__Indexes.java:50`).
`spring.data.mongodb.auto-index-creation=false` (`application.properties:75`) makes every entity `@Indexed` /
`@CompoundIndex` inert; they have drifted and MUST NOT be read as the inventory. A new query that needs an index
MUST add it to the inventory.

| Collection | Indexes |
| --- | --- |
| `task_runs` | `claim_page` (type, status, phase, creationDate); `run_tasks`; sparse `lease_sweep`, `timeout_sweep`, `wait_sweep`; `claimed_sweep`; unique `node_uniqueness` (workflowRunRef, name); sparse `parent_index` (a foreach parent's items); `label_wildcard` (labels.$**) |
| `workflow_runs` | `claim_page`; sparse `timeout_sweep`, `paused_lookup`; `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase`, `workflow_ref_creation`, `workflow_ref_status`; `initiated_by_phase` (the child runs a cascade cancel pages); `label_wildcard` |
| `workflows`, `workflow_revisions`, `workflow_templates`, `workflow_schedules` | `status_lookup`, `name_lookup`, `creation_date_sort`; `workflow_ref_version`; `name_version`; `fire_sweep`, `workflow_lookup` |
| `tasks`, `task_revisions` | `name_lookup`, `creation_date_sort`; `parent_ref_version` |
| `events_outbox`, `events_inbox`, `task_locks` | `dispatch_page`, `sent_ttl` (7 days); `received_ttl` (7 days), `redrive_page`; `lease_ttl` (at `expiresAt`) |
| `actions` | `task_run`, unique where `taskRunRef` exists (`:125-132`); `status_sweep` |
| `dispatchers`, `users`, `workspaces`, `tokens` | unique `registration` (name, host); unique `email_unique`; `name_lookup`, `display_name_lookup`; `token_hash_lookup` |
| `rel_nodes`, `rel_edges` | `type_slug`, `type_ref`; `from_label`, `to_label` |
| `audit` | `createdAt_ttl` (365 days; `audit.retentionDays` applied at startup, floored at 60), `time_desc`, `workspace_time`, `actor_time`, `resource_time` |
| `artifacts` | unique `run_name_idx` (workflowRunRef, name), `status_expiration_idx`, `workflow_status_idx` |

Before the unique indexes are built, duplicates are removed (`:182-185`): task runs keep the finished one, else the
earliest; actions keep the earliest per task run, after a null `taskRunRef` is unset; dispatchers keep the most
recently connected. Users are never deleted: a shared email is logged and fails the build. An existing index that
holds an inventory index's keys under another name, or its name with another shape, is dropped first
(`:284`); any other index, such as one an operator added, is kept.

## Artifacts

An artifact is a file an upload task attached to its run. Its file lives in an object store; its record lives in
`artifacts` and is reached only through the run: access is the run's `HAS_WORKFLOWRUN` edge to the workspace,
and the workspace list and storage total go through the workspace's workflows (`workflowRef`). No artifact
field is on `WorkflowRun`, and no quota is on the run.

| Status | Written when | File |
| --- | --- | --- |
| `uploading` | the engine hands the upload task to a dispatcher and fills its link params (`workflow/ArtifactService.java` `fillLinkParams`; `dispatcher/DispatcherService.java` `fillArtifactLink`) | may be partly written; reaped after `flow.artifacts.upload-grace-minutes` (60) |
| `available` | the dispatcher ends the upload task succeeded: service-core reads the stored file for its size and SHA-256 and checks the limits (`completeUpload`, called from `engine/TaskRunService.java` `completeArtifactUpload`); a refusal ends the task failed with `ArtifactRefused` | in the store |
| `expired` | the watcher passes `expirationDate` (`:324`) | deleted; the record stays so the run still shows what it produced |

A record is removed only by a delete or its workflow's prune (`engine/WorkflowWatcher.java:350`). Limits are
admin settings: the `artifacts` document (default retention 30 days, maximum 90, largest artifact 1024 MB) and
the `max.artifact.storage` workspace quota (5Gi), overridable per workspace with `artifactRetentionDays`. A task
may ask for a shorter retention, never a longer one (`:128`). An upload over the largest artifact or the
storage quota has its own file deleted and its task refused; no existing artifact is deleted to make room.
The store is any S3-compatible service (`workflow/S3ArtifactStore.java`), configured by `flow.artifacts.*`
and off unless `flow.artifacts.enabled` (`workflow/config/ArtifactStoreConfiguration.java:28`).

## Migrations
`service-core` runs every pending change unit on Flamingock while its context starts, before the web server,
the scheduler and the dispatcher API (`migration/MigrationConfiguration.java:21`;
`flamingock.management-mode=INITIALIZING_BEAN`, `application.properties:81`). Replicas that start together wait on
`sys_migration_lock`, then skip what another replica applied; a failed unit fails startup.

The chain migrates exactly two starting points, an empty database and an in-place v3 install, to the shape the
application reads. `_0001` refuses anything else before writing: a v4 install, or a database a 5.0 beta already
migrated (it holds `sys_changelog_loader`); a refusal is retried on every start (`_0001__GuardAndDetectGeneration.java:30,47`).
Every unit retries after a failure (`@Recovery(ALWAYS_RETRY)`), so a start interrupted mid-migration resumes on
the next one. Before upgrading a v3 database, check for two users sharing one email: `_0021` refuses to delete an
account, so a shared email fails the unique index and startup with it; two emails that differ only by case are
left as they are and logged (`_0009__NormaliseUserEmails.java:109`).
It records the generation once in `sys_migration_state` (`LegacyGenerationMarker.java:40`); v3-only units read that
marker. `V3DumpMigrationTest` runs the chain against a real v3 dump and pins what an upgraded install ends with;
`MigrationChainTest` and `SettingsFromSeedTest` cover the empty path, a v3 fixture, the refusals and the dedupes. Once
a release ships the chain, changes are appended as new units.

| Unit | Gate | Does |
| --- | --- | --- |
| `_0001__GuardAndDetectGeneration` | all | Refuses a v4 or beta database; records v3 / fresh |
| `_0002__V3PrepareCollections` | v3 | Renames `teams` to `workspaces`; drops the Quartz store, `tokens`, `tasks_locks`, `workflows_activity_task` and the unprefixed `locks` |
| `_0003__SeedRelationshipRoot` | all | Seeds the `root:root` graph node |
| `_0004__SeedSystemWorkspace` | all | Seeds the `system` workspace and its graph node and edge |
| `_0005__V3MigrateGlobalParameters` | v3 | `global_config` → `parameters` |
| `_0006__V3MigrateTaskCatalogue` | v3 | `task_templates` with embedded revisions → `tasks` + `task_revisions`; task runs' template references |
| `_0007__V3MigrateWorkspaces` | v3 | Reshapes v3 teams in `workspaces`; extracts `approver_groups` |
| `_0008__V3MigrateUsers` | v3 | Reshapes `users`; one personal workspace per user |
| `_0009__NormaliseUserEmails` | all | Lower-cases `users.email`, leaving case collisions as they are and logging them |
| `_0010__V3MigrateWorkflows` | v3 | Reshapes `workflows`; `workflows_revisions` → `workflow_revisions` |
| `_0011__V3ExtractWorkflowTemplates` | v3 | Workflows with `scope=template` → `workflow_templates` |
| `_0012__V3MigrateRuns` | v3 | `workflows_activity` → `workflow_runs`; approvals → `actions`; schedules → `workflow_schedules` |
| `_0013__V3BuildRelationshipGraph` | v3 | Builds `rel_nodes` / `rel_edges`, approver group edges included; clears the hand-off fields |
| `_0014__V3DropIntermediates` | v3 | Drops the consumed v3 collections |
| `_0015__SeedRoles` | all | Seeds the five roles |
| `_0016__BuildSettingsFromSeed` | all | Builds every settings document from `seed/settings.json`, carrying stored values (below) |
| `_0017__SeedTaskCatalogue` | all | Seeds the 88 catalogue tasks and their revisions, matched by name |
| `_0018__SeedArtifactTasks` | all | Inserts `upload-artifact` and `download-artifact`, ids assigned on insert |
| `_0019__V3UpgradeCatalogueRevisions` | v3 | Moves revisions off the retired `worker-flow` image; gives the child-run tasks every param their seeded revision declares |
| `_0020__SeedTemplates` | all | Seeds the starter workflow templates and the integration templates |
| `_0021__Indexes` | all | Dedupes, then builds the index inventory (above) |

`_0016` finds each stored settings document by id, key or earlier key and rebuilds it from the seed under the stored
id. Each entry takes the value stored under its key or an earlier key (`github.pem` → `github.jwt`, the v3
`max.team.*` quota keys, `teamQuotas` → `workspaceQuotas`), except a value stored as another type (secured values
are decrypted by type), the shipped 90-minute task ceiling, a `worker-flow` image and a choice that is no longer an
option: those take the seed value. Entries the seed does not define are kept; retired entries and the v3 User
Defaults and workflow storage documents are removed (`_0016__BuildSettingsFromSeed.java:47-89`).

## Not built
The engine-read `task-*`, `*-params`, `workspace-name` and `status` annotations are planned to move to typed fields; nothing enforces the `<prefix>/<name>` label convention in code.

# Data Model

Boomerang Flow stores everything in one MongoDB database: 26 application collections owned by `service-core`,
plus 3 migration bookkeeping collections. Definitions use the subset pattern, runs carry typed control fields,
and every index and schema change is applied by the change units in
`service-core/src/main/java/io/boomerang/migration/`. Paths below are under
`service-core/src/main/java/io/boomerang/` unless they start with a module name.

## Collection naming

Every collection is named `<prefix>_<name>`; the prefix is `flow.mongo.collection.prefix` (default `flow`,
`service-core/src/main/resources/application.properties:120`). Each entity declares
`@Document(collection = "#{@mongoConfiguration.fullCollectionName('<name>')}")`, resolved by
`core/config/MongoConfiguration.java:16-29` (a blank prefix gives the bare name); the change units apply the
same rule through `migration/CollectionNames.java:15-32`, built from the same property (`:26-28`). Map keys
containing `.` are stored with `#` (`core/config/MongoConfiguration.java:37`), so the annotation
`boomerang.io/position` is on disk as `boomerang#io/position`.

## Collections

| Collection | Holds | Entity (package) |
| --- | --- | --- |
| `workflows` + `workflow_revisions` | Workflow parent (name, status, triggers, labels, annotations, `type`: `workflow` when absent, `template` or `starter` in the system workspace only, and `templateRef` on a workflow that runs a template by reference) + one document per version (tasks, params, workspace, timeout, retries; a link's holds no tasks) | `WorkflowEntity`, `WorkflowRevisionEntity` (`common.entity`) |
| `tasks` + `task_revisions` | Task parent (name, type, status, verified, labels, annotations) + one document per version (`parentRef`, display fields, `version`, `spec`) | `TaskEntity`, `TaskRevisionEntity` (`common.entity`) |
| `workflow_runs` | The execution record of one workflow run | `WorkflowRunEntity` (`common.entity`) |
| `task_runs` | The execution record of one task in a run; the claim queue. Its `workflowRef` is its run's, so a template's task runs carry the workspace's link; `workflowRevisionRef` names the revision that ran (`engine/DAGUtility.java:266-270`) | `TaskRunEntity` (`common.entity`) |
| `artifacts` | One file an upload task attached to a run, or a task's stored log: run, task run and workflow references, scope (`run`, `workflow` or `log`), size, SHA-256, content type, store key, status and expiry ("Artifacts" below) | `ArtifactEntity` (`common.entity`) |
| `workflow_schedules` | Cron and one-off schedules: `nextFireAt`, `lastFiredAt`, `retryCount`, params | `WorkflowScheduleEntity` (`common.entity`) |
| `actions` | Manual approvals and task actions awaiting a person | `ActionEntity` (`common.entity`) |
| `task_locks` | One lock document per key for the `acquirelock` and `releaselock` tasks | `TaskLockEntity` (`engine`) |
| `events_outbox`, `events_inbox` | Outbound CloudEvents awaiting delivery (the transactional outbox): `status`, `attempts`, `retry.after`, `sentAt`, and on a failure `lastError` (at most 1024 characters, `event/OutboxDispatcher.java:46`) and `deadAt`; inbound event receipts with their processing status | `EventOutboxEntity`, `EventInboxEntity` (`event`) |
| `dispatchers` | Registered dispatchers, the task types they serve, and where the engine reads their live task logs (`logsUrl`) with which bearer (`logsToken`, stored as sent); removed after 7 days without a connection (`execution-model.md`, "The watcher sweeps") | `DispatcherEntity` (`dispatcher`) |
| `workspaces` | Workspaces (`type` `personal`, `hobby`, `pro`, `enterprise` or `system`): status, labels, annotations, parameters, quotas | `WorkspaceEntity` (`workspace`) |
| `approver_groups` | Named approver sets used by approval tasks | `ApproverGroupEntity` (`workspace`) |
| `users`, `tokens`, `roles` | User accounts, with per-user interface `settings` (`isFirstVisit`, `isShowHelp`, `hasConsented`, `isShowGetStarted`; `core/model/UserSettings.java:8-12`), which `PATCH /api/v2/profile` merges key by key; hashed bearer tokens with principal, permissions and expiry; the five permission roles | `UserEntity`, `TokenEntity`, `RoleEntity` (`core`) |
| `settings` | Instance settings grouped by `key`, each with a `config` list | `SettingEntity` (`core`) |
| `audit` | One flat event per audited attempt: a CloudEvents-style envelope (`type`, `source`, `time`, `subject`), actor (`actorId`, `actorName`, `actorType`), `workspaceId`, `action`, resource (`resourceType`, `resourceId`, `resourceName`), `outcome`, `level`, `payload` (request discriminators, and on a secret write `detail.secretsChanged` and `secretsCleared` names, never values). Run lifecycle events (`workflowrun` CREATE on admission, UPDATE on completion) come from the engine's transitions (`core/audit/WorkflowRunAuditBridge.java:59`). Expires by time to live | `AuditEventEntity` (`core.audit`) |
| `rel_nodes`, `rel_edges` | The relationship graph (below) | `RelationshipNodeEntity`, `RelationshipEdgeEntity` (`core`) |
| `parameters` | Global parameters | `GlobalParamEntity` (`workflow`) |
| `integrations`, `integration_templates` | Installed integrations, each typed as its template is (`github_app`, `slack_auth`) with `ref` the provider's id for it (a GitHub installation id, a Slack team id), and their catalogue; a template's `fields` are the workspace parameters it manages while its credentials are shared with tasks, and its `kind` whether Flow installs it and fills them (`app`: GitHub, Slack) or the workspace enters them (`form`: Google, Microsoft), which links it with no `integrations` record (`parameters.md`) | `IntegrationsEntity`, `IntegrationTemplateEntity` (`integrations`) |
| `sys_migration_changelog`, `sys_migration_lock`, `sys_migration_state` | Flamingock's change log and lock; the recorded install generation | migrations only (`migration/MigrationConfiguration.java:42-43`, `migration/LegacyGenerationMarker.java:27`) |

A workspace's quotas (`WorkspaceEntity.quotas`: `maxWorkflowCount`, `maxWorkflowRunMonthly`,
`maxWorkspaceStorage`, `maxWorkflowRunDuration`, `maxConcurrentRuns`, `maxArtifactStorage`,
`artifactRetentionDays`; `workspace/model/Quotas.java:15-23`) each have three stored states. Absent inherits
the platform default from the `quotas` or `artifacts` settings document when the workspace is read
(`workflow/QuotaService.java:108-120,189-194`); `0` is unlimited (`Quotas.limited`, `workspace/model/Quotas.java:11-13`,
the one test every check calls); a positive number is the limit. A workspace create or patch overlays the
request's quotas field by field onto what is stored (`workspace/WorkspaceService.java:224,335`), so a request
that carries one quota leaves the others as they were; `DELETE /api/v2/workspace/{workspace}/quotas` is the only
way back to absent (`workspace/WorkspaceControllerV2.java:316-327`). The v3 migration drops a v3 `0`, which v3
enforced as a limit of zero, so that workspace inherits instead (`migration/_0007__V3MigrateWorkspaces.java:234-238`).

## Versioned definitions: the subset pattern

Workflows and tasks are a parent document (fields with limited change scope) plus one child per version,
joined on read by the domain services (`workflow/WorkflowService.java:1501-1528,2552-2577`). The child points at its
parent: `WorkflowRevisionEntity.workflowRef` + `version` (`common/entity/WorkflowRevisionEntity.java:24-25`),
`TaskRevisionEntity.parentRef` + `version` (`common/entity/TaskRevisionEntity.java:27,32`). A new version is a
new child insert. A template and a starter are workflows of the system workspace, so they version the same way;
a link's own revisions hold no tasks (`workflow/WorkflowService.java:1640-1644,2555-2574`).

A revision carries the workflow's run settings beside its tasks: `timeout`, `retries` and `concurrency{limit, overflow}`
(`common/entity/WorkflowRevisionEntity.java:36-38`); a task node carries its own `timeout` and `retry{max, backoff{type, initial, max}}`
(`common/model/WorkflowTask.java:37-39`, `common/model/WorkflowTaskRetry.java:17-18`). The canvas the editor saves through carries both on
its nodes (`workflow/model/CanvasNodeData.java:27-28`).

A reference to a task is stored as the task's id: `taskRef` on a workflow revision's node
(`common/model/WorkflowTask.java:34`) and on a task run (`common/entity/TaskRunEntity.java:55`). The API and
the dispatcher protocol read and write it in the task's slug form, translated through the relationship graph
by node id and its `type_slug` and `to_label` indexes (`api-contract.md`, "Public run models"), so nothing
stored carries a name.

A revision's declared params are `AbstractParam` (`common/model/AbstractParam.java:17-45`), which carries the
value beside the metadata the canvas renders: `type` (a `ConfigType` label), `options` for `select`, and
nullable `min`, `max` and `step` for `slider`, absent from a param of any other type.

## Runs: control state, labels, annotations

Anything the engine reads to decide, or queries on, MUST be a typed field; labels and annotations MUST NOT
carry control state.

| Kind | Fields | Where |
| --- | --- | --- |
| Control state (typed, never on a public model) | `claim{by, at, leaseExpiresAt, seq}`, `timeoutAt`, `retry{after, count, failures, max, backoff}` (task: the engine's and the node's budgets, and the node's setting copied on), `waitUntil` (task), `pauseRequestedAt` (workflow; the public model shows only `paused`), `purgeRequestedAt` (workflow: a purge was requested while its tasks had not settled; the purge sweep removes it), `waitingSince` (workflow: set while every in-flight task waits, so the run clock is stopped), `graphEndedAt` (workflow: the graph ended with the final status while the finally tasks run; with the phase still `running` it marks the finally stage), `retryCount` (workflow). All but `pauseRequestedAt` are `@JsonIgnore` | `common/entity/WorkflowRunEntity.java:50-88`, `common/entity/TaskRunEntity.java:72-94`, `common/model/RunClaim.java:19-22`, `common/model/RunRetry.java:21-25` |
| Attempt history (typed, serialised) | `attempts[]` on a task run: each attempt a requeue superseded, `number`, `claimedAt`, `endedAt`, `duration`, `status`, `statusReason`, `statusMessage`, `dispatcherRef`; absent until the first requeue | `common/entity/TaskRunEntity.java:90`, `common/model/RunAttempt.java:20-27` |
| Lineage (typed, serialised) | `trigger`, `initiatedByRef` (a retry points at the run it retries, a child at its parent); `retryFrom{scope, nodes}` on a run a scoped retry created; `reusedFromRef` on a task run that retry carried over from the origin instead of executing | `common/entity/WorkflowRunEntity.java:83-92`, `common/entity/TaskRunEntity.java:69` |
| For-each items (typed, serialised) | `parentRef` (the parent task run's id) and `index` (position from 0), set only on the items of a for-each task, which are named `<name>[<index>]` | `common/entity/TaskRunEntity.java:63-64` |
| For-each setting (typed, `@JsonIgnore`) | `foreach{items}` on a parent task run, copied from the workflow task (`WorkflowTask.foreach`); the engine overwrites `items` with the resolved array at fan-out, and recovery re-creates missing items from it | `common/entity/TaskRunEntity.java:72`, `common/model/WorkflowTaskForeach.java:19` |
| Status (typed, serialised) | `status` (the closed `RunStatus` enum), `phase`, `statusMessage`, `statusReason` (a closed set of causes: on a task run `OOMKilled`, `DeadlineExceeded`, `LeaseExpired` and the rest; on a workflow run `ConcurrencyLimit`, `WorkflowInvalid`, `FinallyFailed`, `DeadlineExceeded`), `statusOverride` (workflow runs) | `common/entity/WorkflowRunEntity.java:38-44`, `common/entity/TaskRunEntity.java:45-49`, `common/model/StatusReason.java:34-36` |
| Pre-approval (typed, `@JsonIgnore`) | `preApprovedStatus` on a wait-for-event task run that an event reached before it was ready: the status it ends with once it runs | `common/entity/TaskRunEntity.java:52`, `engine/TaskRunService.java:406-414` |
| Labels | `labels: Map<String,String>`, keyed `<prefix>/<name>` and queryable on every v2 list endpoint. A task run stacks its task's, its node's and its run's labels (`engine/DAGUtility.java:286-292`). Keys under `boomerang.io/` are the server's (`common/model/ServerLabel.java:12-18`): `boomerang.io/model` on an `ai` task run (its resolved `model` param, at admission, `engine/TaskExecutionService.java:254-264`) and `boomerang.io/item` on every item task run of a for-each and on a for-each Run workflow child (its text item, `engine/TaskExecutionService.java:904-913,1370-1381`), which the child's task runs inherit; a client's keys under that prefix are dropped. The engine decides from none of them | `common/entity/WorkflowRunEntity.java:30`, `common/entity/TaskRunEntity.java:35` |
| Annotations | `annotations: Map<String,Object>`: user metadata and the `boomerang.io/*` keys below; the engine decides from none of them | `common/entity/WorkflowRunEntity.java:31`, `common/entity/TaskRunEntity.java:37` |

`claim.leaseExpiresAt` is written by the claim and renewed by the dispatcher heartbeat
(`engine/TaskRunService.java:343,662-674`), and unset on completion and requeue (`:637-644,826`), so the sparse
`lease_sweep` index holds only live claims. An abandon (a timeout included) keeps it until the completion lands
(`:733-753`), so an end lost after the abandon is reaped again. Crash recovery keys on a lapsed lease,
dispatcher staleness and `timeoutAt`. Two tests pin the split: `PublicRunModelSerialisationTest` (no control
field serialises, `service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:56-69`)
and `ControlStateFieldsTest` (`retry-of`, `retry-count` and `timeout-cause` are never written as annotations,
`service-core/src/test/java/io/boomerang/engine/ControlStateFieldsTest.java:51-76`).

The `boomerang.io/*` keys written to stored documents; the engine reads none of them:

| Key | Written on | By |
| --- | --- | --- |
| `generation`, `kind` | workflows, tasks, workflow runs | `workflow/WorkflowService.java:1633,2072,2225`, `workflow/TaskService.java:718,806` |
| `position` | each task in a workflow revision (canvas coordinates) | `workflow/WorkflowService.java:1300` |

A run annotation or label under `boomerang.io/` from a client is reserved: a submit or start that sends one has
it dropped (`workflow/WorkflowService.java:793-799`, `workflow/WorkflowRunService.java:906-913`). The engine
takes its decisions from typed sources instead:

| Decision | Source |
| --- | --- |
| The run's workspace: its parameter layer, its secret values, the task lock scope | The workspace that owns the run's workflow in the relationship graph (`workspace --hasWorkflow--> workflow`), held by id so a rename does not move it, and cached per workflow (`workflow/ParamLayerService.java:84`, `core/ParamLayerCache.java:92`) |
| A task run's per-task timeout ceiling, default image and pod deletion policy | The `task` settings, read once when the run's task runs are created at queue, onto the task run's own `timeout`, `spec.image` and `spec.deletion` (`engine/DAGUtility.java:74-87,275-277,338,357,391,420`) |
| The status an early event gave a wait-for-event task | `preApprovedStatus` on the task run (above) |

Not stored: `icon`, `params`, `category`, `displayName`, `version` and `verified` annotations exist only in
Tekton YAML exports (`workflow/tekton/TektonConverter.java:48-53`); `boomerang.io/product`, `tier` and the
`*-ref` keys are Kubernetes labels the dispatcher sets
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:208-234,349-352`).

## Secret values at rest

Secret parameter values are stored encrypted, as `crypt_v1{AESGCM|<ciphertext>}`, and read back as plaintext
(`core/config/SecretValueMongoListener.java:95-131`; `parameters.md`, "Sensitive parameters"). No field is
added; the stored value changes shape. A secret is recognised by the type stored beside its value:

| Collection | Field | Secret when |
| --- | --- | --- |
| `parameters` (global) | `value`, `defaultValue` | `type == password` |
| `workspaces` | `parameters[].value`, `parameters[].defaultValue` | `type == password` |
| `task_revisions` | `spec.params[].defaultValue` | `type == password` |
| `workflow_revisions` | `params[].defaultValue` | `type == password` |
| `workflow_revisions` | `tasks[].params[].value` | `type == secret`, stamped at save from the task's declared `password` param |
| `workflow_schedules` | `params[].value` | `type == secret`, stamped at save from the workflow's declared `password` param |
| `workflow_runs`, `task_runs` | `params[].value` | `type == secret` |
| `integrations` | `data.accessToken`, `data.refreshToken`, the same two under `data.authedUser`, and `data.incomingWebhook.url` | always: a stored Slack OAuth response, whose webhook URL posts by itself |

A task run's stored `spec` holds the references it was written with, never resolved values: the spec is filled
in on the claim payload only. An unlabelled stored value loads as it is and is encrypted on its next save; no
migration encrypts it sooner, so a Slack integration with unlabelled tokens keeps them until it is next saved,
which re-linking Slack does. Not encrypted: task `results`. A `secured` entry of a `settings` document is encrypted by `core/SettingsService.java`
with the same cipher (`:169-172`); the settings API reads it by the one secret rule, `*****` when set and
nothing when unset (`:183-189`), and engine and integration reads get plaintext. A stored value that will not
decrypt is kept as stored, on load and on the next save of its group, never written back as nothing
(`:205-210`).

## Relationship graph

`rel_nodes` and `rel_edges` are plain documents; `authorization.md` describes the walk over them.

| Collection | Document | Notes |
| --- | --- | --- |
| `rel_nodes` | `{_id, creationDate, type, ref, slug, data: Map<String,String>}` | `_id` is `<type>:<ref>` (`core/entity/RelationshipNodeEntity.java:32`). `type` is a `RelationshipType` label: `root`, `workspace`, `user`, `workflow`, `workflowrun`, `approvergroup`, `integration`, `schedule`, `workspacetask` (a workspace's own task), `task` (the global catalogue) (`core/enums/RelationshipType.java:12-23`). |
| `rel_edges` | `{_id, creationDate, from, label, to, data: Map<String,String>}` | `from` and `to` are node ids (`core/entity/RelationshipEdgeEntity.java:19-24`); `label` is a `RelationshipLabel`: `contains`, `ownerOf`, `memberOf`, `hasIntegration`, `hasWorkflow`, `hasWorkflowRun`, `hasTask`, `hasTaskRun`, `hasApproverGroup`, `linksTemplate` (`core/enums/RelationshipLabel.java:14-25`). `_id` is generated, except for a claim: a `linksTemplate` edge runs from a workspace to its link to a template, beside that link's `hasWorkflow` edge, with `_id` = `linksTemplate:<workspace node id>:<template id>`, so MongoDB admits one per workspace and template (`core/RelationshipService.java:326-380`). |

The `root:root` node and the `system` workspace are seeded by `_0003__SeedRelationshipRoot` and
`_0004__SeedSystemWorkspace`.

## Indexes

Indexes exist only because `_0021__Indexes` built them: its `INVENTORY` is the whole set, the same on an empty
and an upgraded database (`migration/_0021__Indexes.java:53-197`).
`spring.data.mongodb.auto-index-creation=false` (`service-core/src/main/resources/application.properties:118`)
makes every entity `@Indexed` and `@CompoundIndex` inert; they have drifted and MUST NOT be read as the
inventory. A new query that needs an index MUST add it to the inventory.

| Collection | Indexes |
| --- | --- |
| `task_runs` | `claim_page` (type, status, phase, claim.by, creationDate); `run_tasks`; sparse `lease_sweep`, `timeout_sweep`, `wait_sweep`; `claimed_sweep`; unique `node_uniqueness` (workflowRunRef, name); sparse `parent_index` (a for-each parent's items); `label_wildcard` (`labels.$**`) |
| `workflow_runs` | `claim_page`; sparse `timeout_sweep`, `paused_lookup`, `purge_sweep`; `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase`, `workflow_ref_creation`, `workflow_ref_status`; `initiated_by_phase` (the child runs a cascade cancel pages); `label_wildcard` |
| `workflows`, `workflow_revisions`, `workflow_schedules` | `status_lookup`, `name_lookup`, `creation_date_sort`; `workflow_ref_version`; `fire_sweep`, `workflow_lookup` |
| `tasks`, `task_revisions` | `name_lookup`, `creation_date_sort`; `parent_ref_version` |
| `events_outbox`, `events_inbox`, `task_locks` | `dispatch_page`, `sent_ttl` (7 days); `received_ttl` (7 days), `redrive_page`; `lease_ttl` (at `expiresAt`) |
| `actions` | `task_run`, unique where `taskRunRef` exists (`:130-137`); `status_sweep` |
| `dispatchers`, `users`, `workspaces`, `tokens` | unique `registration` (name, host); unique `email_unique`; `name_lookup`, `display_name_lookup`, unique sparse `external_ref` (an embedding product's own id, carried by at most one workspace); `token_hash_lookup` |
| `rel_nodes`, `rel_edges` | `type_slug`, `type_ref`; `from_label`, `to_label` |
| `audit` | `createdAt_ttl` (365 days; `audit.retentionDays` is applied at startup and re-applied on every instance within a minute of a change, at least 60, `core/audit/AuditRetentionService.java:29,51-65`), `time_desc`, `workspace_time`, `actor_time`, `resource_time` |
| `artifacts` | unique `run_name_idx` (workflowRunRef, name), `status_expiration_idx`, `workflow_status_idx`, unique `workflow_name_available_idx` (workflowRef, name), partial on `scope: workflow, status: available` (`migration/_0021__Indexes.java:190-197`) |

Before the unique indexes are built, duplicates are removed (`migration/_0021__Indexes.java:202-205`): task
runs keep the finished one, else the earliest; actions keep the earliest per task run, after a null
`taskRunRef` is unset; dispatchers keep the most recently connected. Users are never deleted: a shared email is
logged (`:283-291`) and then fails the unique index, and startup with it. An existing index that holds an
inventory index's keys under another name, or its name with another shape, is dropped first (`:298`); any other
index, such as one an operator added, is kept.

## Artifacts

An artifact is a file an upload task attached to its run. Its file lives in an object store; its record lives
in `artifacts` and is reached only through the run: access is the run's `hasWorkflowRun` edge to the workspace,
and the workspace list and storage total go through the workspace's workflows (`workflowRef`). No artifact
field is on `WorkflowRun`, and no quota is on the run.

Each artifact has a scope, set by the task's `scope` param: `run` by default, and an absent scope reads as
`run` (`common/entity/ArtifactEntity.java:45`). A run-scoped artifact belongs to the run that uploaded it and
only that run can download it. A workflow-scoped artifact is the workflow's state across runs: any later run of
the workflow downloads it by name, it has no `expirationDate`, and a later verified upload of the same name
replaces it.

A task's finished log, when its dispatcher uploads it (`PUT /dispatcher/v1/taskrun/{id}/log`), is the third
scope, `log`: named `<task run id>.log`, keyed `runs/<run>/logs/<task run id>`, written by `service-core`
itself and `available` at once (`workflow/ArtifactService.java:406-445`). Two keys of the `artifacts` settings
document govern it, apart from artifact retention: `logs.enabled` (on; off refuses uploads with
`TASKRUN_LOGS_DISABLED` and keeps serving what is stored) and `logs.retention.days` (7); the same expiry sweep
deletes the file when the date passes. It is read only through the task run's log route
(`engine/TaskRunService.java:1331-1352`): the run's artifact list, the workspace storage total and the
artifact download exclude it, and the workspace query returns it only when asked for by scope. An upload task
may not set scope `log`. A second upload for the same task run replaces the first, file and record.

| Status | Written when | File |
| --- | --- | --- |
| `uploading` | the engine hands the upload task to a dispatcher and fills its link params (`workflow/ArtifactService.java:214-240`, from `dispatcher/DispatcherService.java:328`). The key is `runs/<run>/<name>` for run scope and `workflows/<workflow>/<name>/<artifact id>` for workflow scope, so a replacement never overwrites a file before it is verified (`workflow/ArtifactService.java:284-291`) | may be partly written; reaped after `flow.artifacts.upload-grace-minutes` (60) |
| `available` | the dispatcher ends the upload task succeeded: `service-core` reads the stored file for its size and SHA-256 and checks the limits (`workflow/ArtifactService.java:314-372`, called from `engine/TaskRunService.java:1234`); a refusal ends the task failed with `ArtifactRefused`. For workflow scope the previously available record of that name is expired first, then this one is marked available (`workflow/ArtifactService.java:375-384`), so the partial unique index `workflow_name_available_idx` always holds and the later verified completion wins | in the store |
| `expired` | the watcher passes `expirationDate` (`workflow/ArtifactService.java:569-578`), or, for workflow scope, a replacement completes | deleted; the record stays so the run still shows what it produced |

A record is removed only by a delete, its workflow's prune (`workflow/WorkflowRetentionService.java:278-311`) or its
run's retention purge. Finished runs are kept for the `retention.days` key of the `workflowrun` settings
document (365; `service-core/src/main/resources/seed/settings.json:456`), read at each pass and floored at 60
so a month's quota counts stay whole; a document without the key reads as 365
(`workflow/WorkflowRetentionService.java:313-324`). An hourly pass (`flow.watcher.retention-interval-ms`,
`engine/WorkflowWatcher.java:213-223`) pages runs in phase `completed` created before the cutoff, oldest first,
on the `phase_creation_sweep` index, and deletes each one's run- and log-scoped artifacts (files first),
actions, task runs, relationship node and finally the run document (`workflow/WorkflowRetentionService.java:229-269,335-343`).
A run another run still names in `initiatedByRef`, by the run's id (a retry's origin) or by one of its task runs'
ids (a child's parent), is kept until that run is gone (`WorkflowRetentionService.java:326-333`, on the
`initiated_by_phase` index), so a carried-over task run never outlives the one it stands for and a child's
"started by" link never dangles. A purge request (`api-contract.md`, "Run purge") removes one run the same way with
its lineage in the path workspace (a run another workspace owns stays, with all that points at it), deepest first, cancelling one in flight and stamping `purgeRequestedAt` on one whose tasks have not
settled (still ending, or terminated and held by a dispatcher), which the `purgeRequestedRuns` sweep removes on the sparse `purge_sweep` index
(`workflow/WorkflowRetentionService.java:119-180`). Workflow-scoped artifacts and audit
events are never purged. The first pass runs within a minute of startup, so an upgraded install purges its old
runs before the setting can be changed, and no value turns the purge off; history worth keeping is exported
before upgrading.

Limits are admin settings: the `artifacts` document (default retention 30 days, maximum 90, largest artifact
1024 MB) and the `max.artifact.storage` quota (5Gi); a workspace overrides storage and retention with its
`maxArtifactStorage` and `artifactRetentionDays` quotas. A task may ask for a shorter retention, never a longer one; a workflow-scoped upload may not set
`retention-days` at all and is refused with `ARTIFACT_INVALID_SCOPE` if it does
(`workflow/ArtifactService.java:246-251`). Workflow-scoped artifacts count toward the same quota and hold it
until replaced or deleted. An upload over the largest artifact or the storage quota has its own file deleted
and its task refused; no existing artifact is deleted to make room.

A download whose artifact does not exist fails the task with `ArtifactRefused`, unless the task set
`optional: true`: then the engine ends it `succeeded` at hand-out with reason `ArtifactNotFound` and the result
`found=false`, and nothing is dispatched (`dispatcher/DispatcherService.java:323-349`). A dispatched download
that ends succeeded gets `found=true` from the engine, over the declared result's empty value
(`engine/TaskRunService.java:1248-1271`), so a
decision after the task can branch on `found`. The store is any S3-compatible service
(`workflow/S3ArtifactStore.java`), configured by `flow.artifacts.*` and off unless `flow.artifacts.enabled`
(`workflow/config/ArtifactStoreConfiguration.java:30`). Its client sends checksums only where S3 requires them
(`:83-84`), because stores such as SeaweedFS intermittently refuse the AWS client's default checksum.

## Migrations

`service-core` runs every pending change unit on Flamingock while its context starts, before the web server,
the scheduler and the dispatcher API (`migration/MigrationConfiguration.java:20-21`;
`flamingock.management-mode=INITIALIZING_BEAN`, `service-core/src/main/resources/application.properties:125`).
Replicas that start together wait on `sys_migration_lock` for up to 30 minutes (`:84`), then skip what another
replica applied; a failed unit fails startup.

The chain migrates exactly two starting points, an empty database and an in-place v3 install, to the shape the
application reads. A v3 install is one whose loader changelog holds changeset `111`, the last that flow-loader 0.5.9
writes, which every v3 chart from bmrg-flow 4.13.5 ships (`migration/InstallGeneration.java:39`). `_0001` refuses
anything else before writing: an older v3 install, a v4 install, or a database a 5.0 beta already
migrated (it holds `sys_changelog_loader`); a refusal is retried on every start
(`migration/_0001__GuardAndDetectGeneration.java:30,47-62`). Every unit retries after a failure
(`@Recovery(ALWAYS_RETRY)`), so a start interrupted mid-migration resumes on the next one. The seeds in
`src/main/resources/seed/` carry no ids: each unit matches on a natural key (a settings `key`, a role's `type`
and `name`, a task's `name`, a template's `name` and `version`), a seeded document takes its id from MongoDB, and
a seeded revision or template task names its catalogue task, resolved to the live id on insert. Before
upgrading a v3 database, check for two users sharing one email: `_0021` never deletes an account, so a shared
email fails startup; two emails that differ only by case are left as they are and logged
(`migration/_0009__NormaliseUserEmails.java:109`). `_0001` records the generation once in
`sys_migration_state` (`migration/LegacyGenerationMarker.java:40`); v3-only units read that marker.
`V3DumpMigrationTest` runs the chain against a real v3 dump and pins what an upgraded install ends with;
`MigrationChainTest` and `SettingsFromSeedTest` cover the empty path, a v3 fixture, the refusals and the
dedupes. Once a release ships the chain, changes are appended as new units.

| Unit | Gate | Does |
| --- | --- | --- |
| `_0001__GuardAndDetectGeneration` | all | Refuses a v4 or beta database; records v3 or fresh |
| `_0002__V3PrepareCollections` | v3 | Renames `teams` to `workspaces`; drops the Quartz store, `tokens`, `tasks_locks`, `workflows_activity_task` and the unprefixed `locks` |
| `_0003__SeedRelationshipRoot` | all | Seeds the `root:root` graph node |
| `_0004__SeedSystemWorkspace` | all | Seeds the `system` workspace, found by name and type, and its graph node and edge |
| `_0005__V3MigrateGlobalParameters` | v3 | `global_config` → `parameters` |
| `_0006__V3MigrateTaskCatalogue` | v3 | `task_templates` with embedded revisions → `tasks` + `task_revisions`; task runs' template references |
| `_0007__V3MigrateWorkspaces` | v3 | Reshapes v3 teams in `workspaces`; extracts `approver_groups` |
| `_0008__V3MigrateUsers` | v3 | Reshapes `users`; one personal workspace per user |
| `_0009__NormaliseUserEmails` | all | Lower-cases `users.email`, leaving case collisions as they are and logging them |
| `_0010__V3MigrateWorkflows` | v3 | Reshapes `workflows`; `workflows_revisions` → `workflow_revisions`; v3 run storage becomes the revision's `workspace` (a blank mount path pinned to `/workspace/workflowrun`), v3 workflow storage becomes `legacyWorkflowStorage: true` |
| `_0011__V3ExtractWorkflowTemplates` | v3 | Workflows with `scope=template` → `workflow_templates` |
| `_0012__V3MigrateRuns` | v3 | `workflows_activity` → `workflow_runs`; approvals → `actions`; schedules → `workflow_schedules` |
| `_0013__V3BuildRelationshipGraph` | v3 | Builds `rel_nodes` and `rel_edges`, approver group edges included; clears the hand-off fields |
| `_0014__V3DropIntermediates` | v3 | Drops the consumed v3 collections |
| `_0015__SeedRoles` | all | Seeds the five roles |
| `_0016__BuildSettingsFromSeed` | all | Builds every settings document from `seed/settings.json`, carrying stored values (below) |
| `_0017__SeedTaskCatalogue` | all | Seeds the 105 catalogue tasks, matched by name, and their revisions, matched by task and version; a v3 task an install renamed gets the seed's copy beside it |
| `_0018__SeedArtifactTasks` | all | Inserts `upload-artifact` and `download-artifact` (`scope` on both, `optional` and the `found` result on download), ids assigned on insert |
| `_0019__V3UpgradeCatalogueRevisions` | v3 | Moves revisions off the retired `worker-flow` image onto `boomerangio/task-flow:3.3.0`; gives the child-run tasks every param their seeded revision declares |
| `_0020__SeedTemplates` | all | Seeds the starter workflow templates (by name and version) and the integration templates (by name) |
| `_0021__Indexes` | all | Dedupes, then builds the index inventory (above) |
| `_0022__ReencryptSettingsAesGcm` | all | Re-encrypts v3's AES/CBC secured settings values (decrypted with the `mongo.encrypt.*` pair) and encrypts plaintext ones under the `flow.encrypt.*` key; stops on a value it cannot decrypt (or blanks it when `flow.migration.reset-unreadable-settings=true`) or on a blank key (`authorization.md`, "Secured settings at rest") |
| `_0023__SeedAiTaskFamily` | all | Inserts `ai-prompt` and `ai-agent` on a database seeded before they joined the catalogue; an empty database has them from `_0017`. An install's `ai` task, which the catalogue does not ship, is left as it is |
| `_0024__SeedAiImageInputRevisions` | all | Inserts revision 2 of `ai-prompt` and `ai-agent` (image input) where an install holds the task but not that revision; revision 1 is never touched, and a task the install does not hold gets nothing |
| `_0025__ClearCompletedTaskLeases` | all | Unsets `claim.leaseExpiresAt` on completed task runs; a live claim's lease is untouched |
| `_0026__SeedAiWorkflowTemplate` | all | Inserts the `summarise-a-web-page-with-ai` template (by name and version) where it is missing; an empty database has it from `_0020` |
| `_0027__RenameWorkspaceTaskNodes` | all | Moves each `teamtask` node to `workspacetask` under its new `_id` and re-points the edges naming it; only a database that ran a v5 beta holds one |
| `_0028__RunSettingsOutOfAnnotations` | all | Removes the `boomerang.io/*` run annotations (`workspace-name`, `task-*`, `*-params`) from runs and the workspace name from task runs; moves a pre-approved task run's `preApproved` flag and `status` annotation to `preApprovedStatus`; re-keys a held task lock from `<workspace name>:<key>` to `<workspace id>:<key>` |
| `_0029__ClaimIndexKeysTheClaimant` | all | Rebuilds `task_runs.claim_page` as (type, status, phase, claim.by, creationDate) where it was built without `claim.by`; an empty or a v3 database already has the new keys from `_0021` |
| `_0030__SeedSlackCredentialRevisions` | all | Inserts the revision of each of the 13 Slack tasks whose bot token or webhook URL is a `password` field, with every other seed revision of those tasks the install lacks, so a later seed revision never lands in its place; revisions the install holds are never touched, and an empty database has them from `_0017` |
| `_0031__SeedGitHubIntegrationParameters` | all | Gives the GitHub integration template its `fields` where it has none, and inserts the revision of each of the 17 GitHub tasks whose `token` and `url` default to `$(workspace.params.github-token)` and `$(workspace.params.github-url)`, where an install holds the task but not that revision; an empty database has both from `_0020` and `_0017` |
| `_0032__SeedSlackIntegrationParameters` | all | Makes the seeded Slack integration template, inactive and typed `slack`, the active `slack_auth` template its installations are stored as, with its install link, instructions and `fields` (`slack-token`), and inserts the revisions of each of the 10 Slack token tasks the install lacks, whose newest defaults `token` to `$(workspace.params.slack-token)`; an empty database has both from `_0020` and `_0017` |
| `_0033__SeedSendGridTemplateRevision` | all | Inserts the SendGrid template task's revision 3, whose `apiKey` has no placeholder default and whose `templateId` is a `text` field, where an install holds the task but not that revision; an empty database has it from `_0017` |
| `_0034__SeedFormIntegrations` | all | Sets each integration template's `kind` (`app` for GitHub and Slack) where it has none, and inserts the Google and Microsoft `form` templates where an install holds none of their type; an empty database has them from `_0020` |
| `_0035__SeedTaskFlowIntegrationTasks` | all | Adds task-flow 3.3.0's catalogue: the 16 GitHub, Microsoft Teams, SharePoint and Google Drive tasks an install lacks, with their graph node and root edge, and every seed revision of those and of the Sheets, Drive and Artifactory tasks the install lacks; a seeded task an install dropped gets nothing, and the GitHub integration template's instructions gain the App permissions the GitHub tasks need. An empty database has all of it from `_0017` and `_0020` |
| `_0036__DefaultTaskImageTaskFlow33` | all | Moves the `task` settings' `default.image` from `boomerangio/task-flow:3.1.0` to `3.3.0` where an install still holds the earlier seed's value; an operator's own value is left, and an empty database or a v3 upgrade has `3.3.0` from the seed |
| `_0037__SeedGoogleKeyDefaultRevisions` | all | Inserts the revision of each of the 8 Google Sheets and 4 Drive tasks whose `serviceAccountKey` defaults to `$(workspace.params.google-service-account-key)`, where an install holds the task but not that revision; an empty database has them from `_0017` |
| `_0038__WorkflowTemplatesIntoSystem` | all | Moves every `workflow_templates` document (the seeds of `_0020` and `_0026`, and a v3 install's own from `_0011`) into the system workspace as a `starter` workflow with a revision per version and its `hasWorkflow` edge, a name the workspace already holds left as it is, then drops the collection |
| `_0039__SeedWorkspaceCreateSetting` | all | Adds the `features` setting `workspaceCreate` (whether a user who is not an admin may create a workspace) from the seed to an install built before it existed; an install that has the key is left as it is |
| `_0040__SeedAcquireLockMillisecondsRevision` | all | Inserts the Acquire Lock task's revision 2, whose `timeout` is stated in milliseconds and defaults to `300000` (revision 1 said seconds and defaulted to `300`), where an install holds the task but not that revision; an empty database has it from `_0017` |
| `_0041__RefreshIntegrationInstructions` | all | Replaces each integration template's `instructions` with the seed's text, by `type`, so an install seeded earlier reads the same Connect and Disconnect guidance; nothing else on the template changes |
| `_0042__StarterWorkflowLayerReferences` | all | Rewrites, in the starter workflows' revisions alone, the references admission refuses: a task param reading its own name through `$(params.x)` names `$(workflow.params.x)`, and the v3 `$(params.workflow-activity-id)` becomes `$(context.params.workflowrun-ref)` |
| `_0043__UnpinTaskFlowImages` | all | Unsets `spec.image` on every task revision pinning `boomerangio/task-flow:*`, so each runs on the `task` settings' `default.image`; the AI, Box and Python worker images stay |
| `_0044__BoxPrivateKeyIsASecret` | all | Types the Box tasks' `privateKey` as `password` on their revisions, and in every workflow revision using them makes the stored value a `secret` run param encrypted under `flow.encrypt.secret`/`salt`; a sealed, blank or redacted value is left as it is |
| `_0045__ClearPlaceholderDefaults` | all | Empties the placeholder default a few credential parameters carried (older GitHub revisions' `token`, the SendGrid template task's `apiKey` and `templateId`); the parameters stay |
| `_0046__SeedAutoCreateUsersSetting` | all | Adds the `features` setting `autoCreateUsers` (whether a person's first sign-in creates their user) from the seed to an install built before it existed; an install that has the key is left as it is |

`_0016` finds each stored settings document by its key or an earlier key (`controller` → `task`, `activity` →
`workflowrun`, `extensions` → `integration`, `workspaces` or `teams` → `quotas`) and rebuilds it from the seed
under the stored id. Each entry takes the value stored under its key or an earlier key (`github.pem` →
`github.jwt`, the v3 `max.team.*` quota keys, `teamQuotas` or `workflowQuotas` → `workspaceQuotas`), except a
value stored as another type (secured values are decrypted by type), the shipped 90-minute task ceiling, a
`worker-flow` image and a choice that is not an option: those take the seed value. Entries the seed does
not define are kept; retired entries and the v3 User Defaults and workflow storage documents (keys `users` and
`workflow`) are removed (`migration/_0016__BuildSettingsFromSeed.java:49-95`).

## Not built

Nothing enforces the `<prefix>/<name>` label convention in code, apart from reserving `boomerang.io/` for the
server.

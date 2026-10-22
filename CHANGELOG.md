# Changelog

Operator-facing changes, collected as they merge. The `/release` skill turns the unreleased section into the
tag's release notes.

## Unreleased: 5.0.0

### Upgrading

- **The Java images have no shell.** `flow-service-core` and `flow-service-dispatcher` run on Ubuntu's chiseled
  JRE: no shell, package manager or `keytool`. Set JVM flags in `JDK_JAVA_OPTIONS` (the `JAVA_OPTS` variable and
  the `sh -c java …` command the v3 chart used no longer apply), and trust an internal CA by mounting a
  truststore and naming it there (`-Djavax.net.ssl.trustStore=…`) rather than importing a certificate at
  container start. The container runs as uid 10001 in group 0.
- **Migrations run inside `service-core` as it starts.** The `flow-service-loader` image and its pre-deploy
  Job are gone; remove them from the deployment and give `service-core` a startup probe that covers the
  migration. One product tag builds three images.
- **Only an empty database or a v3 database can be upgraded.** A v4 database, or one a 5.0 beta migrated,
  is refused at startup. Reset beta databases before upgrading.
- **Encryption keys are two pairs.** `mongo.encrypt.secret`/`mongo.encrypt.salt` now mean the v3 pair, read
  once to decrypt v3's secured settings during the upgrade. `flow.encrypt.secret`/`flow.encrypt.salt` is the
  key v5 encrypts with and is required on every install. Set both before the first start against a v3
  database. Secured settings are encrypted with AES-GCM; v3's values are re-encrypted on the first start.
- **Workflow storage is removed.** A workflow has one shared workspace per run, declared as a single
  `workspace` (size, mount path, class, access mode) instead of a `workspaces` list of two types; the volume
  keyed by the workflow is gone. A v3 workflow that had Workflow storage is marked on upgrade and refused at run
  with `WORKFLOW_STORAGE_REMOVED` until it is saved again. To keep files between runs, add a Download Artifact
  task with *Scope: Workflow* and *Optional* at the start of the workflow and an Upload Artifact task with
  *Scope: Workflow* at the end, both with the same name and the path you mounted before; kept artifacts appear
  under Configure › Artifacts. Upgraded workflows keep their mount path (`/workspace/workflowrun` when none was
  set); new workflows mount at `/workspace`. Flow no longer manages the old Workflow storage volumes: copy out
  anything you need, then delete them with
  `kubectl delete pvc -n <namespace> -l boomerang.io/workspace-type=workflow`.
- **One storage quota.** `max.workspace.storage` (the run workspace size cap, carried from
  `max.workflowrun.storage`) replaces `max.workflow.storage` and `max.workflowrun.storage` in the `quotas`
  settings group; the per-workspace quota field is `maxWorkspaceStorage`. Kept artifacts count toward
  `max.artifact.storage` until replaced or deleted. The dispatcher's `kube.workflow.storage.*` properties are gone.
- **Before a v3 upgrade, check for users sharing one email.** The migration never deletes an account, so a
  shared email stops startup until it is resolved. See "Upgrading from v3" in the README.
- **`flow.mode` is gone; two properties express the deployment shape.** `flow.security.enabled` (default `true`)
  switches authentication and authorization together, and `flow.workspace.single` (default `false`) serves the
  seeded `system` workspace alone. An embedded, headless engine sets `false` and `true`. Remove `FLOW_MODE` from
  every deployment. A secured install with no person to sign in takes its first credential from
  `flow.security.bootstrap-token`, registered as a global machine token at start.
- **Finished runs are now purged after the Activity retention, 365 days by default.** The new "Activity
  Retention (days)" setting in the workflow-run group (floored at 60) deletes each finished run older than it,
  with its task runs, actions and run-scoped artifacts and logs, once an hour. A run that a retry or a child run
  still points at is kept until that run goes. Workflow-scoped artifacts and audit events are never touched.
  The purge is on from the first start: its first pass runs within a minute and each hourly pass removes up to
  1000 runs, so an upgraded install loses its runs older than a year within hours, before the setting can be
  changed, and no value keeps every run. This is by design: export any history you need before upgrading
  (README, "Upgrading from v3").
- **The seeded `ai` task is removed; the AI tasks are `ai-prompt` and `ai-agent` on `boomerangio/task-ai:2.0.0`.**
  An upgraded database keeps any `ai` definition it already holds, so its workflows still resolve, but nothing
  maintains it: move nodes to `ai-prompt`, whose param names are the same. `temperature` and `maxTokens` now have
  no default and are sent only when set. On `service-dispatcher`, `flow.dispatcher.ai.image` is gone: every `ai`
  revision declares its image, which must start with one of `flow.dispatcher.ai.image-prefixes` (default
  `boomerangio/task-ai`) or the task is refused with `ImageNotAllowed`. `flow.dispatcher.ai.endpoint` and
  `flow.dispatcher.ai.token` (blank) reach every `ai` pod as `OPENAI_BASE_URL` and `OPENAI_API_KEY`.
- **`flow.uniquenames.enabled` is gone.** A task run's `taskRef` on the API, the status events and the dispatcher
  protocol is the task's slug form, `<slug>` for a global task and `<workspace>/<slug>` for a workspace task, as
  workflow nodes already showed; storage keeps the id, so nothing is migrated. A dispatcher's `task` poll filter
  takes the same form: a bare slug now matches only a global task.
- **A workspace quota of 0 is unlimited, and an absent quota inherits the platform default.** A workspace PATCH
  merges quotas field by field instead of replacing the stored set.
- **Settings and global parameters are updated by `PATCH`; their `PUT` routes are gone.** Send a JSON Merge Patch
  (`application/merge-patch+json` or `application/json`): `PATCH /api/v2/settings` with
  `{"<group>": {"<key>": value}}`, for example `{"artifacts": {"retention.default.days": "30"}}`, and
  `PATCH /api/v2/parameters/{name}` with only the members to change. An absent member is unchanged, `null` clears
  it, and a secured value sent as `*****` is kept. An unknown settings group or key is now a `400
  SETTINGS_INVALID_KEY` rather than ignored.
- **One rule for secret values on every layer, and blank now clears.** A set secret reads `*****` and an unset one
  reads `null` on parameters, workflow defaults and node values, task defaults, schedules, settings and runs
  (workflow defaults used to read blank; node, schedule and run values used to read `*****` even when unset). On a
  write, `*****`, `null` or a missing value keeps the stored secret, `""` clears it, and any other value sets it.
  Before, a blank kept the secret: a client that sends `""` to mean "unchanged" must send `*****` or leave the
  value out. On a run request, a param sent as `null` or `*****`, or left out, keeps the declared default for every
  param type. A value that is only a reference, such as `$(workspace.params.token)`, reads as written.
- **A workflow body's unknown top-level fields are ignored and no longer echoed back** in the save response, so the
  response shows exactly what was stored.
- **The workflow canvas read masks secrets like every other workflow read.** A Slack integration's tokens are now
  encrypted at rest; one stored before this release keeps its plaintext token until Slack is next linked, with no
  migration.
- **A dispatcher must heartbeat every task it holds; `flow.dispatcher.lease.enabled` is gone.** A claim now carries
  a lease from the moment it is made, 90 s ahead (`flow.dispatcher.lease-ms`), and each heartbeat renews it, so a
  claim its dispatcher stops reporting is requeued even when the dispatcher restarts under the same id. Flow's
  Kubernetes dispatcher and the dispatcher SDK heartbeat every 30 s from the claim; a dispatcher written against
  the protocol directly that sends no heartbeat has every claim requeued 90 s after it was made.
- **The first start clears the lease from completed task runs.** Change unit `_0025__ClearCompletedTaskLeases`
  runs one update over `task_runs`; completion now clears the lease itself, so the lease sweep reads only live
  claims. Nothing else changes and no setting is involved.
- **The inbound Slack routes require Slack's request signature.** Set the Slack app's signing secret in the
  integration settings (`slack.signingSecret`); with none set, the commands, interactivity and events routes
  refuse every request.
- **The dispatcher protocol hands out its own run models.** The task poll and the task start and end answers return
  `DispatchTaskRun`, and the workflow poll and its start answer `DispatchWorkflowRun`, in place of the public
  `TaskRun` and `WorkflowRun`; only the fields a dispatcher reads remain (`contracts/dispatcher-v1.yaml`). Move an
  application built on `io.boomerang:dispatcher-sdk` to 5.0.0-beta.23 or later: its `TaskRun` and `WorkflowRun`
  have no getters for the dropped fields, so code that read one fails to compile instead of reading null. An older
  SDK still works against this engine, as its runtime reads only the remaining fields. A dispatcher written against
  the protocol directly must stop reading a task run's `annotations`, `creationDate`, `startTime`, `duration`,
  `statusMessage`, `statusReason`, `taskVersion`, `workflowRevisionRef`, `workflowName`, `parentRef` and
  `reusedFromRef`, and every workflow-run field but `id`, `status`, `phase`, `workflowRef`, `labels` and
  `workspace`; it reads them from the run through `/api/v2` instead.
- **The first start rebuilds the task-run claim index.** Change unit `_0029__ClaimIndexKeysTheClaimant` adds
  `claim.by` to `task_runs.claim_page`, so a dispatcher poll's termination check reads only the task runs a
  dispatcher still holds instead of every cancelled or timed-out task run of the last seven days. The rebuild runs
  before `service-core` serves and takes time in proportion to `task_runs`.
- **Stop every `service-core` replica before starting this version.** Change unit
  `_0028__RunSettingsOutOfAnnotations` removes the `boomerang.io/*` annotations the engine used to read from runs and
  task runs, moves an early event's status to a typed field, and re-keys held task locks by workspace id. A replica
  of an earlier version still running would read runs whose annotations have gone.
- **The last "team" names on the API say "workspace".** `GET /api/v2/profile` returns the user's workspaces as
  `workspaces` (was `teams`), and the error reasons `TEAM_INVALID_REF`, `TEAM_INVALID_REQ`, `TEAM_NON_UNIQUE_NAME`,
  `TEAM_INVALID_USER_ROLE` and `WORKFLOW_TEAM_INACTIVE` are now `WORKSPACE_INVALID_REF`, `WORKSPACE_INVALID_REQ`,
  `WORKSPACE_NON_UNIQUE_NAME`, `WORKSPACE_INVALID_USER_ROLE` and `WORKFLOW_WORKSPACE_INACTIVE`, with the same codes.
  A client that matched a reason or read `teams` moves with them. On the first start, change unit
  `_0027__RenameWorkspaceTaskNodes` moves a workspace's own task nodes in the relationship graph from type
  `teamtask` to `workspacetask`; only a database a 5.0 beta created holds any.

### Added

- Insights group task runs: `GET /api/v2/workspace/{workspace}/insights/tasks` counts the period's task runs per
  task, type, workflow or label, optionally per hour, day or month, with mean, p50, p95 and max duration, the
  tokens and cost of its AI task runs, the wall clock of the runs sharing a label, and the slowest task runs. The
  Insights page shows it under Task runs once a grouping is chosen, with Model and For-each item groupings.
- Task runs can be queried across runs: `GET /api/v2/workspace/{workspace}/taskrun/query` pages them by label or
  run (one of the two is required), workflow, task name, status and date, as the run query pages runs. A row
  leaves out params, results, spec, annotations and status message; read the run for those.
- A task run shows `attempt` once a dispatcher has claimed it: 1, plus one per requeue. The run view shows it when a
  task was requeued.
- Server labels: an AI task run carries `boomerang.io/model`, the model its `model` param asked for, and every
  item task run of a for-each, and a child run a for-each run-workflow task started (on the run and its task runs),
  carries `boomerang.io/item`, its item.
  Labels under `boomerang.io/` are now reserved for the server, as annotations already were: a client's are
  dropped on submit, start and retry.
- The wait-for-event callback (`POST`/`GET /api/v2/callback`) takes an optional `eventId`. A repeat of the same
  callback for the same run is acknowledged and not applied again for 7 days; without one, each delivery is
  applied as before.
- A run's timeout bounds execution only: while every in-flight task waits on an approval, a manual task, an
  event, a child run, a sleep or a lock, the deadline is parked and moved forward by the time waited, and the
  same applies after a pause. A wait is indefinite unless its task declares a timeout. A run's reported duration
  is execution time. Gated workflows no longer need a budget sized for their approvers.
- A retry can start from the tasks that did not succeed, or from named tasks: `PUT …/workflowrun/{id}/retry` takes
  an optional `{scope: all | failed | nodes, nodes, params, labels}`. The rest is carried over as copies marked
  `reusedFromRef`, and the run records `retryFrom`. The run page's Retry run and each task's Retry from here open
  the same choice. The new run starts with an empty run workspace, so files the carried-over tasks wrote there are
  not carried over; pass them as workflow-scoped artifacts, or re-run everything. The dialog says so.
- `secret` is a parameter type. Secret values are encrypted at rest under `flow.encrypt.*`, masked on every read,
  and never written to a log.
- The audit trail records global parameter, settings, catalogue task and schedule writes, and every write that
  changes or clears a secret names it in the event's `detail`, never its value.
- A run-workflow task may run for each item, one child run per item, and a waited child's results become the
  task's results.
- A run-workflow node may carry params its child workflow declares, such as an upstream task's result; the child
  run receives them resolved. A param the child does not declare is refused at save with
  `WORKFLOW_INVALID_CHILD_PARAM` (1218). A child run receives only the params its node passes, plus `item` and
  `index` under for-each; the parent run's params are not handed down, as in v3.
- Revision 2 of `ai-prompt` and `ai-agent`, on `boomerangio/task-ai:2.1.0`, takes images: `images` lists
  run-workspace paths or https URLs, with `imageDetail` and `maxImageBytes` (5 MiB). The image also exports
  OpenTelemetry spans when an OTLP endpoint is configured. Revision 1 stays on 2.0.0 for workflows pinned to it.
- Every task gets a `TRACEPARENT` derived from its run and task run ids, so one run's tasks share one trace, and
  `TaskRun.traceparent()` in the dispatcher SDK computes the same value. On `service-dispatcher`,
  `flow.dispatcher.ai.otel.endpoint`, `.headers` and `.protocol` (blank by default) give `ai` pods the OTLP
  exporter settings. Flow stores no traces; point them at Langfuse, Phoenix, LangSmith or Tempo.
- Insights filter by run `labels` and roll up AI usage: tokens, cost and a breakdown by model for the period, and
  tokens and cost per task.
- A dispatcher may end a task with `RateLimited`, which requeues it with backoff like `ExceededQuota`.
- A failed task's status message carries the container's exit code and the tail of its log.
- `$(workspace.params.x)` replaces `$(team.params.x)`, which still resolves, logs a warning, and is removed in the
  next major version.
- `dispatcher-sdk` adds `StatusReason`, naming every reason a dispatcher sends; `TaskFailure`'s four reason
  constants are the same values.

### Changed

- The task edit modal keeps its description to one line and moves the task name and the tabs into its header, and
  lists a task's declared results on a Results tab instead of as tags under the parameters, so the fields get the
  height.
- A run's workspace is its workflow's owner, looked up by id, so a run in flight keeps its workspace's parameters,
  secret redaction and lock scope when the workspace is renamed. A task run takes the `task` settings (timeout
  ceiling, default image, deletion policy) when the run is queued, so a retry, a run migrated from v3 included, takes
  them as they stand then. A run's only `boomerang.io/*` annotations are `generation` and `kind`; a submit or start
  that sends one has it dropped.
- Building from source needs no GitHub token: Maven resolves every dependency from Maven Central. The dispatcher SDK
  is still published to GitHub Packages.
- Spring Boot 4.1.1, with Tomcat 11.0.27, Jackson 2.21.8 and 3.1.8 and commons-io 2.22.0 ahead of it, and the
  latest chiseled JRE base image, for the critical and high CVEs fixed since 4.1.0. The webapp image has no npm or
  corepack: the server runs on node alone.
- `GET /api/v2/context` no longer carries `platform.privateTeams`, which was always `false`.
- `flow.schedule.watcher.enabled=false` now stops the schedule sweep. Before, the engine's scheduling kept it
  running whenever the engine watcher was on.
- A global token, the bootstrap token included, reads a workspace's Insights, as it reads its runs and task runs.
  The three Insights routes refused it with `401 AUTH_REQUIRED`.
- A run that is invalid when queued, and a task ended because its run is gone or already completed, end through
  the same compare-and-set as every other end, so the end is published: a child run invalid at queue releases the
  parent waiting on it, and such a run is audited and counted against quota.
- A run-scheduled-workflow task passes the scheduled run only its node's other params, not its own `futureIn`,
  `futurePeriod`, `timezone` and `time`, matching the run-workflow task.
- The `wfe-url` context param points to the wait-for-event route, `/api/v2/callback`.
- The engine ends a task it decided the end of - a skip, an admission refusal, an engine-run task's result, a for-each
  parent's outcome - in one write that carries the outcome, so a timeout that lands meanwhile is never rolled back
  and each such end publishes one transition.
- A workspace's monthly run-duration figures (`currentRunTotalDuration`, `currentRunAverageDuration`) cover the
  whole UTC calendar month. They used to stop at midnight before the month's last day, so that day's runs were
  never counted.
- A workspace's quotas report `currentRunAverageDuration`, the mean run time this month in milliseconds like every
  other duration, where `currentRunMedianDuration` held that mean under the wrong name and in the wrong unit: the
  Quotas page showed milliseconds as minutes. `currentRunTotalDuration` is a long, so a busy month no longer
  overflows. The insights response drops `medianDuration`, which was also the mean; `totalDuration` and
  `totalRuns` give it.
- A workflow's Insights `retriedRuns` counts the runs that are a retry of an earlier run, by hand or automatic. It
  counted the runs whose workflow allowed retries, whether or not any happened.
- A switch that never ran, because the path into it was not taken or the run ended first, takes none of its
  branches, so the tasks after it are skipped and the run carries on. It used to fail the run with a null-value
  error when the next task was queued.
- An approval task ends with the five results it declares: `approvalStatus` (`approved` or `rejected`),
  `approvalDate` (ISO 8601, UTC), `approvalUserName`, `approvalUserEmail` and `approvalComments`, taken from the
  approver whose action decided it, so `$(tasks.<name>.results.approvalStatus)` reads a value instead of an empty
  string. Its "Number of Approvals" setting (`numberOfApprovals`) is honoured; the engine read a different name,
  so every approval completed with one approver. `numberOfApprovers` is still read where a definition sets it.
  Each approval is kept as it is given, so one that needs several approvers completes when the last of them acts.
- Each status event describes the transition it was recorded for. An event sent after the run moved on used to
  carry the run's later status, so one change could arrive as two or three copies of a later state. Its
  CloudEvent `id` is now the outbox row's id, the same on every delivery of that transition, and `time` is when
  the transition occurred rather than when it was sent. The graph's end node no longer sends a task event; the
  run's own event reports the end. A skipped task, or one admission refuses, sends one event where it sent two. A workflow timeout sends one where it sent two.
- A dispatcher call runs as the dispatcher's own token, so code on the dispatcher wire reads its caller the way
  code on `/api/v2` does. An audit record of a run that a dispatcher's end call completes names that token as the
  actor, where it named `system`.
- While a task runs, its log is read live from its dispatcher even when a copy is stored; a copy stored during this
  attempt answers only when the dispatcher has no live route or its route is unavailable, and once the task ends
  the stored copy is served first. A requeued task no longer shows its previous attempt's log while it runs again.
- A followed task log that has written nothing for 10 minutes ends with a line saying the view stopped following,
  instead of the connection dropping mid-log; open the log again to keep following. No new property: the two-hour
  async request timeout applies only to the streamed routes, both task logs and artifact downloads.
- Task logs and artifacts store reliably on S3-compatible stores such as SeaweedFS: the store's client sends
  checksums only where S3 requires them, so a log is no longer refused now and then with "The Content-Md5 you
  specified is not valid".
- Settings are built from the shipped seed on first start, carrying an upgraded install's own values, so an
  upgraded and a new install have the same settings documents.
- The same indexes are built on every install; the token lookup index is now built on a new install.
- Migrated v3 approver groups are reachable from their workspace again.
- The sign-in routes the webapp's server calls are `/internal/v1/auth/{config,exchange,logout}`, outside the
  public `/api/v2`; the 5.0 betas served them under `/api/v2/auth`. Route `/api/v2/**` from outside and leave
  `/internal/v1/**` unrouted.
- The dispatcher wire is `/dispatcher/v1`; the 5.0 betas served it under `/api/v1/dispatcher`. `service-core` and
  `service-dispatcher` move together under one tag. Route `/dispatcher/v1/**` to `service-core` only for a
  dispatcher that runs outside its network.
- **Task logs are stored by `service-core` when it has an artifact store.** The dispatcher uploads a finished
  task's log (`PUT /dispatcher/v1/taskrun/{id}/log`, capped by `flow.engine.task.log.max-bytes`, 10 MiB) and core
  serves that copy once the task has ended, so a log outlives the pod and needs no dispatcher up. Otherwise core reads the live
  log from the dispatcher that holds the claim, at the URL it registered: set `flow.dispatcher.logs.url` on
  `service-dispatcher` (for example `http://service-dispatcher:7702/api/v1/logs/stream`). Core's
  `flow.agent.service.host` and `flow.agent.logstream.url` are gone: nothing on core names a dispatcher, and a
  dispatcher that registers no URL has no live log to follow. The dispatcher's live log route,
  `/api/v1/logs/stream`, accepts only the bearer it generates at start and registers; nothing but core can read
  it. Its v3 `GET /api/v1/logs` read is gone, and it serves no OpenAPI page. Two new keys
  in the Artifacts settings group govern storage: "Store Task Logs" (on) and "Task Log Retention (days)" (7),
  apart from artifact retention; stored logs never count toward artifact storage. Set
  `flow.dispatcher.logs.upload=false` on a dispatcher to keep its logs in the pod only.
- List filters behave the same on every route. A `labels` entry without a value, such as `labels=team`, is a
  `400 QUERY_INVALID_FILTERS` everywhere instead of a server error on most lists. A label is split once on its
  first `=`, so `labels=a%3Db%3Dc` matches the value `b=c` (it was read as `b`). The user list's `ids` filter
  now matches. The action list's and action counts' `toDate` is exclusive, like every other query. `statuses` and `phase` are
  read ignoring case on the run, task, workflow, user, workspace and schedule lists.
- A running task's log streams as it is written, through `service-core` and the Kubernetes dispatcher, instead of
  arriving whole when the task ends. Read before its container starts, it shows one waiting line and then the live
  log.
- A dispatcher whose task slots are all taken claims new work the moment one frees, instead of up to 30 s later
  when the engine let go of its held poll. The dispatcher SDK keeps a poll that claims nothing open for
  termination orders while full, and starts its work poll beside it as soon as a task ends; it never asks for
  more than its free slots. This applies to `service-dispatcher` and to any dispatcher built on
  `io.boomerang:dispatcher-sdk`; the wire is unchanged.
- A catalogue task's Tekton YAML carries each param's default and description; a `password` default reads `*****`
  and is kept when the YAML is applied back unchanged.
- An upload- or download-artifact task is handed out to its dispatcher and completes with workspace quotas
  enforced, whatever the security setting; in 5.0.0-beta.19 it stayed claimed and never ran. A task the dispatcher
  poll cannot prepare now ends `failed` with reason `DispatchPrepareFailed` instead of staying claimed, and the
  rest of the poll is still handed out.
- The `tokens.<name>` context parameters are gone. They held a token's stored hash, which cannot authenticate, under
  a name no reference could reach. A task that needs a credential takes it from a `secret` parameter.

### Fixed

- A run with a workspace that no dispatcher claims within its timeout now ends `timedout` instead of waiting for
  ever and holding one of the workspace's concurrent-run slots; its timeout counts from submit until a dispatcher
  starts it.
- A workspace artifact storage quota of `0` is unlimited, as the other quotas are; it refused every upload before.
- A workflow saves with a workspace size up to its own workspace's storage quota (`0` for any size), not only
  up to the platform default, matching what the workspace may run.
- A workflow saved with a workspace but no size takes the smaller of the platform default and its own
  workspace's storage quota when quotas are enforced; it used to take the platform default even when that was
  over the workspace's quota.
- An approval or manual task that a run cancel or a task timeout completed while its action was being created
  stays completed; it used to flip back to waiting with its action left open for nobody to answer.
- A workflow token's principal is the workflow id everywhere: the editor issued it under the workflow name and the
  delete revoked by name, so deleting a workflow revoked the tokens of every workflow with that name in every
  workspace and left its own scheduler token in place.
- A task attempt retried after an earlier attempt was cancelled or failed with its TaskRun or Job left in place
  (deletion policy `Never`, or `OnSuccess` after a failure) no longer ends at once with the earlier attempt's
  result, and no longer reports the earlier attempt's pod as its own start failure, failure detail or log: each
  executor watches the object its attempt created or adopted, reads only that object's pod (the stored and live
  logs included), adopts only an object still running, and on Tekton a cancel reaches every attempt still running.
- A dispatcher's start or end for an attempt the engine has already requeued is refused with
  `TASKRUN_CLAIM_SUPERSEDED`, so the retry the engine granted stands. When the engine's start-timeout requeue and
  the dispatcher's own `StartTimeout` end landed together, the end used to fail the task and waste the retry.
- Slack reaches its routes on a secured install. `POST /api/v2/integration/slack/{commands,interactivity,events}` and
  `GET /api/v2/integration/slack/auth` no longer require a Flow credential; each checks the request itself (the
  Slack signature, or the OAuth code exchange). `GET /api/v2/integration/slack/install` still requires sign-in, and
  the unused `/api/v2/extensions/slack/install` permit entry is gone.
- The retention purge keeps a parent run while a run its run-workflow task started still exists, as it already
  did for a retried run; a parent purged first left every child's "started by" link pointing at a deleted task run.
- A dispatcher whose registration the engine no longer holds (a database reset under a running dispatcher) is
  answered `404 DISPATCHER_NOT_REGISTERED` on its polls and heartbeats instead of a 500, and the dispatcher SDK
  registers again and carries on under the new id instead of failing every poll until restarted. A registration
  without a host is `400 DISPATCHER_INVALID_REQ`.
- A for-each item that ends with a declared result of no value leaves an empty slot in the parent's combined array;
  the parent used to throw on that item's end and on every recovery sweep, stranding the run as running.
- The stalled-run recovery re-advances a run only after two consecutive sweeps find no task in flight; a run caught
  in the instant between a task's end and the next admission was re-advanced on one sighting, with a "Task Status
  invalid" error for every completed task.
- Workspace owners and editors can issue workflow tokens from a workflow's Configure › Tokens tab; it answered
  `TOKEN_ADMIN_REQUIRED` to every non-admin because the permission catalog was asked with the workflow, not the
  workspace, as principal.
- A dispatcher SDK stop that lands as a claim is being taken drains that claim to its end, or refuses it before it
  enters flight; it used to drop a claim already in flight as "not taken", holding a claim it never reported on
  until its lease lapsed.
- A task run admitted since 5.0.0-beta.26 stored its label keys with their dots unescaped, so it matched no label
  query (the task-run query, a dispatcher's workflow-label filter, Insights by label) from admission until its end;
  a for-each's items were missing from the task-run query while they ran. Admission now stores the keys as an
  entity write does. Task runs admitted on beta.26 to beta.28 and still in flight match once they end.
- A dispatcher SDK poller no longer ends when its pool's free-slot supplier throws: the throw is logged, read as not
  due, and the poller asks again after its spacing. Before, every pool whose supplier failed during a store outage
  stopped claiming until the process restarted, while the dispatcher went on reporting itself running.

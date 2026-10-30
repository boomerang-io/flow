# Changelog

Operator-facing changes, collected as they merge. The `/release` skill turns the unreleased section into the
tag's release notes.

## Unreleased: 5.0.0

### Upgrading

- **Catalogue tasks without an image of their own run on `boomerangio/task-flow:3.3.0`** (the `task` settings'
  `default.image`, was 3.1.0), as do the v3 revisions the upgrade moves off `worker-flow`. Change unit
  `_0036__DefaultTaskImageTaskFlow33` moves an install whose setting still holds 3.1.0; an image an operator chose is
  kept. What changes for those tasks:
  - Script tasks run on Alpine 3.24 with Node 24 (was 20), npm 11 at `/usr/local/bin/npm` (`/usr/bin/npm` is gone),
    GNU tar, OpenSSL 3.5, curl 8.23 and bash 5.3.
  - Tasks that exited successfully having done nothing now do their work, or fail visibly: Append Data (Sheets)
    appends its rows, Copy File (Drive) copies, and the Artifactory download and upload tasks run at all. The
    download needs a destination path, relative paths resolve against `/workspace`, Basic auth is sent encoded, and
    the upload verifies TLS, so a self-signed Artifactory fails until its CA is trusted.
  - The Drive tasks reach shared drives, and List Files honours its corpora and drive ID.
  - The mail, ServiceNow and Slack tasks work when `HTTP_PROXY` is set, and JSONPath To Parameter no longer
    crashes with `module is not defined`.
  - Task logs no longer print param values, and Google errors no longer include the request's bearer token.
- **The GitHub tasks need GitHub App permissions.** The GitHub catalogue tasks act with the GitHub App's
  installation token when a workspace shares it; between them they need Issues: write, Pull requests: write,
  Contents: read or write, Commit statuses: write and Actions: write on the App. Each task's description names its
  own, and an organisation approves the App again when its permissions change. The Teams and SharePoint tasks
  default to the Microsoft integration's parameters, and the new Google Drive tasks to the Google integration's;
  in a workspace that has not connected the integration, enter the credentials in the task instead.
- **The Java images have no shell.** `flow-service-core` and `flow-service-dispatcher` run on Ubuntu's chiseled
  JRE: no shell, package manager or `keytool`. Set JVM flags in `JDK_JAVA_OPTIONS` (the `JAVA_OPTS` variable and
  the `sh -c java …` command the v3 chart used no longer apply), and trust an internal CA by mounting a
  truststore and naming it there (`-Djavax.net.ssl.trustStore=…`) rather than importing a certificate at
  container start. The container runs as uid 10001 in group 0.
- **Migrations run inside `service-core` as it starts.** The `flow-service-loader` image and its pre-deploy
  Job are gone; remove them from the deployment and give `service-core` a startup probe that covers the
  migration. One product tag builds three images.
- **You can upgrade from v3, although runs are dropped.**
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
- **The CloudEvent trigger works, with a new run shape.** `POST /api/v2/event` used to fail before a run
  was saved. A run now gets `event`, the event's attributes, and `data`, its data, and no longer one param per
  top-level data member: read `$(params.data.<field>)`. The trigger's filters read the event as sent (`type`,
  `data.tag`). `ref` still takes a workflow id; add `workspace` to name the workflow instead
  (`?workspace=<workspace>&ref=<workflow name>`, on `POST /api/v2/webhook` too), which is the URL the editor now
  shows. Without `ref`, the subject's first segment names the workflow. v3 workflows that filled properties from
  a CloudEvent's data with a `jsonPath` need changing: see "Upgrading from v3" in the README.
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
- **A dispatcher must heartbeat every task it holds.** A claim now carries a lease from the moment it is made, 90 s
  ahead (`flow.dispatcher.lease-ms`), and each heartbeat renews it, so a claim its dispatcher stops reporting is
  requeued even when the dispatcher restarts under the same id. Flow's Kubernetes dispatcher and the dispatcher SDK
  heartbeat every 30 s from the claim; a dispatcher written against the protocol directly that sends no heartbeat has
  every claim requeued 90 s after it was made.
- **The inbound Slack routes require Slack's request signature.** Set the Slack app's signing secret in the
  integration settings (`slack.signingSecret`); with none set, the commands, interactivity and events routes
  refuse every request.
- **Every GitHub App delivery requires GitHub's signature, and the GitHub trigger now works.** Set the App's
  webhook secret in the integration settings (`github.webhookSecret`); with none set, every GitHub event is
  refused with `401`, where before only `installation` events were. A GitHub event now starts the workflows whose
  GitHub trigger is on (it used to read the webhook trigger's switch), its Events and Repository filters apply,
  and one workflow refusing no longer stops the rest of the workspace. The run gains an `event` param,
  `{type, id}`, beside `data`.
- **The Slack tasks take their bot token and webhook URL as password fields.** Change unit
  `_0030__SeedSlackCredentialRevisions` adds a new revision of each of the 13 Slack tasks, so a token typed into a
  node is stored encrypted and read back as `*****`. Workflows keep the revision they pin until upgraded in the
  editor; upgrading keeps the value, which is encrypted on the next save.
- **The GitHub integration can share its installation token with tasks.** Once GitHub is connected, the workspace's
  integration page offers "Share the installation token with tasks", which writes two read-only workspace parameters,
  `github-token` (refreshed every 45 minutes) and `github-url`; workspace parameters must be on. Change unit
  `_0031__SeedGitHubIntegrationParameters` gives the GitHub integration template those fields and adds a revision of
  each of the 17 GitHub tasks whose `token` and `url` default to them; a token typed into a node still wins. A task
  upgraded to the new revision and left on its default fails at admission in a workspace that does not share the
  token; the editor warns at save. A read-only workspace parameter an integration manages can no longer be changed or
  deleted through the workspace (`409 PARAMS_MANAGED_BY_INTEGRATION`), and `readOnly` sent on a workspace update is
  ignored.
- **Slack is a workspace integration and can share its bot token with tasks.** The Integrations page now lists
  Slack once `slack.clientId` and `slack.clientSecret` are set: Connect installs the Flow Slack app and links that
  Slack team to the workspace, and "Share the installation token with tasks" writes a read-only `slack-token`
  workspace parameter. In the Slack app's settings, set the OAuth redirect URL to
  `<Flow API URL>/api/v2/integration/slack/auth`, add the bot scopes `channels:manage`, `channels:read`,
  `chat:write`, `commands`, `files:write`, `groups:read`, `groups:write`, `users:read` and `users:read.email`, and
  leave token rotation off, since a rotating token cannot be shared. The `slack.installURL` setting is no longer
  read: `GET /api/v2/integration/slack/install` builds Slack's authorize URL itself. Change unit
  `_0032__SeedSlackIntegrationParameters` makes the Slack template active and adds a revision of each of the 10
  Slack tasks that call the Slack API whose `token` defaults to `$(workspace.params.slack-token)`. The share
  route is now `POST /api/v2/integration/{github|slack}/share`; the GitHub URL is unchanged.
- **Google and Microsoft can be connected by entering their credentials.** The Integrations page lists them with
  no platform setting needed: Connect asks for a Google Cloud service account's JSON key, or a Microsoft Entra
  app's tenant ID, client ID and client secret, and writes them as read-only workspace parameters
  (`google-service-account-key`; `microsoft-tenant-id`, `microsoft-client-id`, `microsoft-client-secret`).
  Manage edits them, a stored secret kept until it is retyped, and Disconnect removes them. The share route takes
  them as `values`. Change unit `_0034__SeedFormIntegrations` adds the two templates and marks GitHub and Slack as
  integrations Flow installs. No catalogue task defaults to them yet.
- **The last "team" names on the API say "workspace".** `GET /api/v2/profile` returns the user's workspaces as
  `workspaces` (was `teams`), and the error reasons `TEAM_INVALID_REF`, `TEAM_INVALID_REQ`, `TEAM_NON_UNIQUE_NAME`,
  `TEAM_INVALID_USER_ROLE` and `WORKFLOW_TEAM_INACTIVE` are now `WORKSPACE_INVALID_REF`, `WORKSPACE_INVALID_REQ`,
  `WORKSPACE_NON_UNIQUE_NAME`, `WORKSPACE_INVALID_USER_ROLE` and `WORKFLOW_WORKSPACE_INACTIVE`, with the same codes. A
  client that matched a reason or read `teams` moves with them.

### Added

- CloudEvents sink filters. `flow.events.sink.types`, `statuses` (any run status, or `terminal`) and `phases` narrow
  what is sent, and each destination takes the same keys plus `labels` (`key=value`). A transition no sink wants is
  never stored, so a filtered event is not counted as dead; labels filter at delivery. `phases=completed` is the true
  end of a run with finally tasks. With no filter keys nothing changes.
- A run's locks go with the run. A run that completes with any status, or is purged, holding an `acquirelock` lock it
  never released frees it at once, so the next `acquirelock` on the key succeeds on its first attempt instead of waiting
  out the lease; a lock another run holds is never touched.
- Finally tasks. A task linked from End with the execution condition `finally` runs once the run has ended, any way:
  succeeded, failed, cancelled or timed out. The run takes its final status when its graph ends and stays in the
  `running` phase while its finally tasks run in parallel, each with its own timeout under the `workflowrun` setting
  `finally.timeout` (5 minutes), an operator's ceiling outside the run's own timeout that no workflow can raise; the stage
  counts in the run's duration and holds its concurrency slots; the run completes when the last of them ends, and a finally task that fails turns a
  succeeded run into `failed` with the reason `FinallyFailed`. A finally task reads the run's outcome as
  `$(context.params.workflowrun-status)` and `$(context.params.workflowrun-status-reason)`; a cancel during the finally
  tasks does nothing more, a purge still stops them. Finally tasks never chain: saving refuses a `finally` dependency
  on anything but End and any dependency on a finally task (`400 WORKFLOW_INVALID_FINALLY`). In the editor End gains
  an output port; a task linked from it shows a Finally badge and no output port, and its link a fixed finally
  condition.
- A workspace can be found by `externalRef`, an embedding product's own id for the record it mirrors:
  `GET /api/v2/workspace/query?externalRef=` answers the one workspace carrying it, within the caller's access, and a
  create naming a reference a workspace already carries is `409 WORKSPACE_EXTERNAL_REF_TAKEN` with that workspace's
  name, so a retried create is idempotent. The reference is set once at create and never patched; every reader of the
  workspace sees it, and the webapp shows it on Settings. A unique sparse index, `external_ref`, joins the inventory.
- Templates are workflows of the system workspace. A workflow carries a `type`: `workflow` (the default), `template`
  or `starter`, and the last two can be created, versioned and edited only in the system workspace, through its
  ordinary workflow routes. A workspace runs a template by reference: a workflow created with `templateRef: {name,
  version}` shows the template's tasks and params read-only under its own name, labels and triggers, accepts
  run-time values only for the params the template declares, and each submit runs the template's pinned or latest
  revision in the workspace, so the run, its quotas and its audit are the workspace's. A template with such
  workflows cannot be deleted. A starter is copied, as the Home page has always done. Change unit
  `_0038__WorkflowTemplatesIntoSystem` moves the seeded templates and a v3 install's own into the system workspace as
  starters and drops the `workflow_templates` collection.
- Templates and starters appear in every workspace through its own workflow routes; there are no `/workflowtemplate`
  routes. `GET /api/v2/workspace/{workspace}/workflow/query` lists, beside the workspace's workflows, the templates and
  starters it has not made its own, each with its `type`, and `types=` narrows; the get, compose, changelog, export and
  available-parameters routes answer a template by name when the workspace has no workflow of that name. The first
  use makes it the workspace's own: `POST …/workflow/{template}/submit` creates the link under the template's name
  and runs it, and `PUT …/workflow` naming a template creates the link and saves the display name, description, icon,
  labels and triggers. A template's `runworkflow` task that names another template runs it the same way, as the run's
  workspace's link, so a template's children never run in the system workspace on a workspace's behalf. The webapp's
  Workflows page shows templates beside the workspace's workflows, tagged and read-only, dashed until first use; the
  editor puts the task palette away for one; Home's starters come from the workspace query with `types=starter`.
  The system workspace changes what a workflow is with `PATCH /api/v2/workspace/system/workflow/{name}` and `{type}`:
  the card's overflow menu offers Make a template, Make a starter or Make a workflow, the system list shows its
  starters and tags both kinds, and its templates stay fully editable there; a template other workspaces link stays one.
- A run can be purged on request: `DELETE /api/v2/workspace/{workspace}/workflowrun/{id}` removes the run with its
  task runs, actions, artifacts and stored logs, and its lineage (the retries that reuse it and the child runs its
  tasks started); `DELETE …/workflowrun?labels=k%3Dv` does the same for every run carrying the labels, a page of 50 a
  call, reporting how many remain. A run in flight is cancelled first; one whose tasks are still ending, or held by a
  dispatcher awaiting its terminate poll, is removed by a new watcher sweep once they have settled, or after ten
  minutes. Both need `workflowrun/delete` (owner and admin) and are audited as destructive. Audit records stay. A new
  sparse index, `purge_sweep`, joins the inventory.
- 16 catalogue tasks from `boomerangio/task-flow:3.3.0`: GitHub (create an issue, comment on an issue or pull
  request, create a pull request, set a commit status, dispatch a workflow, create a release, get file contents),
  Microsoft Teams (send a message, create a channel, add a member), SharePoint (upload, download and list files) and
  Google Drive (upload, download and share a file). The Google Sheets and Drive tasks gain a revision that takes the
  whole service-account key file. Change unit `_0035__SeedTaskFlowIntegrationTasks` adds them to existing installs.
- The SDK's `Runs.purge(id)` and `Runs.purge(labels)` call the two run purge routes and read `{purged, scheduled,
  remaining}`.
- The thin workflow-run status event (`flow.events.sink.payload=thin`) carries `statusReason`, as the task event
  already did, so a consumer acting on a terminal event reads why the run ended without calling back.
- The dispatcher wire carries the retry story: a task order has `attempt`, `attempts` (how the superseded attempts
  ended, with their dispatcher) and, on a terminate order, `statusReason`; a workflow run order has `statusReason`;
  and the end request takes `retryable: false` to mark a failure final, which the engine never requeues whatever
  the node's `retry` setting. The SDK's `TaskFailure.terminal` sends it. A task order also carries `retry: {max,
  failures}`, the node's budget and how much of it the engine has spent, so a handler that acts differently on a
  failure Flow will not retry needs no count of its own; the SDK's `TaskRun.isLastAttempt()` reads it. Additive,
  protocol version 1.
- A task node can retry a failure its dispatcher reports: `retry: {max, backoff: {type, initial, max}}` on the
  node, edited on the task's Configure tab. The engine requeues the task on that budget with that backoff, never
  for a cause a retry cannot change (`ImageNotAllowed`, `AdmissionDenied`, `ResultsTooLarge` or an engine-written
  cause), and records every superseded attempt on the task run as `attempts`, which the run's task view lists. A
  task without the setting runs once, as before; the engine's own requeues of an attempt that never ran keep their
  separate budget.
- A workflow can limit its runs in flight: `concurrency: {limit, overflow}` on the workflow, edited under
  Configure → Execution. A run over the limit is parked as `waiting` until a run of the workflow ends (`queue`, the
  default) or ends at once as `cancelled` with reason `ConcurrencyLimit` (`skip`). The workspace quota keeps
  precedence. A workflow run now carries `statusReason` beside `statusMessage`, as a task run does.
- Work reaches a dispatcher as soon as it is ready: admitting a task, or submitting a run with a workspace, wakes the
  dispatcher polls on that engine instance that can claim it, and a cancel, timeout or retry reaches the holding
  dispatcher within the end round trip, so a hop in a sequential chain is about 100–200 ms. A dispatcher whose slots
  are all taken claims new work the moment one frees. The timed re-check still covers work admitted on another
  replica.
- `flow.dispatcher.poll.recheck-ms` sets how often a held dispatcher poll looks at the claimable page again
  (default 1000, floor 100). A lower value shortens every hop of a sequential chain at the cost of more indexed
  queries per dispatcher: its idle queries per second are its pools × 1000 / the value.
- The dispatcher SDK captures a task's log and opens its trace for an in-process dispatcher: with
  `captureTaskLogs(new TaskLogCapture())` on the builder, the lines a handler logs (tagged `taskRunId` in the MDC,
  the first and last 64 KB kept) are uploaded as the task log when it ends and every 30 s while it runs; with
  `observationRegistry(registry)`, each handler runs under a `flow.task` observation carrying the task's
  `traceparent`. Both are off by default; a logback appender is included, SLF4J and logback stay optional.
- Insights group task runs: `GET /api/v2/workspace/{workspace}/insights/tasks` counts the period's task runs per task,
  type, workflow or label, optionally per hour, day or month, with mean, p50, p95 and max duration, the tokens and
  cost of its AI task runs, the wall clock of the runs sharing a label, and the slowest task runs. The Insights page
  shows it under Task runs, grouped by task unless another grouping, Model and For-each item included, is chosen.
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
- The public API has a checked-in OpenAPI contract, `contracts/api-v2.yaml`, generated from the document
  `service-core` serves at `/api/docs/spec/api-v2` and held equal to it by a test; an integrator reads the routes
  and models from the file at the tag they run.
- `flow-sdk`, a Java library for the public API: `Flow.connect(url, token)` then typed calls to submit, start,
  read, pause, resume, cancel and retry workflow runs, query runs and task runs by label, read a task run's
  stored log, read, query and decide manual and approval actions, read Insights and probe the server's health,
  with the server's error carried as `FlowApiException`. It depends on `spring-web` and Jackson only, and is
  published to GitHub Packages by every product tag beside `dispatcher-sdk`, at the tag's version
  (`flow-sdk/README.md`).
- A for-each parent's task run carries `foreach: true` in the public `TaskRun`, and the task-run query takes
  `foreach=true|false` to list parents only or leave them out; absent lists both as before. A parent stands for its
  items in the graph and never runs, so a running or waiting count over a run's task runs can now exclude it without
  reading the workflow. `flow-sdk` adds `TaskRunQuery.foreach(boolean)` and `TaskRun.foreach()`.
- A `features` setting, Workspace creation by users (`workspaceCreate`, on by default), says whether a user who is not
  an admin may create a workspace; Home and Workspaces offer the button only to those who may.

### Changed

- The Google Sheets (revision 4) and Drive (revision 3) tasks default their service-account key to
  `$(workspace.params.google-service-account-key)`, the Google integration's parameter, as the Drive file tasks
  do. A workflow moves when it is upgraded in the editor. Upgraded, a node that still signs in with Client Email
  and Private Key in a workspace without the Google integration fails at admission with no such workspace
  parameter, and the editor warns at save; type the key into Service Account Key, or connect Google. Where the
  workspace has the parameter, its key is used ahead of Client Email and Private Key. Change unit
  `_0037__SeedGoogleKeyDefaultRevisions` adds the revisions to existing installs.
- The dispatcher runs the upload and download artifact tasks on `boomerangio/task-flow:3.3.0` (was 3.2.1), whose
  runtime base moved from an end-of-life Alpine 3.20 to 3.24; the artifact commands are unchanged. An operator who
  pins `flow.dispatcher.artifact.image` keeps their own value.
- Outside a workspace (Home, Admin, Profile) the side navigation points at the last workspace opened in this browser,
  while you can still open it.
- A dispatcher that cannot provision a run's workspace reports the cause: the run shows it and retries after a minute
  (`flow.watcher.provision-retry-ms`, 60000), failing on the third attempt. A dispatcher built on an older SDK keeps
  the sweep's release.
- A run a person or a token starts records who started it in `initiatedByRef`, and reads name them in the new
  `initiatedByName`; the run page and Activity's Initiated By column show the name.
- A run's duration is shown as active time on the run page and in Activity: it excludes waits for approvals, manual
  tasks and sleeps, and pauses.
- Home counts each member of your workspaces once (the profile's `workspaceMemberCount`).
- The task edit modal keeps its description to one line, carries the task name and the tabs in its header, and lists a
  task's declared results on a Results tab, so the fields get the height.
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
- The Java images start from a JDK AOT cache (JEP 514/515) and run with compact object headers (JEP 519). Each
  image build starts the application once on the runtime JRE, with no database or cluster, and records the loaded
  and linked classes; a container start then reads them instead of redoing that work, which takes roughly 40 % off
  the time to a healthy `service-core` and `service-dispatcher`. Compact object headers take 4 bytes off every
  object on the heap. Both are set in the image's command, under any `JDK_JAVA_OPTIONS` a deployment adds; an
  option there that changes the heap layout (another garbage collector, headers off) makes the JVM skip the cache
  with a warning and start as before. The images ship the application extracted (`service.jar` plus `lib/`),
  which the cache requires, and each is about 120 MB larger for the cache; the health probe and ports are
  unchanged. The dispatcher handles requests on virtual threads, as `service-core` already did.
- The chiseled JRE base image is refreshed to Canonical's current build (OpenJDK 25.0.4.1), which scans clean,
  and the shipped dependencies with an open advisory are moved past it: `nimbus-jose-jwt` 10.3, the Slack client
  1.45.3 (for its `okio` 3.4), `body-parser` 1.20.9, `axios` 1.20.0 and `dompurify` 3.4.17.
- `GET /api/v2/context` no longer carries `platform.privateTeams`, which was always `false`.
- A run-scheduled-workflow task passes the scheduled run only its node's other params, not its own `futureIn`,
  `futurePeriod`, `timezone` and `time`, matching the run-workflow task.
- The `wfe-url` context param points to the wait-for-event route, `/api/v2/callback`.
- A workspace's monthly run-duration figures (`currentRunTotalDuration`, `currentRunAverageDuration`) cover the
  whole UTC calendar month. They used to stop at midnight before the month's last day, so that day's runs were
  never counted.
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
- Each status event describes the transition it was recorded for: its CloudEvent `id` is the outbox row's id, the same
  on every delivery of that transition, and `time` is when the transition occurred. The graph's end node sends no task
  event; the run's own event reports the end, and a skipped or refused task and a workflow timeout send one event
  each.
- A dispatcher call runs as the dispatcher's own token, so code on the dispatcher wire reads its caller the way
  code on `/api/v2` does. An audit record of a run that a dispatcher's end call completes names that token as the
  actor, where it named `system`.
- While a task runs, its log is read live from its dispatcher even when a copy is stored; a copy stored during this
  attempt answers only when the dispatcher has no live route or its route is unavailable, and once the task ends the
  stored copy is served first.
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
- The sign-in routes the webapp's server calls are `/internal/v1/auth/{config,exchange,logout}`, outside the public
  `/api/v2`. Route `/api/v2/**` from outside and leave `/internal/v1/**` unrouted.
- The dispatcher wire is `/dispatcher/v1`. `service-core` and `service-dispatcher` move together under one tag. Route
  `/dispatcher/v1/**` to `service-core` only for a dispatcher that runs outside its network.
- **Task logs are stored by `service-core` when it has an artifact store.** The dispatcher uploads a finished task's
  log (`PUT /dispatcher/v1/taskrun/{id}/log`, capped by `flow.engine.task.log.max-bytes`, 10 MiB) and core serves that
  copy once the task has ended, so a log outlives the pod and needs no dispatcher up. Otherwise core reads the live
  log from the dispatcher that holds the claim, at the URL it registered: set `flow.dispatcher.logs.url` on
  `service-dispatcher` (for example `http://service-dispatcher:7702/api/v1/logs/stream`). Core's
  `flow.agent.service.host` and `flow.agent.logstream.url` are gone: nothing on core names a dispatcher, and a
  dispatcher that registers no URL has no live log to follow. The dispatcher's live log route, `/api/v1/logs/stream`,
  accepts only the bearer it generates at start and registers; nothing but core can read it. Its v3 `GET /api/v1/logs`
  read is gone, and it serves no OpenAPI page. Two new keys in the Artifacts settings group govern storage: "Store
  Task Logs" (on) and "Task Log Retention (days)" (7), apart from artifact retention; stored logs never count toward
  artifact storage. Set `flow.dispatcher.logs.upload=false` on a dispatcher to keep its logs in the pod only.
- List filters behave the same on every route. A `labels` entry without a value, such as `labels=team`, is a `400
  QUERY_INVALID_FILTERS` everywhere instead of a server error on most lists. A label is split once on its first `=`,
  so `labels=a%3Db%3Dc` matches the value `b=c` (it was read as `b`). The user list's `ids` filter now matches. The
  action list's and action counts' `toDate` is exclusive, like every other query. `statuses` and `phase` are read
  ignoring case on the run, task, workflow, user, workspace and schedule lists.
- A running task's log streams as it is written, through `service-core` and the Kubernetes dispatcher, instead of
  arriving whole when the task ends. Read before its container starts, it shows one waiting line and then the live
  log.
- A catalogue task's Tekton YAML carries each param's default and description; a `password` default reads `*****`
  and is kept when the YAML is applied back unchanged.
- An upload- or download-artifact task is handed out to its dispatcher and completes with workspace quotas enforced,
  whatever the security setting. A task the dispatcher poll cannot prepare ends `failed` with reason
  `DispatchPrepareFailed`, and the rest of the poll is still handed out.
- The `tokens.<name>` context parameters are gone. They held a token's stored hash, which cannot authenticate, under
  a name no reference could reach. A task that needs a credential takes it from a `secret` parameter.

### Fixed

- The Send Email with SendGrid Template task's revision 3 has no placeholder default for its API key, and takes the
  template ID as plain text; revision 2 defaulted both to a placeholder and masked the template ID. Change unit
  `_0033__SeedSendGridTemplateRevision` adds it to existing installs; a workflow moves when it is upgraded in the
  editor.
- Disconnecting GitHub on the Integrations page disconnects it. `POST /api/v2/integration/github/unlink` takes the
  integration's record id or the installation id, refuses a caller who cannot reach the workspace (integration write
  permission in any workspace could unlink another workspace's GitHub by its installation id), answers `404` for an
  unknown installation, and is audited.
- The GitHub integration dialog reads the installation and its repositories through the external HTTP client, so
  the proxy, trust and timeout settings apply, and it lists every repository the installation can reach.
- An approval or manual task that a run cancel or a task timeout completed while its action was being created
  stays completed; it used to flip back to waiting with its action left open for nobody to answer.
- A workflow token's principal is the workflow id everywhere: the editor issued it under the workflow name and the
  delete revoked by name, so deleting a workflow revoked the tokens of every workflow with that name in every
  workspace and left its own scheduler token in place.
- Slack reaches its routes on a secured install. `POST /api/v2/integration/slack/{commands,interactivity,events}` and
  `GET /api/v2/integration/slack/auth` no longer require a Flow credential; each checks the request itself (the
  Slack signature, or the OAuth code exchange). `GET /api/v2/integration/slack/install` still requires sign-in, and
  the unused `/api/v2/extensions/slack/install` permit entry is gone.

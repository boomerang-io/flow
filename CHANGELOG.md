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

### Added

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

### Changed

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
  serves that copy first, so a log outlives the pod and needs no dispatcher up. Otherwise core reads the live
  log from the dispatcher that holds the claim, at the URL it registered: set `flow.dispatcher.logs.url` on
  `service-dispatcher` (for example `http://service-dispatcher:7702/api/v1/logs/stream`). Core's
  `flow.agent.service.host` and `flow.agent.logstream.url` are gone: nothing on core names a dispatcher, and a
  dispatcher that registers no URL has no live log to follow. The dispatcher's `/api/v1/logs` route
  now accepts only the bearer it generates at start and registers; nothing but core can read it. Two new keys
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

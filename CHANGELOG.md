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

### Added

- A run's timeout bounds execution only: while every in-flight task waits on an approval, a manual task, an
  event, a child run, a sleep or a lock, the deadline is parked and moved forward by the time waited, and the
  same applies after a pause. A wait is indefinite unless its task declares a timeout. A run's reported duration
  is execution time. Gated workflows no longer need a budget sized for their approvers.
- A retry can start from the tasks that did not succeed, or from named tasks: `PUT …/workflowrun/{id}/retry` takes
  an optional `{scope: all | failed | nodes, nodes, params, labels}`. The rest is carried over as copies marked
  `reusedFromRef`, and the run records `retryFrom`. The run page's Retry run and each task's Retry from here open
  the same choice.
- `secret` is a parameter type. Secret values are encrypted at rest under `flow.encrypt.*`, masked on every read,
  and never written to a log.
- A run-workflow task may run for each item, one child run per item, and a waited child's results become the
  task's results.
- A dispatcher may end a task with `RateLimited`, which requeues it with backoff like `ExceededQuota`.
- A failed task's status message carries the container's exit code and the tail of its log.
- `$(workspace.params.x)` replaces `$(team.params.x)`, which still resolves, logs a warning, and is removed in the
  next major version.

### Changed

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

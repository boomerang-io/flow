# Changelog

Operator-facing changes, collected as they merge. The `/release` skill turns the unreleased section into the
tag's release notes.

## Unreleased

### Fixed

- **A task is told its real results budget.** `RESULTS_MAX_BYTES` is now the bytes left for result values once the
  envelope of the task's declared results is taken from the 4096-byte termination message, so a task that fills its
  budget no longer lands a few bytes over and fails `ResultsTooLarge`. A task with no declared results is told 4094 on
  Jobs; each declared result takes its name plus 6 bytes on Jobs and plus 31 on Tekton. Escaped characters in a value
  (`"`, `\`, newlines) still count twice.

## 5.0.1 (2026-11-25)

### Upgrading

- **Run one instance while upgrading to 5.0.1**, or the runs recorded by older instances during the roll-out drop out
  of this month's counts: the upgrade moves the audit trail to workspace ids, while an older instance still records
  workspace names.
- **`start` on submit is removed: a submit always starts its run, or queues it.** `POST …/workflow/{name}/submit`
  takes no `start` query parameter, and `PUT …/workflowrun/{id}/start` and the Java SDK's `Runs.start` and
  `Runs.submit(…, boolean)` are gone: nothing stages a run for later. A retry starts the run it creates as well, so
  the webapp's Retry and the API's retry route need no second call. The `flow.workflowrun.auto-start-on-submit`
  property is gone with them: webhook, event and GitHub triggers always start their runs. The dispatcher's own
  `PUT /dispatcher/v1/workflowrun/{id}/start` is unchanged. A client that sent `?start=false` to create a run it
  started later now has the run started at once.
- **The default monthly run quota moves from 20 to 100.** On the first start, an install whose **Settings > Quotas >
  Max WorkflowRuns per month** still holds the 20 that 5.0.0 shipped has it set to 100. A value an administrator
  chose, 50 say, is left as it is, and so is every quota a workspace stores for itself: only workspaces that inherit
  the platform default see their monthly limit change. Set it back to 20 in Settings to keep the 5.0.0 limit.
- **A run that has waited more than 24 hours for a slot is cancelled.** A run parked by its workflow's own
  concurrency limit could wait without end in 5.0.0. The first sweep after the upgrade, which runs as the server
  starts, first admits every parked run whose slot is free and then cancels the ones still parked for longer than
  **Settings > Run limits > Maximum Queue Wait (hours)** with the reason `QueueExpired`, 50 runs a sweep. The
  upgrade adds the setting at 24; set it to `0` to keep the 5.0.0 behaviour of no limit. Runs already parked for more than 24 hours are cancelled before the value
  can be changed; retry any of them you still need.
- **"Concurrent runs" now counts running runs only.** A workspace's concurrent usage, in the webapp and in
  `currentConcurrentRuns` on the workspace API, is the runs holding a slot: admitted, running or in their finally
  tasks. A run waiting for a slot is no longer in that figure; it is in the new `currentQueuedRuns`. A dashboard or
  alert that reads `currentConcurrentRuns` as "everything not yet finished" needs the two added together.
- **A submit over the concurrent limit answers `200`, not `429`.** A client that treated `429 QUOTA_EXCEEDED` as
  "try again later" for the concurrent limit no longer receives it there: the run is created and the response carries
  it with `status: waiting`. `429` still means the month's runs are spent, the requested workspace is too large or,
  new, the queue is full.

- **Only an administrator or a global token can set a workspace's quotas.** A create or patch of a workspace that
  carries `quotas`, and `DELETE …/workspace/{workspace}/quotas`, answer `403 PERMISSION_DENIED` for anyone else, a
  workspace's owners and editors included, and change nothing. A patch without `quotas` is unchanged. An automation
  that sets quotas with a workspace or personal token needs a global token.

### Added

- **A workspace queues the runs over its concurrent limit instead of refusing them.** A submit that would exceed
  **Concurrent Runs** is created, waits as `waiting` with "Waiting for a slot: the workspace's limit of 4 concurrent
  runs is reached." on the run, and starts when a slot frees, the run that has waited longest first. A workspace has
  one queue: it holds the runs its own limit holds back and the ones a workflow's own `concurrency` limit holds back,
  and a waiting run takes no slot of either. Time spent waiting does not count against the run's timeout. Child runs
  (a run-workflow task, a for-each fan-out) are never held by the workspace's limit, since their parent already holds
  a slot; they start at once and count toward the total, and one waiting on its own workflow's limit is admitted
  even while every slot of the workspace is taken. A waiting run says which limit holds it in `statusReason`:
  `WorkspaceConcurrencyLimit` for the workspace's, `ConcurrencyLimit` for its workflow's own. With quotas switched off, or the concurrent limit set to `0`,
  nothing is held and the next sweep admits whatever was waiting.
- **The queue has a limit of its own: the Queued Runs quota.** `max.workflowrun.queued` in **Settings > Quotas**,
  20 by default, with a per-workspace override on the workspace's Quotas tab (`maxQueuedRuns`) like the other
  quotas; `0` is unlimited. It counts the runs waiting for a slot of the workspace: a submit that finds every slot
  taken and that many waiting is refused with `429 QUOTA_EXCEEDED`, "Queued runs". A run its own workflow's
  `concurrency` limit holds is never refused and never counted, so that queue stays unbounded, as in 5.0.0, and
  one workflow's backlog cannot make the workspace refuse another workflow's runs. Five times the concurrent limit
  is a good starting point. The workspace API and the Quotas tab show `currentQueuedRuns` beside it.
- **A run cannot wait for a slot for ever.** **Settings > Run limits > Maximum Queue Wait (hours)**
  (`workflowrun`/`queue.max.age`), 24 by default, `0` for no limit. A run that waits longer ends `cancelled` with the
  reason `QueueExpired` and a message that says how long it waited and what the limit is. A run whose slot has
  come free is admitted rather than cancelled, however long it waited. The value is whole hours: anything else
  (`1.5`, a blank, text) is read as 24 and said once in the server log.
- **A schedule's dropped fire shows on the Activity list.** When a schedule's run cannot be submitted on any of its
  three attempts, the workflow gets a `cancelled` run with the reason `SubmitRefused`, triggered by the schedule,
  whose message reads, for example, `Schedule "nightly" fired at 02:00 UTC and no run started after 3 attempts.
  QUOTA_EXCEEDED: maximum queued runs`. It never ran, holds no slot and does not count toward the month's runs, and
  the audit trail records one failed submit for it. Retrying it runs what the schedule would have run.
- **The webapp says when a run will wait.** The Run button stays enabled with every slot busy and says the run will
  wait its turn; it is disabled only when the queue is full or the month's runs are spent. A queued run's page shows
  what it is waiting on beside its status, and a run the queue cancelled explains why instead of showing a run error.
  Retrying a run with every slot busy says the retry is in the queue. The run page has no Start button: nothing
  waits to be started.
- **A monthly cumulative duration quota per workspace.** **Cumulative Duration** caps the minutes of task time a
  workspace may use in a month: each task's time from its start to its end, added up, so tasks running side by side
  each count, and counted when the run ends, rounded up to the minute per run. A submit or retry once the month's
  minutes reach the limit is refused `429 QUOTA_EXCEEDED`, "Cumulative duration this month (minutes): 612 / 600"; runs
  already going finish. It is unlimited by default: **Settings > Quotas > Max Cumulative Duration per month** is `0`,
  and a workspace can set its own on its Quotas tab. A child run started by a run-workflow task is never held to it,
  nor to the month's runs, and still counts.
- **Unmetered workflows.** **Settings > Quotas > Unmetered Workflows** names workflows of the `system` workspace,
  separated by commas, whose runs, and the runs of every workspace's template link to them, use none of a workspace's
  monthly runs or cumulative duration. Exact names only; anyone who can write to `system` can create a workflow of a
  listed name. A workflow added mid-month keeps its earlier runs counted; one removed has its earlier runs counted
  again but not their minutes.
- **The workflow list says which workflows are unmetered.** `GET …/workflow/query` marks each workflow with a
  read-only `unmetered`, and the webapp's Run button stays enabled for one with the month spent.

### Changed

- **Audit events record the workspace by its id, with its name at the time**, so a renamed workspace keeps its audit
  history and monthly usage, and a workspace created under a deleted one's name starts fresh. The upgrade re-keys the
  existing trail; the audit listing's `workspaceId` filter takes an id or a name, and each event shows the workspace's
  name as it was then.
- **Workspace tokens are now issued for the workspace's id, and follow a rename.** A token created on a workspace's
  Tokens tab names the workspace by its id. A token issued earlier for the workspace's name moves to its id when the
  workspace is renamed, so it keeps working, is listed and revoked with the workspace, and never passes to a new
  workspace that later takes the old name. A token works on the workspace's paths by id or by name alike.
- **A workspace's name is lower-case letters, numbers and hyphens.** A create or a rename whose name is in any other
  form is refused `400 WORKSPACE_NAME_NOT_CANONICAL`, and the message says the form to send: `Platform Team` would be
  `platform-team`. A name shaped like a workspace id is refused `400 WORKSPACE_NAME_RESERVED`, and the `system`
  workspace keeps its name. Workspaces already stored with another form keep it.
- **A rename is confirmed first, and the display name changes on its own.** The workspace's Settings edit the
  display name and the name in separate fields; a new name asks before it is saved, saying what stops working. The
  name the webapp shows is the one the server takes, accented and non-Latin letters dropped.
- **Retries queue at the concurrent limit.** Retrying a run from the webapp or the API at the concurrent limit
  answers `200` with a `waiting` run, which starts when a slot frees; it is refused with `429` only when the month's
  runs are spent or the queue is full. A run's automatic retry after a timeout queues the same way.

- **Permission checks read far less on a large install.** Who can see what is unchanged, and grants and memberships
  are still read on every request. Measured for a member of 100 workspaces of 200 workflows each, on a 1.12 million
  node relationship graph:
  - The profile counts each workspace's workflows on the edge index instead of listing them: 21.8 ms, 64 KB
    returned and 1.4 MB allocated per request, where 5.0.0 took 238 ms, 13.9 MB and 245 MB.
  - An access check follows only the relationship labels that lead to what it looks for, and reads nodes and edges
    as plain documents without their data. The same member's approver groups take 6.0 ms and 701 documents, where
    5.0.0 read every workflow edge and node to find them; a listing of every workflow the member can see takes
    92 ms, 4.50 MB returned and 46.6 MB allocated instead of 238 ms, 13.9 MB and 245 MB; a lookup of a workflow
    by name reads that workflow's node, not every workflow's in the workspace.
  - A request reads its caller's memberships once, when it authenticates, and every check in that request uses
    that read. A member removed from a workspace is refused from their next request, as before.
  - An admin's list of every workspace no longer reads every user and membership: 3,002 documents in 8.2 ms for
    500 workspaces and 2,001 users, instead of 11,403 documents.
  - The Workflows page finds a workspace's workflows in one walk and no longer loads each workflow to list the
    templates beside them; deleting a workspace resolves its workflows once instead of once per workflow; platform
    Insights with no workspace named reads every workspace's workflows in two queries instead of three per workspace.
  - In the webapp, an open run page refreshes only the run every 5 seconds instead of reloading the profile,
    features, navigation and starters with it, and Home's first load reads the profile once instead of twice.

### Fixed

- **A dispatcher built on the SDK stops quietly.** A long poll the stop cuts off is logged at debug, not as a
  failed poll, and a poll still held at the drain deadline is logged at info, saying its claim is handed out again
  when its lease lapses. A poll that fails while the dispatcher runs is still a warning.
- **A run that cannot start now ends `invalid` instead of waiting forever.** A run admitted but never started, such
  as one whose graph names a task it does not have, was tried again on every recovery sweep and stayed `ready`.
  Fifty of them filled the sweep's page, so no later run whose start was lost was ever started. Such a run now ends
  `invalid` with the `WorkflowInvalid` reason, and its end frees its slot and notifies a parent run as any end does.
  A database error during the start ends nothing: the run is tried again on the next sweep.
- **`DELETE /api/v2/workspace/{workspace}` takes the workspace's id as well as its name**, as `GET` and `PATCH` do.
- **A change to a workspace addressed by id touches that workspace alone**, even where a 3.5 upgrade left another
  workspace of the same name: its graph node, its members and its name.
- **`GET` and `PATCH /api/v2/workspace/{workspace}` take the workspace's id as well as its name.** An id passed
  the access check and was then answered `404 WORKSPACE_INVALID_REF`.
- **Only an administrator or a global token can set a workspace's quotas.** The webapp has only ever offered the
  quota editor to administrators; the API now holds every caller to the same rule.
- **The average run duration counts only the runs that ended.** A workspace's **Current average execution time**
  divided the month's total by every run of the month, the ones still running included, so it read low while runs
  were going. It is now the total over the runs that ended.
- **A run waiting for a slot no longer carries a timeout deadline.** In 5.0.0 a run that declares a workspace took
  its deadline when it was submitted, parked or not, so the time it spent waiting for a slot of its workflow's
  concurrency limit was taken out of its timeout. A parked run now has no deadline; the deadline for the wait for a
  dispatcher is set when the run is admitted.
- **The timeout sweep no longer ends a run that is waiting for a slot.** In 5.0.0 the sweep took every pending run
  past its deadline, so a parked run that had waited longer than its timeout ended `timedout` without ever running.
  It now leaves parked runs alone, including the ones 5.0.0 already stored with a deadline, which take a fresh one
  when they are admitted.
- **A run whose start was lost is started.** A run with nothing to provision that was admitted when the server
  stopped, before it started the run, waited in `ready` with no deadline and no sweep. A sweep now starts any such
  run it finds on two passes in a row.
- **A retried run no longer carries the reason its original ended with.** In 5.0.0 a retry kept the original run's
  status reason, so a retry of a run that had timed out showed `DeadlineExceeded` from its first moment and still
  showed it after succeeding. A retry now starts with no reason of its own.
- **An automatic retry that had to wait is no longer logged as refused.** In 5.0.0 a timed-out run whose automatic
  retry was parked by its workflow's concurrency limit logged "Retry refused" although the retry had been created
  and ran later. The log now says the retry is queued.
- **A one-off schedule whose run is refused no longer stays Enabled forever.** In 5.0.0, when the run of a one-off
  schedule could not be submitted (a quota, for example) on all three attempts, the schedule was left Enabled with no
  next date: it never ran and was never marked. It now moves to Error after the third attempt, a minute or two after it
  was due, and the log names the reason. Open the schedule and save it, with a new date or the same one, to arm it
  again. A recurring schedule is unchanged: it waits for its next occurrence. A one-off schedule already stuck by
  5.0.0 stays Enabled with no next date; save it again to arm it.
- **A GitHub App delivery whose run is refused is no longer answered as delivered.** In 5.0.0 a delivery that matched
  a workflow whose run was refused (a quota, for example) was answered `200`, so GitHub showed it as delivered and the
  event was lost with only a warning in the server log. When no run starts, the delivery is now answered with the
  refusal itself, `429 QUOTA_EXCEEDED` for a quota, in the standard error body, so it shows as failed in the App's
  recent deliveries and can be redelivered once the quota allows. When a delivery starts some runs and others are
  refused it is still answered `200`, because redelivering it would start the started runs a second time. Each
  refused workflow is recorded in the audit trail as a failed submit that names the workflow, the event and the
  delivery id.

## 5.0.0 (2026-11-17)

5.0.0 is the first release since 3.5 (last 3.5.54): 4.x was never released for general use, so every entry below
compares 5.0.0 with 3.5.

### Upgrading

- **Upgrade from a 3.5 install built by bmrg-flow 4.13.5 or later.** That is flow-loader 0.5.9 or later, whose
  changelog holds changeset `111`. 5.0.0 refuses an older 3.5 database, a 4.x database or one a 5.0.0 beta migrated,
  on every start and before it writes anything: move an older 3.5 install to that chart first. Back up the database
  before the first start; the migration is forward-only and drops each 3.5 collection once it has migrated it.
- **Stop every 3.5 service before 5.0.0 starts.** 3.5 and 5.0.0 never run together: the upgrade renames `teams` to
  `workspaces` and drops collections 3.5 still uses. Let runs finish or cancel them, since 3.5's task runs are not
  carried over and a run cut off part way cannot resume.
- **Export the run history you need before upgrading.** 3.5's workflow runs become completed runs; their task runs
  are dropped. 5.0.0 deletes each finished run older than the Activity retention ("Activity Retention (days)" in the
  workflow-run settings, 365 by default, at least 60) with its task runs, approvals, logs and run artifacts, once an
  hour. The purge is on from the first start: its first pass runs within a minute and each pass removes up to 1000
  runs, so an upgraded install loses its runs older than a year within hours, before the setting can be changed, and
  no value keeps every run. This is by design. Keep the backup, or export `flow_workflows_activity` with
  `mongoexport`. Workflows, workflow-scoped artifacts and the audit trail are kept.
- **Check for users sharing one email.** The migration never deletes an account, so an exact duplicate email stops
  startup until it is resolved. Emails that differ only in case are left as they are and logged at ERROR, and the
  mixed-case account in such a pair stops resolving at sign-in. See "Upgrading from 3.5" in the README.
- **Three images replace 3.5's five.** `flow-service-core` (port 7700), `flow-service-dispatcher` (7702) and
  `flow-client-web` (3000), all at one product tag, replace `flow-service-workflow`, `flow-service-controller`,
  `flow-service-listener`, the 3.5 `flow-client-web` and the `flow-loader` Job. `service-core` migrates the database
  as it starts and serves nothing until it has finished: remove the loader Job and its pre-upgrade hook, start one
  replica first, and give it a startup probe that covers the migration. A 16 MB 3.5 database, about 18 000 runs,
  migrates in under a minute; replicas that start together wait on the migration lock for up to 30 minutes. The
  probes are `/health/liveness` and `/health/readiness`, which answer without a token.
- **Every task runs through a dispatcher.** 3.5's controller service ran tasks itself; in 5.0.0 the Kubernetes
  dispatcher claims them from `service-core`. Set `flow.engine.url` (`FLOW_ENGINE_URL`, for example
  `http://service-core:7700`) and `flow.engine.dispatcher.token` (a global machine token, such as the bootstrap
  token) on it, and bind its Kubernetes Role. It runs each task as a Tekton TaskRun (the default) or a plain
  Kubernetes Job.
- **Encryption keys are two pairs.** `mongo.encrypt.secret`/`mongo.encrypt.salt` now mean 3.5's pair, read once to
  decrypt 3.5's secured settings (the Slack and GitHub credentials) during the upgrade. `flow.encrypt.secret`/
  `flow.encrypt.salt` is the key 5.0.0 encrypts with, required on every install. Both default to `FLOW_ENCRYPT_SECRET`
  and `FLOW_ENCRYPT_SALT`, the names the 3.5 chart kept its pair under, so the 3.5 release's Secret works unchanged;
  back it up before uninstalling 3.5. A wrong 3.5 pair stops startup; `flow.migration.reset-unreadable-settings=true`
  blanks each value that does not decrypt instead. Secured settings are re-encrypted with AES-GCM on the first start.
- **The MongoDB address is `spring.mongodb.uri`.** 5.0.0 reads `spring.mongodb.uri` (`SPRING_MONGODB_URI`), not 3.5's
  `spring.data.mongodb.uri`. A deployment that sets only the old name gets the packaged `localhost` address and does
  not connect. Keep 3.5's `flow.mongo.collection.prefix` (the 3.5 chart's `bmrg-flow` prefix gives `flow`).
- **The Java images have no shell and run Java 25.** `flow-service-core` and `flow-service-dispatcher` run on
  Ubuntu's chiseled JRE: no shell, package manager or `keytool`. Set JVM flags in `JDK_JAVA_OPTIONS` (the
  `JAVA_OPTS` variable and the `sh -c java …` command the 3.5 chart used no longer apply), and trust an internal CA
  by mounting a truststore and naming it there (`-Djavax.net.ssl.trustStore=…`) rather than importing a certificate
  at container start. The container runs as uid 10001 in group 0. Give `service-core` at least 1 GB.
- **The webapp image has no shell either.** `flow-client-web` runs on Google's distroless Node 24 as its nonroot user
  (uid 65532). A probe that runs inside the container uses node itself; the HTTP probe is `GET /apps/flow/health`.
- **The activation code is `FLOW_ACTIVATION_CODE` (`flow.activation-code`), with no default.** 3.5's
  `boomerang.otc` default `test` is gone. An upgraded install already has users, so the code is never asked for.
  `FLOW_OTC` is still read when the new name is unset, with a deprecation warning; both set to different values
  stops the start, and a code under 32 characters stops it too.
- **Issue new tokens.** 3.5's tokens do not carry over: the upgrade drops the `tokens` collection. Every caller that
  used one, such as a CI job, a script or a webhook sender, needs a 5.0.0 token.
- **API clients and ingress move to `/api/v2`.** 3.5's `/workflow/**`, `/apis/v1/**` and listener routes are gone.
  Webhooks are `POST /api/v2/webhook`, CloudEvents `POST /api/v2/event`, and the wait-for-event callback
  `/api/v2/callback`, which the `wfe-url` context param now names. Route `/api/v2/**` from outside, route
  `/dispatcher/v1/**` only for a dispatcher outside the cluster network, and leave `/internal/v1/**` unrouted.
  Teams are workspaces throughout: `GET /api/v2/profile` returns `workspaces`, and error reasons say `WORKSPACE_`.
  Settings and global parameters are changed with `PATCH` and a JSON Merge Patch; an unknown settings key is
  `400 SETTINGS_INVALID_KEY`.
- **A submit through the API obeys the workflow's Manual switch and records `api`.** 3.5 skipped the switch for an
  execute request without a `trigger`. Now `POST …/workflow/{name}/submit` records `manual` for a person signed in
  to the webapp and `api` for any token, and both are refused with `WORKFLOWRUN_TRIGGER_DISABLED` while Manual is
  off: turn Manual on for any workflow a program starts. A body `trigger` other than `manual` or `api` is `400`.
- **Users of type `auditor` and `author` are plain users.** 3.5 gave them the admin navigation; only `admin` and
  `operator` are admins. Stored users are not changed, and Admin › Users shows both as User.
- **Teams become workspaces.** Each 3.5 team, with its members, parameters, quotas and approver groups, becomes a
  workspace; a user's own workflows move to their personal workspace and 3.5's system workflows to the `system`
  workspace. 3.5's workflow templates become starters in the `system` workspace. Each user owns their personal
  workspace (role `owner`) and edits their teams' workspaces (role `editor`), as a 3.5 team member could; 3.5 admins
  own the `system` workspace. Change a member's role on the workspace's Members tab.
- **Workflow storage is removed.** A workflow has one shared workspace per run, declared as a single `workspace`
  (size, mount path, class, access mode) instead of a `workspaces` list of two types; the volume keyed by the
  workflow is gone. A 3.5 workflow that had Workflow storage is marked on upgrade and refused at run with
  `WORKFLOW_STORAGE_REMOVED` until it is saved again. To keep files between runs, add a Download Artifact task with
  *Scope: Workflow* and *Optional* at the start of the workflow and an Upload Artifact task with *Scope: Workflow* at
  the end, both with the same name and the path you mounted before. Upgraded workflows keep their mount path
  (`/workspace/workflowrun` when none was set); new workflows mount at `/workspace`. Flow no longer manages the old
  volumes: copy out anything you need, then delete them with
  `kubectl delete pvc -n <namespace> -l boomerang.io/workspace-type=workflow`.
- **Quotas: one storage quota, and 0 is unlimited.** `max.workspace.storage` (carried from `max.workflowrun.storage`)
  replaces `max.workflow.storage` and `max.workflowrun.storage`; the per-workspace field is `maxWorkspaceStorage`.
  Kept artifacts count toward `max.artifact.storage`. 3.5 enforced a quota of 0 as a limit of zero; in 5.0.0 0 is
  unlimited and an absent quota inherits the platform default, so the upgrade drops each 3.5 `0` and that workspace
  inherits the default. 3.5's per-user quota defaults are removed.
- **Settings are rebuilt from 5.0.0's defaults, keeping 3.5's values.** 3.5's user defaults and Workflow storage
  settings are removed.
- **A CloudEvent run has a new shape.** 3.5 filled a workflow property from a CloudEvent with the property's
  `jsonPath`, or from a `properties` object in the event's data. 5.0.0 does neither: the run gets `event`, the event's
  attributes, and `data`, its data; read `$(params.data.<field>)`. Such a workflow upgrades and runs with its
  properties' defaults and no error: list them before upgrading (README, "Upgrading from 3.5") and change each. The
  trigger's filters read the event as sent (`type`, `data.tag`). `ref` still takes a workflow id; add `workspace`
  to name the workflow instead (`?workspace=<workspace>&ref=<workflow name>`, on `POST /api/v2/webhook` too).
- **`$(team.params.x)` is deprecated.** It still resolves, logs a warning, and is removed in the next major version;
  change it to `$(workspace.params.x)`.
- **Acquire Lock's `timeout` is in milliseconds.** 3.5 read it in seconds. Revision 2 of the task says so and
  defaults to `300000` (5 minutes); a workflow pinned to revision 1, or holding 3.5's default `300`, now waits 300 ms
  until the value is edited.
- **Integration secrets are required.** Set the Slack app's signing secret (`slack.signingSecret`): without it the
  Slack commands, interactivity and events routes refuse every request. Set the GitHub App's webhook secret
  (`github.webhookSecret`): without it every GitHub event is refused with `401`. Slack is now a workspace
  integration: set the Slack app's OAuth redirect URL to `<Flow API URL>/api/v2/integration/slack/auth`, add the bot
  scopes `channels:manage`, `channels:read`, `chat:write`, `commands`, `files:write`, `groups:read`, `groups:write`,
  `users:read` and `users:read.email`, and leave token rotation off. A Slack token stored by 3.5 stays plaintext
  until Slack is next linked, so link it again.
- **Catalogue tasks move off `worker-flow` to `boomerangio/task-flow:3.4.0`** (the `task` settings'
  `default.image`), as does every 3.5 revision that named `worker-flow`; an image an operator chose is kept. What
  changes for those tasks:
  - Script tasks run on Alpine 3.24 with Node 24 (3.5: Alpine 3.20, Node 20), npm 11 at `/usr/local/bin/npm`
    (`/usr/bin/npm` is gone), GNU tar, OpenSSL 3.5, curl 8.23 and bash 5.3.
  - The Artifactory download needs a destination path, relative paths resolve against `/workspace`, and the upload
    verifies TLS, so a self-signed Artifactory fails until its CA is trusted.
- **New catalogue revisions; workflows keep the revision they pin.** The upgrade adds revisions in which the Slack
  tasks take their bot token and webhook URL as password fields; the GitHub and Slack API tasks default their token
  to the integration's shared workspace parameter; the Google Sheets and Drive tasks default their key to the Google
  integration's; and SendGrid Template drops its placeholder API key. A workflow moves when its node is upgraded in
  the editor, and an upgraded node left on its default fails at admission in a workspace that has not connected the
  integration (the editor warns at save). Between them the GitHub tasks need Issues: write, Pull requests: write,
  Contents: read or write, Commit statuses: write and Actions: write on the GitHub App. The Box tasks' `privateKey`
  is a secret, and the value a workflow holds is encrypted in place.
- **Loki task logs read 5.0.0's labels.** Change the log shipper to map the task pod's labels as
  `boomerang_io_taskrun_ref` and `boomerang_io_workflow_ref` and its container as `task` or `step-task`; 3.5's
  `bmrg_*` labels match no 5.0.0 task.

### Added

- **Run control.** Pause and resume a run (`PUT …/workflowrun/{id}/pause`, `…/resume`). Retry a run in full, from
  the tasks that did not succeed, or from named tasks (`PUT …/workflowrun/{id}/retry` with `{scope: all | failed |
  nodes}`); carried-over tasks are marked Reused. Purge a run, or every run with given labels, with its task runs,
  actions, artifacts and logs (`DELETE …/workflowrun/{id}`, `workflowrun/delete` permission).
- **Task retries.** A task node can retry a failure its dispatcher reports: `retry: {max, backoff}` on its Configure
  tab, at most 10. Each superseded attempt is recorded on the task run and shown in the run's task view.
- **Finally tasks.** A task linked from End with the `finally` condition runs once the run has ended, however it
  ended, and reads the outcome as `$(context.params.workflowrun-status)`. Each has its own timeout under the
  `workflowrun` setting `finally.timeout` (5 minutes); one that fails turns a succeeded run into `failed`.
- **For-each tasks.** A task runs once per item of an array, up to 256 items, in parallel within the run; a
  run-workflow task can start one child run per item.
- **Child workflows pass values both ways.** A run-workflow node passes the params its child declares, such as an
  upstream task's result, and a waited child's results become the task's results. The parent run's params are not
  handed down, as in 3.5.
- **Concurrency limits per workflow.** `concurrency: {limit, overflow}` under Configure › Run Options parks a run
  over the limit as `waiting` until one ends (`queue`) or cancels it with reason `ConcurrencyLimit` (`skip`). The
  workspace quota keeps precedence.
- **Templates and starters.** A workflow in the `system` workspace can be a `template`, which every workspace runs
  by reference under its own name, quotas and audit, or a `starter`, which Home copies. Templates show beside a
  workspace's own workflows, read-only, through its ordinary workflow routes.
- **Secret parameters.** `secret` is a parameter type: encrypted at rest under `flow.encrypt.*`, masked on every
  read and never written to a log. On every layer a set secret reads `*****` and an unset one `null`; a write of
  `*****`, `null` or nothing keeps it, and `""` clears it.
- **Approval results.** An approval task ends with `approvalStatus`, `approvalDate`, `approvalUserName`,
  `approvalUserEmail` and `approvalComments`, read with `$(tasks.<name>.results.approvalStatus)`.
- **Locks and callbacks.** A run that ends holding an `acquirelock` lock frees it at once. A wait-for-event callback
  may carry an `eventId`, and a repeat for the same run within 7 days is acknowledged and not applied again.
- **Workspace integrations.** A workspace connects GitHub (an App installation) and Slack (the Flow Slack app), and
  Google and Microsoft by entering their credentials, on its Integrations page. GitHub and Slack can share their
  installation token with tasks as read-only workspace parameters (`github-token`, refreshed every 45 minutes,
  `github-url` and `slack-token`). A GitHub event starts the workflows whose GitHub trigger is on, filtered by event
  and repository.
- **16 catalogue tasks** on the default image: GitHub (create an issue, comment on an issue or pull request, create
  a pull request, set a commit status, dispatch a workflow, create a release, get file contents), Microsoft Teams
  (send a message, create a channel, add a member), SharePoint (upload, download and list files) and Google Drive
  (upload, download and share a file).
- **AI tasks.** `ai-prompt` sends a prompt, files or images to an OpenAI-compatible model and returns the reply;
  `ai-agent` lets the model call tools until it reaches an answer. Both run on `boomerangio/task-ai`. On the
  dispatcher, `flow.dispatcher.ai.endpoint`, `.token` and `.image-prefixes` configure them, and
  `flow.dispatcher.ai.otel.*` exports their traces.
- **Dispatchers.** A dispatcher is anything that implements the dispatcher protocol (`/dispatcher/v1`,
  `contracts/dispatcher-v1.yaml`): it claims ready tasks, heartbeats them and reports their end. Flow's Kubernetes
  dispatcher runs tasks as Tekton TaskRuns or Kubernetes Jobs, and can run in another cluster or network zone.
  `dispatcher-sdk` (`io.useboomerang:dispatcher-sdk`) builds one in Java. A ready task reaches a waiting dispatcher
  at once, about 100 to 200 ms a hop.
- **Task logs that outlive the pod.** With an artifact store, the dispatcher uploads a finished task's log (capped by
  `flow.engine.task.log.max-bytes`, 10 MiB) and `service-core` serves that copy. The Artifacts settings "Store Task
  Logs" (on) and "Task Log Retention (days)" (7) govern it. A failed task's status message carries the container's
  exit code and the tail of its log.
- **Sign-in with OpenID Connect,** beside an authenticating proxy as in 3.5. `flow.auth.oidc.issuer` and
  `flow.auth.oidc.client-id` set it at install time. `flow.security.admin-claim.name` and `.values` make the holders
  of an identity-provider claim admins on every sign-in. The `features` settings Auto-Create Users
  (`autoCreateUsers`, on) and Workspace creation by users (`workspaceCreate`, on) allow invite-only sign-up and
  admin-only workspace creation.
- **Workspace roles.** Each member is an `owner`, `editor` or `reader`. Owners add members and change roles in
  Manage › Members, and a workspace always keeps an owner (`400 WORKSPACE_LAST_OWNER`). A session's and a personal
  token's grants follow the user's current memberships on every request.
- **Machine tokens.** Global tokens, workspace-bound tokens (for a service, an agent or a workflow) and personal
  tokens, each with permissions or a role preset. `flow.security.bootstrap-token` registers a first global token at
  start, and `flow.security.tokens.<name>.*` declares more in configuration, rotating without a gap.
- **Audit trail.** Admin › Audit records who changed what, including settings, global parameters, catalogue tasks
  and schedules; a change to a secret names it, never its value. Audit retention is the `audit` setting
  `retentionDays` (at least 60).
- **Insights.** Beside 3.5's run summary: task-run breakdowns by task, type, workflow or label with mean, p50, p95
  and longest durations (`…/insights/tasks`), AI token use and cost by model, filters by run label, and the same
  across workspaces for a global grant (`GET /api/v2/insights`).
- **Status events.** `service-core` sends run and task status as CloudEvents over HTTP to the sinks in
  `flow.events.sink.*`, filtered by type, status, phase or label. Every task gets a
  `TRACEPARENT` derived from its run, so one run's tasks share one trace.
- **A versioned public API.** `/api/v2`, with its OpenAPI contract checked in as `contracts/api-v2.yaml`, task runs
  queried across runs by label (`…/taskrun/query`), and `flow-sdk` (`io.useboomerang:flow-sdk`), a Java library to
  submit, control and query runs, decide actions and read Insights. A workspace can be found by an external system's
  id (`GET /api/v2/workspace/query?externalRef=`). Labels under `boomerang.io/` are reserved for the server.
- **The webapp is rebuilt** on React 18, React Router 7 and Carbon v11, with Home (today's runs, waiting approvals,
  Get started), one Admin area (Settings, Workspaces, Users, Parameters, Tokens, Tasks, Audit) and a profile that
  lists your workspaces and roles.

### Changed

- **Runs time out on execution time.** A run's timeout is the request's, else the workflow's, else the run-duration
  quota (`max.workflowrun.duration`, 30 minutes by default, carried from 3.5's Total Workflow Duration). While every
  task in flight waits on an approval, a manual task, an event, a child run, a sleep or a lock, or the run is paused,
  the deadline moves forward; a wait is indefinite unless its task declares a timeout. A run's duration is shown as
  active time in Activity and on the run page.
- **Task logs.** A running task's log streams through `service-core` from the dispatcher that holds it (set
  `flow.dispatcher.logs.url` on the dispatcher), and the stored copy is served once the task ends.
- **Status events leave over HTTP.** 3.5 published CloudEvents to NATS JetStream (`eventing.*`); 5.0.0 posts them to
  `flow.events.sink.*` destinations.
- **Every API error is a `RestErrorResponse`** (`timestamp`, `code`, `reason`, `message`, `status`), including a
  missing permission (`403 PERMISSION_DENIED`), a framework error such as `405`, and an unhandled `500`, whose
  message is fixed and whose exception stays in the server log.
- **Task references are slugs.** A task run's `taskRef` on the API and the status events is `<slug>` for a global
  task and `<workspace>/<slug>` for a workspace task.
- **The JVM exits when it runs out of heap.** Both Java images start with `-XX:+ExitOnOutOfMemoryError`, so a
  platform restart policy recovers the process instead of leaving `service-core` running but unreachable.

### Removed

- **3.5 images:** `flow-service-workflow`, `flow-service-controller`, `flow-service-listener`, `flow-loader` and the
  `worker-flow` task image.
- **3.5 properties no longer read:** `spring.data.mongodb.uri`, `JAVA_OPTS`, `boomerang.*` (including
  `boomerang.otc`, still read as `FLOW_OTC`, and `boomerang.authorization.*`), `controller.*`, `core.*`,
  `kube.worker.*`, `eventing.*`, `opentracing.jaeger.*`, `flow.externalUrl.*`, `flow.services.*`,
  `swagger.enable`, `flow.apps.url`, and the quota properties `max.workflow.duration`, `max.workflow.count`,
  `max.workflow.execution.monthly`, `max.workflow.execution.time` and `max.concurrent.workflows`: quotas are the
  `quotas` settings.
- **3.5 routes:** `/workflow/**`, `/apis/v1/**`, `/internal/**` and the listener's `/listener/**`. The API
  documentation moves from `/apis/docs` to `/api/docs`.
- **Workflow storage,** its quota (`max.workflow.storage`) and per-user quota defaults.
- **3.5 tokens,** which are not migrated.

### Fixed

- **The Artifactory tasks sign in with a username and password.** They sent Basic auth as `user:password` without
  encoding it, which Artifactory refuses; the header is now Base64-encoded.
- **A workflow started through the API without a `trigger` obeys its Manual switch** (see Upgrading).
- **The Slack routes check Slack's request signature,** so only Slack can call the commands, interactivity and
  events routes (see Upgrading).

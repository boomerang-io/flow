# API contract

`service-core` serves three groups of routes, split by who calls them: the public API under `/api/v2`, the sign-in routes the webapp's server calls under `/internal/v1`, and the dispatcher wire under
`/dispatcher/v1`. This document states the rules every public route follows; route-level detail is the OpenAPI document springdoc serves at `/api/docs/spec`
(`service-core/src/main/resources/application.properties:205-207`), which `OpenApiDocumentTest` pins to cover the public API and the dispatcher wire and leave out `/internal/`
(`service-core/src/test/java/io/boomerang/core/config/OpenApiDocumentTest.java:49-56`). The public API alone is the `api-v2` group at `/api/docs/spec/api-v2`
(`core/config/OpenApiConfiguration.java:139-157`), checked in as `contracts/api-v2.yaml` with every object's keys sorted; `ApiContractTest` fails when the served document and the file differ and
rewrites the file under `-Dcontract.update=true` (`service-core/src/test/java/io/boomerang/core/config/ApiContractTest.java:50-62,83-98`). `contracts/dispatcher-v1.yaml` and `DispatcherContractTest`
do the same for the dispatcher wire. The document requires a bearer token or the `x-access-token` header on every route; the GitHub callback and the four Slack routes, which verify the request
themselves, declare none, and `/webhook`, `/event` and `/callback` also take the `access_token` query parameter (`core/config/OpenApiConfiguration.java:228-260`, "Authentication: how a caller becomes
a `Token`" in `authorization.md`). Each operation states the permission its handler is checked against, read from its `@AuthCriteria` or `@AuthExempt` by the rule the authorization manager enforces:
an `x-flow-permission` extension, `{permission, tokens}` or `{exempt}` with the reason, and a closing sentence in its description (`core/config/OpenApiConfiguration.java:161-185`, `core/security/AuthCriteriaAuthorizationManager.java:67-93`). Every operation has its own `operationId`, a verb and the resource (`queryWorkflowRuns`, `getWorkspace`), named on the handler where the method name does not say it; `ApiContractTest` fails on springdoc's numbered fallback such as `query_8` (`ApiContractTest.java:104-119`).

## URL shape

A resource a workspace owns sits under `/api/v2/workspace/{workspace}/...`; everything else is global. Controller
paths are relative to `service-core/src/main/java/io/boomerang/`.

| Scope | Prefix | Controllers |
| --- | --- | --- |
| Workspace-scoped | `/api/v2/workspace/{workspace}/{workflow,workflowrun,taskrun,task,action,schedule,insights}` | `workflow/WorkspaceWorkflowControllerV2.java:39`, `workflow/WorkspaceWorkflowRunControllerV2.java:35`, `workflow/WorkspaceTaskRunControllerV2.java:24`, `workflow/WorkspaceTaskControllerV2.java:33`, `workflow/WorkspaceActionControllerV2.java:35`, `schedule/WorkspaceScheduleControllerV2.java:36`, `workflow/WorkspaceInsightsControllerV2.java:30` |
| Artifacts | `/api/v2/workspace/{workspace}/workflowrun/{id}/artifacts[/{name}]`, `/api/v2/workspace/{workspace}/artifacts[/{artifactId}]` | `workflow/WorkspaceArtifactControllerV2.java:42` |
| Workspaces | `/api/v2/workspace[/{workspace}]` | `workspace/WorkspaceControllerV2.java:37` |
| Global | `/api/v2/{user,profile,token,task,taskrun,parameters,insights,integration,webhook,event,callback}` | `core/UserControllerV2.java:31`, `workspace/UserWorkspaceControllerV2.java:31`, `workspace/ProfileControllerV2.java:35`, `core/TokenControllerV2.java:35`, `workflow/TaskControllerV2.java:33`, `workflow/TaskRunControllerV2.java:24`, `workflow/ParameterControllerV2.java:29`, `integrations/IntegrationControllerV2.java:51`, `event/WebhookEventControllerV2.java:34` |
| System | `/api/v2/{settings,activate,context,features,navigation}`, `/api/v2/system/outbox`, `/api/v2/audit` | `core/SystemControllerV2.java:38`, `event/OutboxControllerV2.java:31`, `core/audit/AuditControllerV2.java:46` |
| Dispatcher | `/dispatcher/v1` | `dispatcher/DispatcherControllerV1.java:54` |
| Webapp server only | `/internal/v1/auth/{config,exchange,logout}` | `core/InternalAuthControllerV1.java:35` |

The prefix says who the caller is, so whatever fronts `service-core` can route by it. A front door SHOULD route
`/api/v2/**` only, and `/dispatcher/v1/**` as well when a dispatcher runs outside core's network. `/internal/v1/**`
MUST NOT be routed from outside: only the webapp's server calls it, at `CORE_SERVICE_INTERNAL_ORIGIN`
(`client-web/src/Config/serverFetch.ts:34-36`, `client-web/src/Config/servicesConfig.ts:27,63-65`), and springdoc leaves it out (`service-core/src/main/resources/application.properties:207`). The prefix is a routing boundary, not a
credential: a caller that reaches `service-core` directly reaches it too.

`{workspace}` is the workspace's name; there is no `/api/v2/team/...` route. With `flow.workspace.single=true` a
workspace-scoped request naming any workspace but `system` is `404 WORKSPACE_INVALID_REF` (`core/security/SingleWorkspaceInterceptor.java:37-44`). `GET /api/v2/taskrun/{id}/log` has no workspace segment:
it is authorised through the owning run, serves the origin task run's log for a task a scoped retry carried over,
and is scrubbed of the run's secret values (`workflow/WorkflowRunService.java:586-622`).

## Routes with their own rules

**Artifacts.** A run's list holds its artifacts, expired ones and the workflow-scoped ones it uploaded included, but no
task log or unfinished upload (`workflow/ArtifactService.java:476-483`). A download streams through `service-core`; an
expired or replaced artifact is `410 ARTIFACT_EXPIRED` (`:648-659`), and with no artifact store `503
ARTIFACTS_NOT_CONFIGURED` (`:632-638`). The workspace list is largest first and takes `statuses` (default `available`),
`scope` (`run`, `workflow`, or `log` for task logs, listed only when asked) and `workflow`, a name (`:508-547`).

**Workflow templates** are the system workspace's workflows of `type` `template` or `starter`, authored through its ordinary workflow routes; that `type` on a body to any other workspace is `400
WORKFLOW_TEMPLATE_SYSTEM_ONLY` (`workflow/WorkflowService.java:2475-2483`). There are no template routes: a workspace sees them through its own workflow routes. `GET …/workflow/query` lists, beside
its workflows, the active templates and starters whose name none of its workflows carries and that none links, each with its `type`; `types=workflow|template|starter` narrows, a workflow stored with no type counting as `workflow` (`:379-386,1546,2253-2278`); one created without a type stores `workflow` (`:1638`).
The reads by name (get, compose, changelog, export, available parameters) answer the template or starter of a name the workspace has no workflow of (`:295-306,2224-2235`); a workflow of the same name
hides it. A starter is copied, its body with `type` `workflow` posted to `POST …/workflow`; its submit is `400 WORKFLOW_TEMPLATE_INVALID_REF` (`:2237-2247`). A template runs through a link, a workflow
of the workspace with `templateRef: {name, version}` that shows the template's tasks and params read-only under its own name, labels and triggers and runs the template's pinned or latest revision as
its own run (`:2042-2056`). The first use makes the link: `POST …/workflow/{template}/submit` creates it under the template's name and runs it (`:724-729,2299-2324`); `PUT …/workflow` naming a
template creates it and applies display name, description, icon, labels and triggers, tasks and params ignored (`:655-667`). A missing template or version is `400 WORKFLOW_TEMPLATE_INVALID_REF`;
deleting a template a link names is `409 WORKFLOW_TEMPLATE_IN_USE` (`:847-850`). `PATCH …/workflow/{name}` with `{type}` changes what a workflow is, its revision untouched: a shared kind is the system
workspace's alone, a link is refused one, and a template that workspaces still link stays one, `409 WORKFLOW_TEMPLATE_IN_USE` (`:857-889`).

**Workspaces.** `externalRef` is an embedding product's own id for the record a workspace mirrors: set once at create, never patched (a `PATCH`
carrying one is ignored), read by every reader. `GET /api/v2/workspace/query?externalRef=` answers the one carrying it within the caller's access; a
create naming one already carried is `409 WORKSPACE_EXTERNAL_REF_TAKEN` with that workspace's name, whatever name it asks for, so a retried
create reads it instead; the reference is checked before the name (`workspace/WorkspaceService.java:178-193,305-306,476-479`; unique sparse index `external_ref`).

**Task catalogue.** `/api/v2/task` has the same operations as a workspace's catalogue, `DELETE` included
(`workflow/TaskControllerV2.java:274`); a delete is `409 TASK_DELETE_IN_USE` while a pending, queued or running task run references the task (`workflow/TaskService.java:989-996`).

**Run purge.** `DELETE /api/v2/workspace/{workspace}/workflowrun/{id}` removes a run with everything it owns and its
lineage, cancelling one in flight first (`workflow/WorkspaceWorkflowRunControllerV2.java:292`; what goes, and the wait
for a dispatcher's terminate poll, are in `data-model.md`, "Artifacts" and decision 0045). `DELETE
…/workflowrun?labels=k%3Dv` does the same for the workspace's runs carrying every label, oldest first, a page of 50 a
call, runs the sweep already holds left out (`workflow/WorkflowRunService.java:487-524`); no `labels` is `400 QUERY_INVALID_FILTERS`. Both answer `{purged,
scheduled, remaining}` (`common/model/WorkflowRunPurge.java`): runs removed now, runs left to the sweep, and matches
left for the next call. A run that does not exist counts in neither, so a repeated call answers the same; one in
another workspace is `404 WORKFLOWRUN_INVALID_REFERENCE`. Both need `workflowrun/delete` (the owner and admin roles;
`editor` and `operator` have no delete, `seed/roles.json:16-28`) and are audited `DESTRUCTIVE` with the id or the labels, never a value.

**Integrations.** `POST /api/v2/integration/{github|slack|google|microsoft}/share` with `{workspace, shared}`, plus a form integration's `values` by field
name, shares, or stops sharing, those credentials with the workspace's tasks as read-only parameters (`parameters.md`, "Parameters an integration
manages"); it needs `integration/write`, and another name is `404 INTEGRATION_INVALID_REF` (`integrations/IntegrationControllerV2.java:303-332`). A user's
parameter of a field's name is `409 INTEGRATION_PARAM_EXISTS` and workspace parameters off `400 INTEGRATION_PARAMS_DISABLED`; unknown, missing or misplaced
`values` are `400` (1910-1912); GitHub issuing no token is `502 INTEGRATION_TOKEN_FAILED` and an expiring Slack token `400 INTEGRATION_TOKEN_ROTATES`
(`integrations/IntegrationService.java:137-187`). `GET /api/v2/integration?workspace=` gives each template's `kind`, `fields` (a linked form's with its
values, a secret as `*****`), `shared` once linked, and Slack's signed authorize URL (`integrations/IntegrationService.java:86-109`,
`integrations/SlackService.java:705-722`). Slack's redirect links its team to that workspace, one per workspace (`409 INTEGRATION_ALREADY_LINKED`);
`POST /api/v2/integration/slack/unlink` disconnects one workspace (`integrations/SlackService.java:521-649`, `integrations/IntegrationControllerV2.java:127-142`).
`POST /api/v2/integration/github/unlink` with `{workspace, ref}` takes the record id (`GET`'s `ref`) or installation id: an unreachable workspace is `404
WORKSPACE_INVALID_REF`, one not owning it `403 INTEGRATION_UNAUTHORIZED`, an unknown installation `404 INTEGRATION_INVALID_REF`; it is audited and removes
the record, link and parameters (`integrations/IntegrationControllerV2.java:286-301`, `integrations/GitHubService.java:390-419`).

**Event outbox.** `GET /api/v2/system/outbox` lists outbound event rows (`status`, default `dead`; `limit`, default
100). `PUT /api/v2/system/outbox/replay` requeues rows with a fresh retry budget, the named `ids` whatever their status
or else every row in `status` (default `dead`), either narrowed by `olderThan` (epoch milliseconds), and answers
`{"replayed": n}` (`event/OutboxControllerV2.java:44-97`, `event/OutboxService.java:58-82`). A row that failed delivery
carries `lastError` (exception type and message, at most 1,024 characters) and a dead row `deadAt`; a replay clears both
(`event/OutboxDispatcher.java:165-171`). Both routes need a `global` token with `system` permission.

**Audit trail.** `GET /api/v2/audit` returns a `Page<AuditEvent>`, newest first; `GET /api/v2/audit/stats` counts
the same filters and window by outcome and adds the configured `captureEnabled`, `level` and `retentionDays`
(`core/audit/AuditControllerV2.java:93,168-202`). Both read every workspace, so they need a global `system/read`
grant; a workspace-scoped one is `PERMISSION_DENIED` (`:213-231`).

| Parameter | Accepts | Default |
| --- | --- | --- |
| `actor` | any part of an actor id or display name, ignoring case | none |
| `action`, `outcome`, `level` | comma-separated `AuditAction` / `AuditOutcome` / `AuditLevel` names | none (all) |
| `resourceType`, `workspaceId` | comma-separated resource types (`workflow`, `token`, …); workspace ids | none |
| `resourceId` | a single resource id | none |
| `from`, `to` | ISO-8601 instants, `to` exclusive | `from` = 30 days ago; `to` = no bound |
| `page`, `limit`, `order` (listing only) | 0-based page; page size, at most 100; `ASC`/`DESC` on `time` | `0`, `25`, `DESC` |

The default `from` keeps the page and its count on the `time` index (`core/audit/AuditControllerV2.java:52-59`).
An event's `payload` holds request discriminators, never request or response content, and a write that changed a
secret adds `payload.detail.secretsChanged` or `secretsCleared`, naming secrets, never values
(`core/audit/AuditEvent.java:8-17`, `core/audit/SecretChangeHolder.java:15-24`). A write is level `WRITE` unless
marked `DESTRUCTIVE` (`core/audit/Audited.java:45`), as deleting a workspace, a parameter, a workflow, a schedule, an
artifact or a run and revoking a token are; deleting a catalogue task is not audited. The engine records each run's
admission (`CREATE`) and completion (`UPDATE`) (`core/audit/WorkflowRunAuditBridge.java:64-72`); the monthly run quota counts the `CREATE` events (`core/audit/AuditQueryService.java:191-208`).

## Error response

An error the application raises is a `RestErrorResponse`
(`service-core/src/main/java/io/boomerang/common/error/RestErrorResponse.java:7-12`), built by
`core/RestExceptionHandler.java:50-72` for a `BoomerangException`, `:79-91` for a failed `@Valid` body (first field
error only), `:98-116` for an unreadable parameter `type`, `:143-171` for an authentication failure, which also
sends `WWW-Authenticate: Bearer`, `:177-182` for a missing permission, `:188-198` for any other exception, and `:206-221`
for a framework error.

```json
{ "timestamp": "2026-09-01T10:00:00.000+00:00", "code": 1001, "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(labels) have been provided.", "status": "400 BAD_REQUEST" }
```

| Field | Source |
| --- | --- |
| `code`, `reason`, HTTP status | The `BoomerangError` constant (`common/error/BoomerangError.java:22-144`). Ranges: below 1000 mirror HTTP, 10xx generic, 11xx workspace, 12xx workflow, 13xx workflow run, 14xx task, 15xx task run, 16xx action, 17xx schedule, 18xx parameter, 19xx integration, 20xx artifact (`:17-20,119,133`). Codes 1405 and 1802 each carry two reasons (`:91-94,113-116`), so a client keys on `reason` |
| `message` | `service-core/src/main/resources/messages.properties`, keyed by `reason`, with `{0}` arguments (`:14`); an explicit exception message wins (`core/RestExceptionHandler.java:56-65`) |
| `cause` | Set only when `flow.error.include-cause=true` (default `false`) and the exception has a cause (`core/RestExceptionHandler.java:44-45,67-69`) |

The status never changes to fit the body. A missing permission at the endpoint is `403` with `PERMISSION_DENIED`'s code, reason and message (`authorization.md`, "Layer 1"); `PERMISSION_DENIED` raised by a service carries code 403 but answers HTTP 401 (`common/error/BoomerangError.java:25`).
A framework error (an unsupported method or media type, a missing parameter, an unreadable body, an unknown route) keeps its status and headers, a `405` its `Allow`, with the HTTP status as `code` and `reason` (`405`, `METHOD_NOT_ALLOWED`) and Spring's detail as `message`. Anything else is `500 INTERNAL_SERVER_ERROR` (code 500) with a fixed message; the exception is logged, never sent. A failure inside a servlet filter, before the request reaches Spring MVC, still answers Spring Boot's default error body.

A `*_INVALID_REF*` reason answers `404`: the resource the path addresses does not exist, or the caller cannot reach
it. Blank, missing or unusable input is `400` under the matching `*_INVALID_REQ` reason (`common/error/BoomerangError.java:14-16`).
A reference inside a body that fails to resolve stays `400`, because the route exists and the payload is wrong: a node
naming a missing task is `WORKFLOW_INVALID_TASK_REF`, one with none `WORKFLOW_MISSING_TASK_REF`, at save and at submit
(`workflow/WorkflowService.java:1434-1497,2148`). `TASK_INVALID_NAME` (1403) is a name that breaks the slug rules; a lookup,
changelog or delete naming a task that does not exist or is out of reach is `TASK_INVALID_REFERENCE` (1401, `404`; `workflow/TaskService.java:175-185,541-547,573-586`).

## Pagination and filters

List routes take `page` (0-based), `limit`, `order` (`ASC`/`DESC`) and, where sortable, `sort` (a field name) as
separate query parameters, and return a Spring Data `Page<T>`. On the run, task run, workflow, task, template,
workspace, user and token lists `limit` is optional, and leaving it out returns every match in sort order
(`common/util/PagingUtil.java:25-34`); the action and schedule lists default to 10
(`workflow/WorkspaceActionControllerV2.java:132`, `schedule/WorkspaceScheduleControllerV2.java:123`). The run list
sorts on `creationDate`, `ASC` by default (`workflow/WorkspaceWorkflowRunControllerV2.java:108-119`); the workspace
list on `sort` (default `name`) and `order` (default `DESC`) (`workspace/WorkspaceControllerV2.java:140-152`). The
run, task run, action and token lists take `fromDate`/`toDate` in epoch milliseconds.

List routes build their filters through one builder (`common/util/QueryCriteria.java`), so a filter means the same
on every route that takes it, and no filter matches everything (`:118-123`):

| Filter | Meaning |
| --- | --- |
| `labels` | URL-encoded `key=value` entries, comma-separated, all of which must match. Each entry is split once, on its first `=`, so `a%3Db%3Dc` matches the value `b=c`; a dot in a key matches its stored form, `#`. An entry without a key or a value is `400 QUERY_INVALID_FILTERS` (`:41-57`) |
| `fromDate`, `toDate` | Half-open: `fromDate` included, `toDate` excluded (`:24-33`) |
| `statuses`, `phase`, `types` | Read ignoring case; a value that names no constant is `400 QUERY_INVALID_FILTERS` (`:71-80,101-111`). On a field whose absent value reads as a constant, that constant also matches no stored value (`:82-98`) |
| `search` | Free text, ignoring case and taken literally (regex characters are quoted). The workspace list matches the start of `name` or `displayName` (`workspace/WorkspaceService.java:480-490`); the user list matches any part of `name`, `displayName` or `email` (`core/UserService.java:423,450-457`) |

The task and workflow `/{name}/changelog` routes are not paged: they return every version in ascending order, so the
last entry is the latest (`workflow/TaskControllerV2.java:233`, `workflow/WorkspaceTaskControllerV2.java:299`,
`workflow/WorkspaceWorkflowControllerV2.java:214`; sorted by `workflow/repository/TaskRevisionRepository.java:13` and
`workflow/repository/WorkflowRevisionRepository.java:16`). A version's `author` is the server's record of who made it, a user's display name or a token's name, never the request's (`workflow/TaskService.java:457`, `workflow/WorkflowService.java:1675-1678`).
`GET /api/v2/profile` adds `workspaceMemberCount`, the people in any of the caller's workspaces counted once (`workspace/ProfileControllerV2.java:69`). `PATCH /api/v2/profile` and `PATCH /api/v2/user/{userId}` leave a field the body omits as it is; a blank `displayName` clears it, so the user reads by `name` again (`core/UserService.java:485-489`).

## Public run models

`WorkflowRun` and `TaskRun` (`service-core/src/main/java/io/boomerang/common/model/`) are the public shapes; the
entities are separate classes and MUST NOT be returned from a controller. The dispatcher wire has its own models,
`DispatchWorkflowRun` and `DispatchTaskRun` (`dispatcher-contract.md`, "What a poll hands out"). Both public models are
`@JsonInclude(NON_NULL)`: a null field is absent (`common/model/WorkflowRun.java:38`, `common/model/TaskRun.java:27`).
In the contract, a run model's `required` list is what every response carries, and a property typed `[<type>, "null"]` may be sent as `null`: `RunResult.description` and every `TaskRunSpec` field but `timeout`, which those two classes write when unset. `TaskRunSpec` also passes undeclared properties through (`additionalProperties: true`). The lists apply to the `api-v2` group only (`core/config/OpenApiConfiguration.java:73-134,187-223`); `RunModelRequiredFieldsTest` checks them against a run just submitted, its task runs, both queried, and a superseded attempt. A task run's `name` and `type` and a run's `workflowVersion` are not required: saving a workflow does not refuse a task without a name or type, and a run upgraded from v3 carries a version only when the v3 record did (`workflow/WorkflowService.java:1705-1724`, `migration/_0012__V3MigrateRuns.java:211-214`).

| Rule | Where |
| --- | --- |
| No execution state is serialised. Entity-only on a workflow run: `claim`, `timeoutAt`, `pauseRequestedAt`, `waitingSince`, `retryCount`, `statusOverride`; on a task run: `claim`, `timeoutAt`, `retry`, `waitUntil`, `foreach`, `dependencies`, `preApprovedStatus`, `decisionValue` | `common/entity/WorkflowRunEntity.java:40,53,57,66,77,88`; `common/entity/TaskRunEntity.java:52-54,72,77,81,85,94`; pinned by `service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:56-91` |
| Lineage is public: `trigger` (on `POST …/workflow/{name}/submit` the caller's, never the body's: `manual` for a person signed in to the webapp, a `session` token, and `api` for any other token or the security-off admin, both refused `WORKFLOWRUN_TRIGGER_DISABLED` while the Manual switch is off, and a body naming any other trigger `400 WORKFLOWRUN_INVALID_TRIGGER`; schedules, webhooks, events, GitHub, child runs and retries stamp `schedule`, `webhook`, `event`, `github`, `task` and `retry`; `engine` is on runs stored before, and on an internal submit that names none), `initiatedByRef` (what started the run: the schedule, the origin run, the submitting task run, or the user or token that submitted it, stamped by the server), the derived `initiatedByWorkflowRunRef` and `initiatedByName` (a user's display name or a token's name, one lookup per page), and on a scoped retry `retryFrom{scope, nodes}` (workflow run) and `reusedFromRef` on each carried-over task run | `common/model/WorkflowRun.java:67-77`; `workflow/WorkflowService.java:687-704,1085-1115,2200-2212`; `workflow/WorkflowRunService.java:1367-1374,1381-1396`; `common/model/TaskRun.java:60-62` |
| `PUT …/workflowrun/{id}/retry` takes an optional body `{scope: all \| failed \| nodes, nodes: [names], params: [...], labels: {...}}`; no body is `all` | `common/model/WorkflowRunRetryRequest.java:11-24`; `workflow/WorkspaceWorkflowRunControllerV2.java:423-448` |
| A task reference reads as the task's slug form, `<slug>` for a global task and `<workspace>/<slug>` for a workspace task: `taskRef` on task runs, workflow nodes, workflow template tasks, insight task rows and `full` status events, translated in one batch per response. A save naming another workspace's task is `WORKFLOW_INVALID_TASK_REF`. The stored value is the task's id | `workflow/TaskService.java:620-657,665-693`; `workflow/WorkflowRunService.java:181-193`; `workflow/WorkflowService.java:1372-1385,1447-1469`; `workflow/WorkflowRunInsightService.java:341-347`; `event/EventSinkService.java:72-78` |
| A task a dispatcher has claimed shows `attempt`: 1, plus one per requeue for any cause, so a requeued task waiting out its backoff already shows the next one. An approval, a wait or a reused task has none. `attempts` lists the superseded attempts, oldest first, each with `number`, `claimedAt`, `endedAt`, `duration`, `status`, `statusReason`, `statusMessage` and `dispatcherRef`; the task run's own fields are the current attempt | `common/model/TaskRun.java:60-67`, `:75-80`; `common/model/RunAttempt.java:20-27` |
| A workflow run carries `statusReason` beside `statusMessage`, as a task run does: `ConcurrencyLimit` on a run skipped over its workflow's limit, `WorkflowInvalid`, `DeadlineExceeded`, `FinallyFailed` on a succeeded run whose finally task failed; absent when the run ended for no cause of its own | `common/model/WorkflowRun.java:59`; `common/model/StatusReason.java:34-36` |
| Pause is the derived boolean `paused`, never the timestamp | `common/model/WorkflowRun.java:53-55` |
| `status` (`notstarted, ready, running, waiting, succeeded, failed, invalid, skipped, cancelled, timedout`) is the external field | `common/enums/RunStatus.java:6-16` |
| **Exception:** `phase` (`queued, pending, running, completed`) is serialised on both models; the webapp reads it on both and the list routes filter on it. `queued` is the only position `status` cannot express, and a terminal `status` with `phase` `running` is a run whose graph has ended while its finally tasks run (`execution-model.md`, "Finally tasks") | `common/model/WorkflowRun.java:50`; `common/model/TaskRun.java:44`; read at `client-web/src/Features/WorkflowRun/RunHeader/RunHeader.tsx:111-112` and `client-web/src/Features/WorkflowRun/TaskRunList/TaskRunItem/RunTaskItem.tsx:252`; tripwire `PublicRunModelSerialisationTest.phaseIsStillSerialisedOnThePublicModels` |

A workflow carries `concurrency: {limit, overflow}` (`queue` or `skip`) and a task node `retry: {max, backoff: {type, initial, max}}`
(`fixed` or `exponential`, seconds), both on the YAML and JSON forms and on the canvas the editor saves
(`common/model/WorkflowConcurrency.java:18-19`, `common/model/WorkflowTaskRetry.java:17-18`); a `limit` below 1, a `max` outside 0 to 10 or a
backoff wait below 1 is `400 WORKFLOW_INVALID_REQ` (`workflow/WorkflowService.java:537-570`).

A for-each task appears in a run's task list as one parent `TaskRun` under the task's name plus one item `TaskRun`
per element, named `<name>[<index>]` and carrying `parentRef` (the parent's `id`) and `index` (from 0), which every
other task run lacks (`common/model/TaskRun.java:54-56`). Items keep their own `status`, `statusReason` and logs;
the parent's `foreach` setting stays on the entity. A parent that fails for a for-each cause has `statusReason`
`ItemFailed`, `ForeachItemsInvalid`, `ForeachTooManyItems`, or `ResultsTooLarge` when its combined results exceed
MongoDB's 16 MB document limit (`common/model/StatusReason.java:22,45-47`, `engine/TaskExecutionService.java:828-852`).

`GET …/workspace/{workspace}/taskrun/query` (`workflow/WorkspaceTaskRunControllerV2.java:34`) pages task runs across
runs with the run query's filters, applied to the task run, plus `tasks`: node names, each matching its for-each
items too, and `foreach`: `true` keeps for-each parents only, `false` leaves them out, absent lists both
(`workflow/WorkflowRunService.java:333-392`, the parent being the task run carrying the foreach setting, `:376-377`). It
needs `labels` or `workflowruns`, the two ways task runs are indexed (`migration/_0021__Indexes.java:63,75`), or it is
`400 QUERY_INVALID_FILTERS` (`workflow/WorkflowRunService.java:354-358`). A task run carries its run's labels, and a
run-workflow child its parent's (`engine/DAGUtility.java:286-292`, `engine/TaskExecutionService.java:1370-1381`), so one
label finds a run's task runs and its children's. A row is the public `TaskRun` without `params`, `results`, `spec`,
`annotations` or `statusMessage`, the parts that can carry a secret value (`workflow/WorkflowRunService.java:380-382,425-434`);
a for-each parent's row carries `foreach: true` and never the setting's items (`common/model/TaskRun.java:59`). Rows sort by creation, then id, so pages stay stable (`:353`).

## Insights

Three reads under `/api/v2/workspace/{workspace}/insights` (`workflow/WorkspaceInsightsControllerV2.java:45,84,116`) are computed from the runs and task runs the workspace still holds, so a deleted
workflow's runs are not counted; the monthly quotas read the audit trail instead (`workflow/WorkflowRunInsightService.java:70-73`). The period is `[fromDate, toDate)` in epoch milliseconds, default
the last 90 days (`workflow/WorkspaceInsightsControllerV2.java:34`); `labels` is the list filter above. Durations and waits are milliseconds; a percentile over no values is `0`
(`workflow/WorkflowRunInsightService.java:830-839`). The same summary and task breakdown read across workspaces at `GET /api/v2/insights` and `GET /api/v2/insights/tasks` with `workspaces` (names;
absent means every workspace, one that does not exist contributes nothing), and `GET /api/v2/insights/taskruns?workspaces&fromDate&toDate&labels&statuses&phases&tasks&foreach&limit&page` lists task
runs newest first, each row with its `workspace`, identity and lifecycle, never params, results or spec; `labels` is required, as the workspace's task-run query requires a label or a run
(`workflow/InsightsControllerV2.java:54,88,142`, `workflow/WorkflowRunInsightService.java:137,250,357-467`). The three admit `global`, `user` and `session` tokens and then insist the matching grant be `global`, as the audit
routes do; a workspace-scoped grant is `403 PERMISSION_DENIED` (`workflow/InsightsControllerV2.java:81`, `core/security/IdentityService.java:60-71`).

| Read | Returns |
| --- | --- |
| `GET …/insights?fromDate&toDate&workflows&labels&statuses&triggers` | `WorkflowRunInsightSummary` (`:116-178`): `totals` and `previous` (the same-length period before `from`) with counts by outcome, `successRate` (succeeded over runs that ended succeeded, failed, timed out or invalid; `null` when none did), p50 and p95 duration over succeeded runs, `maxDuration` over completed runs, p50 and p95 queue wait (start minus creation), `byTrigger`; `daily`, one entry per UTC day, empty days included; `workflows`, one row per workflow with the same counts, p5/p50/p95, the newest run's timeout, the last failure and its run, and the last seven days, worst success rate first; `usage`. `workflows` takes names or ids |
| `GET …/insights/workflow/{workflow}?fromDate&toDate&labels` | `WorkflowRunInsightWorkflowDetail` (`:179-221`): per task name (start and end excluded) run and failure counts and p50/p95 over succeeded task runs, with `totalTokens` and `cost` on a row with `ai` usage; queue-wait percentiles; `retriedRuns`; up to ten `failures` grouped by terminal status, first unsuccessful task and its status message or reason, each with its count and newest run; `usage`. `404 WORKFLOW_INVALID_REFERENCE` for a workflow the workspace does not hold |
| `GET …/insights/tasks?fromDate&toDate&workflows&labels&tasks&types&groupBy&bucket&spanBy&slowest` | `WorkflowRunInsightTasks` (`:232-338`): the completed, not skipped task runs of the period's runs (a for-each task's items, under its name), one row per `groupBy` value (`task`, `type`, `workflow` or `label:<key>`, a task run label such as `boomerang.io/model`) and, with `bucket`, per UTC hour, day or month of the start. Each row: task runs, succeeded, failed (failed, timed out, invalid), cancelled, mean, p50, p95 and max duration over succeeded ones, and `usage`'s sums for its `ai` task runs. `spanBy=label:<key>` adds the count and p50/p95/max of each run-label value's first start to last end. `slowest` (up to 100) lists the slowest task runs. Rows are by bucket, then most task runs; past 1,000, `truncated`. An unknown `groupBy`, `bucket` or `spanBy`, or a `slowest` over 100, is `400 QUERY_INVALID_FILTERS` (`:281-283,1130-1146`) |

`usage` (`common/model/WorkflowRunInsightUsage.java`) sums what the counted runs' `ai` task runs reported:
`aiTaskRuns`, `promptTokens`, `completionTokens`, `totalTokens`, `reasoningTokens`, `cachedPromptTokens` and `cost`,
and the same per `model` result in `byModel`, most tokens first (`workflow/WorkflowRunInsightService.java:645-706`). An
empty or non-numeric result adds nothing, so `cost` is `null` when no task reported one (`:608-633`). A for-each parent,
which holds its items' results as arrays, is left out and its items counted under its name. Every read projects only the fields it needs, and the task view streams one small document per task run (`:570-605`).

## YAML content negotiation

Task definitions are also served and accepted as `application/x-yaml`, by `Accept` and `Content-Type` on the JSON
paths (`workflow/TaskControllerV2.java:66,160,208,253`; `workflow/WorkspaceTaskControllerV2.java:75,195,263,327`),
through a Jackson `YAMLMapper` that writes multi-line strings such as `spec.script` as `|` blocks
(`workflow/config/YamlJacksonHttpMessageConverter.java:11-15`); with no `Accept` header JSON wins
(`workflow/config/YamlConfiguration.java:32-35`). The YAML is a Tekton task whose `boomerang.io/params` annotation
carries each param's other fields, so a read applied back unchanged leaves the task as it was (`workflow/tekton/TektonConverter.java:33-49`).

## Webhook and event endpoints

### Events in

Every route here needs `webhook/action` permission and accepts `session`, `user`, `key` and `global` tokens
(`event/WebhookEventControllerV2.java:81-84`). A rejected request creates no run.

| Route | Body | Workflow resolution |
| --- | --- | --- |
| `POST /api/v2/webhook` (`application/json`) | Raw JSON, passed to the run as its `data` param in plain maps and lists; GitHub (`X-GitHub-Event`, below) and Slack (`x-slack-signature`) payloads branch on their headers | `?ref=`, a workflow id, or with `?workspace=` the workflow's name in that workspace; a body with neither a known header nor `ref` is `400`, and a workflow the caller cannot reach `WORKFLOW_INVALID_REFERENCE` (`event/WebhookEventControllerV2.java:86-145`; `event/WebhookEventService.java:100-130,210-218`) |
| `POST /api/v2/event` | CloudEvents 1.0 in structured mode (`application/cloudevents+json`, attributes in the body) or binary mode (attributes in `ce-*` headers, the body its data), as the `Content-Type` says; a request that is neither is `WORKFLOW_INVALID_REQ` | `?ref=`, a workflow id, or with `?workspace=` its name in that workspace; without `ref`, the first segment of the `subject`, so `/<workflow>/<topic>` names `<workflow>`; with neither, `WORKFLOW_INVALID_REQ`. By id, a caller with no relationship to the workflow gets `PERMISSION_DENIED`; by name, a workflow the caller cannot reach in that workspace is `WORKFLOW_INVALID_REFERENCE` (`event/WebhookEventControllerV2.java:246-285`; `event/WebhookEventService.java:76-98,220-229`) |
| `POST\|GET /api/v2/callback?ref={workflowrun}&topic=&status=&eventId=` | Resumes the run's "Wait For Event" tasks on `topic`; a `POST` body becomes their `data` result | `ref` is the workflow run. `status` ends the tasks `succeeded` (the default) or `failed`, in any case; anything else is refused with `QUERY_INVALID_FILTERS`. An `eventId` makes a repeat of the same callback for that run a no-op for 7 days, the `events_inbox` expiry; without one, each delivery is applied (`event/WebhookEventService.java:186-208`; `workflow/WorkflowRunService.java:1422-1440`; `migration/_0021__Indexes.java:118-122`) |

An event's run params are `event`, its attributes and extensions as CloudEvents JSON writes them, and `data`, its data
(JSON as sent, `text/*` a string, else base64; absent with none), read as `$(params.data.tag)`
(`event/WebhookEventService.java:231-277`). Conditions are JSON paths into the event as sent, data under `data`
(`type`, `data.tag`); a missing field matches nothing (`workflow/WorkflowService.java:1117-1194`).

A GitHub App delivery carries GitHub's `X-Hub-Signature-256` over the `github.webhookSecret` setting, or it is `401`
and starts nothing; the installation in its body, not the caller, picks the workspace
(`event/WebhookEventService.java:138-169`). GitHub's `ping` names no installation and is accepted; an `installation`
event links or unlinks the installation. Any other event is offered to every workflow in the linked workspace, each
submitted on its own, so one that refuses never stops the rest (`workflow/WorkflowService.java:737-755`). A workflow
runs when its `github` trigger is on and every condition holds: `events` is the `X-GitHub-Event` name, `repositories`
the repository's name, and any other field a JSON path into the payload; a field with no value matches nothing
(`workflow/WorkflowService.java:1136-1176`). The run's params are `data`, the payload, and `event`, `{type, id}` from
`X-GitHub-Event` and `X-GitHub-Delivery` (`event/WebhookEventService.java:172-183`).

### Events out

Every externally visible run status change is POSTed to each configured sink as a structured CloudEvent 1.0
(`application/cloudevents+json`). Egress is off by default (`flow.events.sink.enabled=false`,
`service-core/src/main/resources/application.properties:83`), and while it is off no outbox row is written
(`event/CloudEventsBridge.java:24-27`). Delivery is at-least-once through the outbox, three attempts with backoff and
then dead until replayed (`event/OutboxDispatcher.java:43,74-88`), so a consumer MUST treat duplicates as benign. An
event describes the transition it was recorded for: its subject and `data.status`/`data.phase` are that
transition's, and the rest of `data` is the run as it stands when the event is sent (`:93-115`). A task event is
sent for every task the run executes; the start and end nodes send none, and the run's own event reports its end
(`event/CloudEventsBridge.java:16-19,40-41`). A skipped task, or one admission refuses, completes in one write and
sends one event, as does a run timeout (`engine/TaskExecutionService.java:169-176,231-234,296`, `engine/WorkflowRunStateHelper.java:566-584`).

| Envelope field | Value |
| --- | --- |
| `type` | `io.boomerang.event.status.workflowrun`, `io.boomerang.event.status.taskrun` (`event/enums/EventType.java:10-11`) |
| `source` | `/apis/v1/events` (`event/EventFactory.java:23`) |
| `subject` | `/workflowrun/{id}/status/{status}` or `/taskrun/{id}/status/{status}`, the status the transition reached (`:36-39,61-64`) |
| `id` | the outbox row's id, the same on every delivery of that transition, so a consumer MAY drop an `id` it has handled; not a run id (`:44,69`) |
| `time` | when the transition occurred, not when it was sent (`:47,72`) |
| `initiatorcontext` (extension) | the run's `initiatorContext` label, when set; task events only (`event/model/TaskRunStatusEvent.java:37-40`) |

`flow.events.sink.payload` chooses `data`'s shape (`event/config/EventSinkProperties.java:37`); the envelope is the same either way, and one projection step builds both
(`event/model/RunStatusSummary.java:23-71`), pinned by `service-core/src/test/java/io/boomerang/event/StatusEventPayloadTest.java`.

| `payload` | `data` | Use |
| --- | --- | --- |
| `thin` (default) | Identity and lifecycle only: `id`, `workflowRef`, `workflowRunRef` (task events), `status`, `statusReason`, `phase`, `labels`, `creationDate`, `startTime`, `duration`; never `params`, `results` or `annotations`. Absent fields are omitted | A consumer triggers on the event and reads the run back over the API |
| `full` | The whole public `WorkflowRun` / `TaskRun`; a `secret` param's value is `*****`, and scrubbed from results, the status message and annotations | A consumer processes result values without calling back |

A sink is a bare URL in `flow.events.sink.urls` (comma-separated; any secret rides in the query string) or an entry
in `flow.events.sink.destinations[n]` with `url`, `header-name` (default `Authorization`) and `header-value`, the
secret, supplied from the environment, sent only to its own sink and never logged
(`event/config/EventSinkProperties.java:112-130`, `event/EventSinkService.java:117-126`). Prefer the header: a URL
secret is recorded by every proxy and access log on the way. Both lists are delivered to, through the internal
`RestTemplate`, so its proxy routing and timeouts apply (`event/EventSinkService.java:40`). Flow sends the header
verbatim and treats any non-2xx answer as a delivery failure to retry. Every key below is optional and empty means no filter: `types` (`workflowrun`, `taskrun`), `statuses` (a `RunStatus` name, or `terminal` for `succeeded`, `failed`, `cancelled`, `timedout`, `invalid`) and `phases` (a `RunPhase` name), at the top level for every sink and again per destination (a destination with none of its own inherits the top level), plus `labels` (`key=value`, all must match) per destination. An unknown value fails startup naming the key (`event/config/EventSinkProperties.java:49-54,83-100`). `statuses` matches the status as emitted, so a run with finally tasks takes its terminal status while still `running`; `phases=completed` is its true end. A transition no sink's filter wants writes no outbox row, so a filtered event is neither stored nor counted as dead (`event/CloudEventsBridge.java:47,67`). Labels are not on the transition, so they filter at delivery only: a row is posted to the destinations that admit it and one nobody admits is marked sent with nothing posted (`event/EventSinkService.java:102-112`).

## Updating a workflow

A workflow's `name` is its identity within a workspace; `displayName` is free text. Clients that keep workflows in
step with their own definitions MUST update in place and MUST NOT delete and recreate.

| Call | Effect |
| --- | --- |
| `PUT /api/v2/workspace/{workspace}/workflow` | Finds the workflow by `name` (`workflow/WorkflowService.java:614-621`). Found: same workflow, its runs, schedules and versions kept, and a new version added (`:2084-2098`). Not found: created (`:672-675`). Labels and annotations are merged (`:2038-2050`) |
| `PUT …/workflow?replace=true` | Overwrites the latest version in place (`:1798-1800`) and replaces labels and annotations. Runs already made from that version then show the new definition |
| `DELETE /api/v2/workspace/{workspace}/workflow/{name}` | Marks the workflow deleted and removes its schedules, workflow tokens, actions and relationship node at once (`workflow/WorkflowService.java:887-909,2278-2290`); once its runs have finished, the watcher hard-deletes them, their task runs, artifacts and the versions (`workflow/WorkflowRetentionService.java:250-290`). The monthly run quota still counts the old runs; Insights does not |

Every update without `replace` adds a version, so a client that applies its definitions on start-up SHOULD skip the call when nothing changed, for example by storing a hash of the definition in its
own annotation. A top-level field the workflow model does not carry is ignored, so the response shows exactly what was stored (`common/model/Workflow.java:23`).

Triggers follow the labels' rule: a trigger the update leaves out, or a missing `triggers` block, keeps its stored value (`workflow/WorkflowService.java:632-637,1039-1054,2044-2060`). Turning `schedule` off moves
the workflow's active schedules to `trigger_disabled`, and turning it on moves them back (`:907-922`). Every read fills a missing trigger with its default, manual on and the rest off, so responses
carry all five (`workflow/ConvertUtil.java:31,39-48`).

## Partial updates

Admin settings and global parameters are updated by a JSON Merge Patch, sent as `application/merge-patch+json` or
`application/json`: an absent member is unchanged, a `null` member is cleared, any other value replaces it, and a
secured value sent as `*****` keeps the stored one.

| Call | Body | Errors |
| --- | --- | --- |
| `PATCH /api/v2/parameters/{name}` | The members to change, `{"description": "…", "value": "…"}`; `name`, if sent, must match the path, and `hiddenValue` is ignored. Answers the param as a read shows it (`workflow/ParameterService.java:73-106`) | `400 PARAMS_INVALID_REQ` (a rename, or a body that is not an object); `404 PARAMS_INVALID_REFERENCE` |
| `PATCH /api/v2/settings` | `{"<group>": {"<key>": value}}`, values strings, numbers or booleans. Answers every setting (`core/SettingsService.java:73-104`) | `400 SETTINGS_INVALID_KEY` (1030) for an unknown group or key, or a value that is not a scalar; nothing is written |

A `null` here clears, whereas on a full-replace `PUT` (workflows, catalogue tasks, schedules) a secret sent as `null`
keeps the stored value, because there a missing member and a `null` member cannot be told apart.

## Labels and annotations

`labels` are a `Map<String,String>` and `annotations` a `Map<String,Object>` (`common/model/Workflow.java:52-53`,
`common/model/WorkflowRun.java:42-43`). Keys under `boomerang.io/` are the server's; every other key is the
client's, free-form, and MAY carry a `prefix/name` form. A dot in a label key is stored as `#`. Label updates differ
by resource: `PATCH /workspace/{workspace}` replaces the whole map (`workspace/WorkspaceService.java:307-311`), while `PUT /workflow` merges unless `replace=true`.

| Aspect | Rule | Where |
| --- | --- | --- |
| Reserved annotations | `boomerang.io/generation` and `boomerang.io/kind` are overwritten on every save; `boomerang.io/position` is a task's canvas position; a run annotation under `boomerang.io/` that a submit or start sends is dropped | `workflow/WorkflowService.java:793-799,1300,1633-1634,2062-2063,2215-2216`; `workflow/TaskService.java:718-719,806-807`; `workflow/WorkflowRunService.java:906-913` |
| Server labels | `boomerang.io/model` on an `ai` task run: its resolved `model` param, set at admission unless that param is a secret. `boomerang.io/item` on every item task run of a for-each, and on a child run a for-each run-workflow task started and so on its task runs: the item, when it is text that carries no secret. The engine never reads either; they exist to filter and group on | `common/model/ServerLabel.java:6-18`; `engine/TaskExecutionService.java:254-264,904-913,1370-1381`; `engine/ParameterManager.java:345` |
| Reserved labels | A label under `boomerang.io/` that a client sends is dropped: on submit, start and retry, from the workflow's labels when a run copies them, and from a node's or task's labels when a task run copies them | `workflow/WorkflowService.java:799,2158`; `workflow/WorkflowRunService.java:908-910,1095`; `engine/DAGUtility.java:288-291` |

## Parameter model

Definitions declare parameters as `AbstractParam`, a form field the editor renders (`name`, `type` as a field type
such as `text` or `password`, `label`, `default`, `options`, `required`, ...; `common/model/AbstractParam.java:20-45`);
runtime values are `RunParam` (`name`, `value`, `type`; `common/model/RunParam.java:12-18`). A `RunParam`'s `type` is
`string`, `array`, `object` or `secret`, and absent means `string` (`common/enums/ParamType.java:5-16`); an unknown
value is `400 PARAM_INVALID_TYPE` (1216) (`core/RestExceptionHandler.java:98-116`), and a `secret` whose value is not
a string `400 PARAM_SECRET_NOT_STRING` (1217) (`workflow/WorkflowService.java:2227-2239`). A workflow param whose `type` names no field type is the same `400` at save (`:1681,1816-1835`), and one with no type, or one stored before that check, runs as `string` (`common/util/ParameterUtil.java:338-345`).

| Object | Field | Element type |
| --- | --- | --- |
| `Workflow.params`, `Task.spec.params`, `Workspace.parameters` | definitions | `AbstractParam` (`common/model/Workflow.java:60`, `common/model/TaskSpec.java:18`, `workspace/model/Workspace.java:26`) |
| `WorkflowTask.params`, `WorkflowRun.params`, `TaskRun.params` | values | `RunParam` (`common/model/WorkflowTask.java:44`, `common/model/WorkflowRun.java:82`, `common/model/TaskRun.java:39`) |
| `WorkflowCanvas.config` | the workflow's `params`, under the compose routes' name | `AbstractParam` (`workflow/model/WorkflowCanvas.java:32`; mapped at `workflow/WorkflowService.java:1210,1284`) |

Every secret, on a run, a definition, a schedule, a global or workspace parameter or a secured setting, reads as
`*****` when set and as nothing when unset; a value that is exactly one Flow reference reads as written
(`common/util/DataAdapterUtil.java:169-178`). A client that sends back `*****`, `null` or no value keeps the stored
secret, `""` clears it, and any other value replaces it (`:127-137`). A run request that sends no value, or `*****`
for a secret, leaves the declared default in place (`common/util/ParameterUtil.java:210-232`). The dispatcher claim
carries the real value (`parameters.md`, "Sensitive parameters").

A workflow task, and a canvas node's `data`, MAY carry `foreach: { "items": … }`: a JSON array literal or one
whole-value reference such as `"$(tasks.stage.results.batches)"` (`common/model/WorkflowTask.java:51`,
`workflow/model/CanvasNodeData.java:22`). Each item gets the `RunParam`s `item` and `index`; after the task,
`$(tasks.<name>.results.<result>)` is a JSON array in item order, `null` for an item that did not succeed
(`engine/TaskExecutionService.java:976-1021`). Save refuses with `WORKFLOW_INVALID_TASK_FOREACH` (1213) items neither an
array nor one reference, a literal array over `max.foreach.items` (default 256), a task type other than those a
dispatcher runs and `runworkflow`, and a task declaring `item` or `index`; and with `WORKFLOW_INVALID_TASK_NAME` (1214)
a name ending in `[<digits>]`, the item form (`workflow/WorkflowService.java:144-158,1715-1724,1946-1982`; `engine/TaskExecutionService.java:92-99`).

## How the webapp calls the API

The browser never calls `/api/*`: a React Router `loader` or `action` calls it server-side through `serverFetch`,
which targets `CORE_SERVICE_INTERNAL_ORIGIN`, forwards the session `Cookie` and rewrites `/api/...` to `/api/v2/...`
(`client-web/src/Config/serverFetch.ts:34-36,77,80-88`). Binary or streamed reads go through the webapp's `/res/*`
routes (`client-web/src/Config/resourceRoutes.ts:19-44`). `PRODUCT_SERVICE_ENV_URL` (default `/api`) only builds URLs
the webapp displays, such as the webhook trigger URL (`client-web/src/Config/servicesConfig.ts:18-21`). The webapp's server
on `:3000` is the one browser-facing origin; `service-core` on `:7700` stays open to integrations, the dispatcher and direct use (`docker-compose.yml:188-194,234-256`).

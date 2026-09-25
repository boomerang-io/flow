# API contract

The public API is `/api/v2`; the dispatcher wire is `/api/v1/dispatcher`; both are served by
`service-core`. This document states the rules every endpoint follows. Route-level detail lives in
the OpenAPI document springdoc generates from the `*ControllerV2` classes.

## URL shape

Every public route is under `/api/v2`; resources owned by a workspace sit under
`/api/v2/workspace/{workspace}/...`, everything else is global.

| Scope | Prefix | Controllers (`service-core/src/main/java/io/boomerang/...`) |
| --- | --- | --- |
| Workspace-scoped | `/api/v2/workspace/{workspace}/{workflow,workflowrun,task,action,schedule,insights}` | `workflow/WorkspaceWorkflowControllerV2.java:35`, `workflow/WorkspaceWorkflowRunControllerV2.java:31`, `workflow/WorkspaceTaskControllerV2.java:30`, `workflow/WorkspaceActionControllerV2.java:35`, `schedule/WorkspaceScheduleControllerV2.java:37`, `workspace/WorkspaceInsightsControllerV2.java:27` |
| Workspace collection | `/api/v2/workspace` | `workspace/WorkspaceControllerV2.java:38` |
| Global | `/api/v2/{auth,user,profile,token,task,taskrun,parameters,workflowtemplate,integration,webhook,event,callback}` | `core/AuthControllerV2.java:37`, `core/UserControllerV2.java:31`, `workspace/ProfileControllerV2.java:40`, `core/TokenControllerV2.java:32`, `workflow/TaskControllerV2.java:29`, `workflow/TaskRunControllerV2.java:23`, `workflow/ParameterControllerV2.java:24`, `workflow/WorkflowTemplateControllerV2.java:30`, `integrations/IntegrationControllerV2.java:50`, `event/WebhookEventControllerV2.java:30` |
| System | `/api/v2/{settings,activate,context,features,navigation}`, `/api/v2/system/outbox`, `/api/v2/audit` | `core/SystemControllerV2.java:35`, `event/OutboxControllerV2.java`, `core/audit/AuditControllerV2.java:46` |
| Dispatcher | `/api/v1/dispatcher` | `dispatcher/DispatcherControllerV1.java:41` |

`/api/v2/workflowtemplate` is read-only: `GET /{name}` and `GET /query` are the whole surface
(`workflow/WorkflowTemplateControllerV2.java:57,84`). Templates are content, not a managed
resource — the loader seeds them and a v3 upgrade imports them — so there is no route to create,
change or delete one. A client creates a Workflow from a template by reading the template and
posting its body to `POST /api/v2/workspace/{workspace}/workflow`
(`client-web/src/Features/Home/Home.tsx:61-78`).

The global Task catalogue carries the same operations as the workspace-scoped one, `DELETE /api/v2/task/{name}`
included (`workflow/TaskControllerV2.java`); it refuses with `TASK_DELETE_IN_USE` (`409`) while a run in flight
still references the Task. Two system routes serve the outbound event outbox: `GET /api/v2/system/outbox`
(`?status=`, default `dead`, plus `page`/`limit`) lists rows, and `PUT /api/v2/system/outbox/replay`
(`?ids=`, `?status=`, `?olderThan=` epoch milliseconds) puts them back in the queue and answers
`{"replayed": n}`. A listed row that has failed delivery carries `lastError` (the exception type and message,
capped at 1024 characters) and, once dead, `deadAt`; a replay clears both. Both routes need `system` permission
and a `global` token.

Two routes read the audit trail, both instance-wide and both admin-only
(`core/audit/AuditControllerV2.java:92,166`). `GET /api/v2/audit` returns a `Page<AuditEvent>` newest first;
`GET /api/v2/audit/stats` counts the same filters and window by outcome and adds the configured
`captureEnabled`, `level` and `retentionDays`. Both take the same filters:

| Parameter | Accepts | Default |
| --- | --- | --- |
| `actor` | any part of an actor id or display name, ignoring case | none |
| `action`, `outcome`, `level` | comma-separated `AuditAction` / `AuditOutcome` / `AuditLevel` names | none (all) |
| `resourceType` | comma-separated resource types (`workflow`, `token`, …) | none |
| `resourceId`, `workspaceId` | a single resource id; comma-separated workspace ids | none |
| `from`, `to` | ISO-8601 instants, `to` exclusive | `from` = now − 30 days; `to` = now |
| `page`, `limit`, `order` (listing only) | 0-based page; page size, capped at 100; `ASC`/`DESC` on `time` | `0`, `25`, `DESC` |

The `from` default is load-bearing rather than cosmetic: it keeps both the page and its count on the
`time` index of an insert-only collection. Sorting is by `time` only — there is no `sort` parameter.
`AuditEvent` is the entity minus the CloudEvents envelope (`type`, `source`, `subject`) and the TTL anchor
`createdAt`; `payload` is passed through as captured and holds request discriminators only, never content.

`{workspace}` is the workspace **name**, not its id. There is no `/api/v2/team/{team}` alias: the
former alias was retired and only `/api/v2/workspace/{workspace}` is registered
(`core/security/EngineWorkspaceInterceptorConfiguration.java:12-13`). `TaskRun` is the one run
resource with no workspace segment; `GET /api/v2/taskrun/{id}/log` is authorised through the
owning `WorkflowRun` instead (`workflow/WorkflowRunService.java:325-335`).

## Error response

Every API error, including authentication failures, is a `RestErrorResponse`
(`lib-common/src/main/java/io/boomerang/common/error/RestErrorResponse.java`), built by
`core/RestExceptionHandler.java:36-55` (`BoomerangException`) and `:72-84` (`AuthenticationException`).

```json
{ "timestamp": "2026-09-01T10:00:00.000+00:00", "code": 1001, "reason": "QUERY_INVALID_FILTERS",
  "message": "Invalid query filters(labels) have been provided.", "status": "400 BAD_REQUEST" }
```

| Field | Source |
| --- | --- |
| `code`, `reason`, HTTP status | The `BoomerangError` enum constant (`lib-common/.../error/BoomerangError.java`); code ranges: 0–999 mirror HTTP, 10xx generic, 11xx workspace, 12xx workflow, 13xx workflow run, 14xx task, 15xx task run, 16xx action, 17xx schedule, 18xx parameter (`BoomerangError.java:13-17`) |
| `message` | `service-core/src/main/resources/messages.properties`, keyed by `reason`, with `{0}` arguments (`:12`); an explicit exception message wins (`RestExceptionHandler.java:42-50`) |
| `cause` | Present only when the exception has a cause (`:53-55`) |

A `*_INVALID_REF*` code answers `404` and means the resource the request path addresses does not exist; blank,
missing or unusable input answers `400` under the matching `*_INVALID_REQ` code (`TEAM_INVALID_REQ`,
`WORKFLOW_INVALID_REQ`, `TASK_INVALID_REQ`, `PARAMS_INVALID_REQ`, `WORKFLOWRUN_INVALID_REQ`,
`TASKRUN_INVALID_REQ`, `SCHEDULE_INVALID_REQ`). A reference that fails to resolve *inside a body* stays `400` —
the route exists and the payload is wrong — which is why a workflow node naming a Task that does not exist is
`WORKFLOW_INVALID_TASK_REF` on `400`, and a node carrying no task reference at all is `WORKFLOW_MISSING_TASK_REF`
on `400` at both save and submit. Decision 0080 states the rule. `TASK_INVALID_NAME` (`1403`, `400`) means the
supplied name is blank or breaks the slug rules and nothing else: every scoped Task lookup, changelog and delete
answers `TASK_INVALID_REFERENCE` (`1401`, `404`) when the named Task does not exist or the caller cannot reach it
(`workflow/TaskService.java`).

## Pagination and sorting

List endpoints take `page` (0-based), `limit`, `order` (`ASC`/`DESC`) and, where sortable, `sort`
(a field name) as separate query parameters, and return Spring Data `Page<T>`
(`workflow/WorkspaceWorkflowRunControllerV2.java:58,104-117`; `workspace/WorkspaceControllerV2.java:129-147`,
where `sort` defaults to `name` and `order` to `DESC`). Run and action queries also take
`fromDate`/`toDate` as epoch milliseconds (`WorkspaceWorkflowRunControllerV2.java:118-131`) and
list filters as comma-separated values (`statuses`, `phase`, `workflows`, `triggers`). Clients MUST
treat these as today's contract; boomerang-io/flow#328 proposes moving to Spring Data `Pageable`
(`sort=field,dir`), which would change the wire.

## Public run models

`WorkflowRun` and `TaskRun` (`lib-common/src/main/java/io/boomerang/common/model/`) are the
public shapes; the entities are separate classes and MUST NOT be returned from a controller.

| Rule | Where |
| --- | --- |
| No execution-state field is serialised: `claim`, `timeoutAt`, `retry`, `retryAfter`, `waitUntil`, `pauseRequestedAt`, `agentRef`, `dispatcherRef` exist on the entities only | `WorkflowRunEntity.java:53,59,64,77`; `TaskRunEntity.java:53-55,65,71,75,81`; pinned by `service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:48-81` |
| Also entity-only: `statusOverride`, `retryCount` (workflow run); `preApproved`, `decisionValue`, `dependencies` (task run) | `WorkflowRunEntity.java:43,77`; `TaskRunEntity.java:53-55` |
| Pause is exposed as the derived boolean `paused`, never the timestamp | `WorkflowRun.java:52-54`; test `:85-92` |
| `status` (`notstarted, ready, running, waiting, succeeded, failed, invalid, skipped, cancelled, timedout`) is the external field | `lib-common/.../enums/RunStatus.java` |
| **Exception:** `phase` (`queued, pending, running, completed`) is serialised on both models because the dispatcher receives the same classes and branches on it. `queued` is now the only position `status` cannot express, so it is the whole remaining reason the field is exposed | `TaskRun.java:19-23`; `dispatcher/DispatcherControllerV1.java:83,97,126`; `service-dispatcher/.../dispatcher/QueueService.java:47-55`; tripwire `PublicRunModelSerialisationTest.java:117-129`, phase set pinned at `:137-141` |

`TaskRun` is `@JsonInclude(NON_NULL)`, so a null field is absent rather than `null`
(`PublicRunModelSerialisationTest.java:97-99`).

## YAML content negotiation

Task definitions are also served and accepted as `application/x-yaml`, chosen by the `Accept`
and `Content-Type` headers on the same paths as JSON (`workflow/TaskControllerV2.java:61,152,194,239-240`;
`workflow/WorkspaceTaskControllerV2.java:71,184,241,301-302`). The converter is a Jackson
`YAMLMapper` with `LITERAL_BLOCK_STYLE` enabled, so multi-line strings such as `spec.script` are
emitted as `|` blocks (`workflow/config/YamlJacksonHttpMessageConverter.java:11-15`). With no
`Accept` header the JSON handler wins (`workflow/config/YamlConfiguration.java:16-23`).

## Webhook and event endpoints

### Events in

All three routes require `webhook/action` permission and accept `session`, `user`, `key` and
`global` tokens (`event/WebhookEventControllerV2.java:73-76`).

| Route | Body | Workflow resolution |
| --- | --- | --- |
| `POST /api/v2/webhook` (`application/json`) | Raw JSON, converted to run params; GitHub (`X-GitHub-Event`) and Slack (`x-slack-signature`) payloads branch on their headers | `?ref=` — a body with neither a known header nor `ref` is `400` (`:79-84,121-127`) |
| `POST /api/v2/event` (`application/cloudevents+json`) | CloudEvents 1.0 structured mode | `?ref=`, else the first path element of the CloudEvent `subject` (`:210-224`; `event/WebhookEventService.java:82-88`) |
| `POST /api/v2/event` (any other type) | CloudEvents 1.0 binary mode, attributes in `ce-*` headers | same (`:228-243`) |
| `POST|GET /api/v2/callback?ref={workflowrun}&topic=&status=` | Resumes a "Wait For Event" task | `ref` is the workflow run (`:139-196`) |

Accepted CloudEvent shape (structured mode):

```json
{ "specversion": "1.0", "type": "io.boomerang.eventing.custom", "source": "/github/actions",
  "id": "C234-1234-1234", "subject": "/5f74d0293979cd04c7f8afa1", "datacontenttype": "application/json",
  "data": { "event": "request_success", "inputs": { "key": "value" } } }
```

`data` is optional: an event without it is accepted and its run params carry only `event`
(`WebhookEventService.java:226-230`).

A caller with no relationship to the workflow gets `PERMISSION_DENIED`
(`WebhookEventService.java:94-97`); a rejected request creates no run.

### Events out

Every externally visible run status change is POSTed to each configured sink as a structured
CloudEvent 1.0 (`application/cloudevents+json`). Egress is off by default
(`flow.events.sink.enabled=false`, `application.properties:50`); delivery is at-least-once through the
outbox, so a consumer MUST treat duplicates as benign (decision 0012).

| Envelope field | Value |
| --- | --- |
| `type` | `io.boomerang.event.status.workflowrun`, `io.boomerang.event.status.taskrun` (`event/enums/EventType.java:9-11`) |
| `source` | `/apis/v1/events` |
| `subject` | `/workflowrun/{id}/status/{status}` or `/taskrun/{id}/status/{status}` (`event/EventFactory.java:54-58,79-83`) |
| `id` | a fresh UUID per delivery, not a run id |
| `initiatorcontext` (extension) | the run's `initiatorContext` label, when set (task events only, `event/model/TaskRunStatusEvent.java:37-40`) |

`data` carries the run, in one of two shapes chosen by `flow.events.sink.payload`
(`event/config/EventSinkProperties.java:22`). The envelope is identical either way, so routing and
filtering built on `type` and `subject` are unaffected by the setting.

| `payload` | `data` | Use |
| --- | --- | --- |
| `thin` (default) | The run's identity and lifecycle only: `id`, `workflowRef`, `workflowRunRef` (task events), `status`, `statusReason` (task events), `phase`, `labels`, `creationDate`, `startTime`, `duration`. Absent fields are omitted, never `null` | A consumer triggers on the event and reads the run back over the API |
| `full` | The whole public `WorkflowRun` / `TaskRun` model, `params`, `results` and `annotations` included; a `secret` param's value is `*****` | A consumer processes result values without calling back |

One projection step builds both (`event/model/RunStatusSummary.java:46-53`); the shapes are pinned by
`service-core/src/test/java/io/boomerang/event/StatusEventPayloadTest.java`. `thin` never carries
`params`, `results` or `annotations` — see decision 0079.

```json
{ "specversion": "1.0", "type": "io.boomerang.event.status.taskrun",
  "source": "/apis/v1/events", "subject": "/taskrun/68b1.../status/failed",
  "id": "9f1c...", "time": "2026-09-16T04:21:07Z", "datacontenttype": "application/json",
  "data": { "id": "68b1...", "workflowRef": "66aa...", "workflowRunRef": "67cc...",
            "status": "failed", "statusReason": "JobFailed", "phase": "completed",
            "labels": { "initiatorId": "user-1" },
            "creationDate": 1758000000000, "startTime": 1758000001000, "duration": 4210 } }
```

A sink is configured either as a bare URL or as a destination that authenticates with a request
header. Prefer the header: a URL secret is recorded by every proxy and access log on the way, a
header value is not. Bare URLs stay supported unchanged for receivers that can only be given a URL.

| Property | Meaning |
| --- | --- |
| `flow.events.sink.urls` | Comma-separated sink URLs; any secret rides in the query string (`...?token=xyz`) |
| `flow.events.sink.destinations[n].url` | A sink that authenticates with a header |
| `flow.events.sink.destinations[n].header-name` | Header to send; defaults to `Authorization` (`EventSinkProperties.java:42`) |
| `flow.events.sink.destinations[n].header-value` | The secret, supplied from the environment; sent only to its own sink and never logged (`EventSinkService.java:94-100`) |

Both lists are delivered to; every request goes through the configured internal `RestTemplate`, so
proxy routing and the per-template timeouts apply (decision 0062). The receiver's own
authentication is its business: Flow sends the header verbatim and treats any non-2xx as a
delivery failure to retry.

## Labels and annotations

`labels` are a client-owned `Map<String,String>`; `annotations` are a `Map<String,Object>` in
which keys prefixed `boomerang.io/` are reserved for the server.

| Aspect | Rule | Where |
| --- | --- | --- |
| Label keys | Free-form strings, MAY carry a `prefix/name` form; the server does not validate them | `common/model/Workflow.java:52`, `WorkflowRun.java:41` |
| Label filter | `?labels=key%3Dvalue,...` — each entry is URL-decoded and split on `=`; dots in keys are stored as `#` | `WorkspaceWorkflowRunControllerV2.java:66-70`; `WorkflowRunService.java:503-518` |
| Reserved annotations | `boomerang.io/generation`, `boomerang.io/kind` are overwritten on every save; `boomerang.io/position` is the canvas position of a task; `boomerang.io/*-params` are stripped from run responses | `WorkflowService.java:1676-1677,1136`; `TaskService.java:646-647`; `WorkflowRunService.java:977-979` |
| Client annotations | Any key outside `boomerang.io/` MAY be set; on update, annotations and labels are merged unless `replace=true` | `WorkflowService.java:1644-1656` |

## Parameter model

Definitions declare parameters as `AbstractParam` (a UI-driven field: `name`, `type`,
`label`, `defaultValue`, `options`, `required`, ...; `common/model/AbstractParam.java:16-34`);
runtime values are `RunParam` (`name`, `value`, `type`; `common/model/RunParam.java:12-17`). `type` is
one of `string`, `array`, `object` or `secret` and is omitted when absent, which means `string`; an
unknown value is `400 PARAM_INVALID_TYPE` (1213), and a `secret` whose value is not a string is
`400 PARAM_SECRET_NOT_STRING` (1214). A `secret` param's value is returned as `*****` on every run read,
submit and lifecycle response and `full` status event; the dispatcher claim carries the real value
(`task-runtime.md`, "Sensitive parameters").

| Object | Field | Element type |
| --- | --- | --- |
| `Workflow.params`, `Task.spec.params`, `Workspace.parameters` | definitions | `AbstractParam` (`Workflow.java:59`, `TaskSpec.java:17`, `workspace/model/Workspace.java:25`) |
| `WorkflowTask.params`, `WorkflowRun.params`, `TaskRun.params` | values | `RunParam` (`WorkflowTask.java:43`, `WorkflowRun.java:65`, `TaskRun.java:39`) |
| `WorkflowCanvas.config` (webapp type only) | editor-only view of the same definitions | `client-web/src/Types/index.tsx:348` |

There is no `config` field on the backend `Task` or `Workflow` model; the word survives only in
the webapp's canvas type and in `DataAdapterUtil.filterRunParamValueByFieldType`'s parameter name
(`lib-common/.../util/DataAdapterUtil.java:217-218`). boomerang-io/flow#377 tracks unifying the two.

## How the webapp calls the API

The browser never calls `/api/*`; every request is made server-side by a React Router
`loader`/`action` through `serverFetch`, which targets `CORE_SERVICE_INTERNAL_ORIGIN`, forwards
the inbound session `Cookie`, and rewrites `/api/...` to `/api/v2/...`
(`client-web/src/Config/serverFetch.ts:24,64-72`). Binary or streamed reads (task YAML, run logs,
workflow export) go through the webapp's own `/res/*` resource routes
(`client-web/src/Config/resourceRoutes.ts:19-39`). `PRODUCT_SERVICE_ENV_URL`
(default `/api`, injected into `window._SERVER_DATA` in production) is used only for URLs the UI
displays, such as the copyable webhook trigger URL (`client-web/src/Config/servicesConfig.ts:18-24`).
In `docker-compose.yml` the webapp's SSR server on `:3000` is the single browser-facing origin and
`service-core` on `:7700` stays reachable for integrations, the dispatcher and direct API use
(`docker-compose.yml:3-8,166-176`); there is no separate gateway.

Browser routes are keyed by workspace — `/:workspace/...` (`client-web/src/Config/appConfig.ts:80-134`) —
and v4's `/:team/...` URLs are not redirected, so bookmarks and pasted links from v4 break at this
major.

## Open contract decisions

- Label update semantics differ by resource: `PATCH /workspace/{workspace}` replaces the map (`WorkspaceService.java:284-285`); `PUT /workflow` merges unless `replace=true` (`WorkflowService.java:1644-1649`). One rule is still to be chosen.
- Whether to strip `phase` from the public models (a dispatcher-only wire model or a `@JsonView`), and whether the invariant narrows to `WorkflowRun` only — the webapp reads `TaskRun.phase`.
- boomerang-io/flow#328: move list endpoints to Spring Data `Pageable` (`order` → `sort=field,dir`).
- boomerang-io/flow#377: whether editor `config` and engine `params` stay two shapes.

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
| Global | `/api/v2/{auth,user,profile,token,task,taskrun,parameters,workflowtemplate,integration,webhook,event,callback}` | `core/AuthControllerV2.java:37`, `core/UserControllerV2.java:31`, `workspace/ProfileControllerV2.java:40`, `core/TokenControllerV2.java:32`, `workflow/TaskControllerV2.java:29`, `workflow/TaskRunControllerV2.java:23`, `workflow/ParameterControllerV2.java:24`, `workflow/WorkflowTemplateControllerV2.java:31`, `integrations/IntegrationControllerV2.java:50`, `event/WebhookEventControllerV2.java:30` |
| Dispatcher | `/api/v1/dispatcher` | `dispatcher/DispatcherControllerV1.java:41` |

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
| No execution-state field is serialised: `claim`, `timeoutAt`, `retry`, `retryAfter`, `waitUntil`, `pauseRequestedAt`, `agentRef`, `dispatcherRef` exist on the entities only | `WorkflowRunEntity.java:53,59,64,77`; `TaskRunEntity.java:53-55,65,71,75,81`; pinned by `service-core/src/test/java/io/boomerang/common/PublicRunModelSerialisationTest.java:43-76` |
| Also entity-only: `statusOverride`, `retryCount` (workflow run); `preApproved`, `decisionValue`, `dependencies` (task run) | `WorkflowRunEntity.java:43,77`; `TaskRunEntity.java:53-55` |
| Pause is exposed as the derived boolean `paused`, never the timestamp | `WorkflowRun.java:52-54`; test `:80-87` |
| `status` (`notstarted, ready, running, waiting, succeeded, failed, invalid, skipped, cancelled, timedout`) is the external field | `lib-common/.../enums/RunStatus.java` |
| **Exception:** `phase` (`queued, pending, running, completed, finalized`) is serialised on both models because the dispatcher receives the same classes and branches on it | `TaskRun.java:19-23`; `dispatcher/DispatcherControllerV1.java:93,142,157`; `service-dispatcher/.../dispatcher/QueueService.java:47-55`; tripwire `PublicRunModelSerialisationTest.java:110-121` |

`TaskRun` is `@JsonInclude(NON_NULL)`, so a null field is absent rather than `null`
(`PublicRunModelSerialisationTest.java:92-94`).

## YAML content negotiation

Task definitions are also served and accepted as `application/x-yaml`, chosen by the `Accept`
and `Content-Type` headers on the same paths as JSON (`workflow/TaskControllerV2.java:61,152,194,239-240`;
`workflow/WorkspaceTaskControllerV2.java:71,184,241,301-302`). The converter is a Jackson
`YAMLMapper` with `LITERAL_BLOCK_STYLE` enabled, so multi-line strings such as `spec.script` are
emitted as `|` blocks (`workflow/config/YamlJacksonHttpMessageConverter.java:11-15`). With no
`Accept` header the JSON handler wins (`workflow/config/YamlConfiguration.java:16-23`).

## Webhook and event endpoints

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
runtime values are `RunParam` (`name`, `value`; `common/model/RunParam.java:10-12`).

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

## Open contract decisions

- Label update semantics differ by resource: `PATCH /workspace/{workspace}` replaces the map (`WorkspaceService.java:284-285`); `PUT /workflow` merges unless `replace=true` (`WorkflowService.java:1644-1649`). One rule is still to be chosen.
- Whether to strip `phase` from the public models (a dispatcher-only wire model or a `@JsonView`), and whether the invariant narrows to `WorkflowRun` only — the webapp reads `TaskRun.phase`.
- boomerang-io/flow#328: move list endpoints to Spring Data `Pageable` (`order` → `sort=field,dir`).
- boomerang-io/flow#377: whether editor `config` and engine `params` stay two shapes.

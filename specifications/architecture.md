# Architecture

Boomerang Flow is one Maven monorepo (`pom.xml:10-13`) plus one pnpm web client, built into four images
that ship together under a single product version. Workflows are directed acyclic graphs (DAGs) executed
by `service-core`; the container work for each task is carried out by a separate `service-dispatcher`.

## Modules

| Module | Owns | Depends on |
| --- | --- | --- |
| `service-core` | The product server: the v2 REST API, authentication and authorization, workspaces, workflow definitions, the DAG execution engine, the dispatcher-facing v1 API, schedules, webhooks and outbound events, GitHub/Slack integrations. One Spring Boot application, `service-core/src/main/java/io/boomerang/Application.java:36-39`. | `lib-common` (`service-core/pom.xml:47`) |
| `service-dispatcher` | The worker that registers with core, polls and claims runs, executes each task in Kubernetes through the `io.boomerang.executor.TaskExecutor` interface (`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-31`), and reports results back. `dispatcher.executor` selects `tekton` (default, `TektonServiceImpl.java:53`) or `kube-jobs` (`KubeJobsExecutor.java:65`). One `dispatcher.tasks.runtimeClassName` per deployment (`service-dispatcher/README.md:15-17`). | `lib-common` (`service-dispatcher/pom.xml:32`) |
| `service-loader` | Database migrations and seed data on Flamingock, run once before each deploy. `LoaderApplication.java:20` loads every changeunit in `io.boomerang.loader.migration` (`_0001` … `_0039`). It is the only thing that creates indexes: core sets `spring.data.mongodb.auto-index-creation=false` (`service-core/src/main/resources/application.properties:56`). | Mongo driver only |
| `lib-common` | The shared wire and storage contract: 9 entities (`WorkflowRunEntity`, `TaskRunEntity`, …), the public models (`WorkflowRun`, `TaskRun`, `Trigger`, …), enums (`RunStatus`, `RunPhase`, `TaskType`, …), `BoomerangError`/`RestErrorResponse`, and pure utilities (`Backoff`, `SweepRunner`) under `lib-common/src/main/java/io/boomerang/common/`. No beans, no repositories. | — |
| `client-web` | The React 18 + React Router 7 web app with IBM Carbon, served by its own Node server (`client-web/Dockerfile`, `client-web/server/index.js`) with server rendering on (`client-web/react-router.config.ts:16-17`, base path `/apps/flow`). The browser talks only to this server; it calls `service-core` server-side through `CORE_SERVICE_INTERNAL_ORIGIN` (`client-web/src/Config/serverFetch.ts:24`). | `service-core` over HTTP |

All Java modules share the Spring Boot 4.1.0 parent and Java 25 (`pom.xml:18-19`, `ci-release.yml:26`).
Every module reads `application.properties` overridden by environment variables (`docker-compose.yml:135-141`).

## Run modes of `service-core`

`flow.mode` selects one of two shapes; a missing or blank value means `standalone`
(`service-core/src/main/java/io/boomerang/config/FlowMode.java:21-23,49-52`). Beans that belong to only one
mode carry `@ConditionalOnFlowMode(...)` (`config/ConditionalOnFlowMode.java:31-35`). There is no separate
engine image: the same jar runs in either mode (`.github/workflows/ci-release.yml:52-54`).

| Concern | `standalone` (default) | `engine` |
| --- | --- | --- |
| Purpose | The complete product. | Headless embedded execution inside another product. |
| `flow.security.enabled` default | `true` | `false` (`core/security/FlowSecurityProperties.java:23-29`) |
| `flow.quotas.enabled` default | `true` | `false` (`application.properties:21-28`) |
| Packages loaded | All eight. | `workspace`, `schedule`, `integrations` and the sign-in surface (`core/AuthControllerV2.java:43`, `core/security/AuthExchangeService.java:25`) do not load — 18 classes carry the standalone gate. |
| Workspace | Any workspace. | Only `system`; other `{workspace}` path values are refused with `TEAM_INVALID_REF` (`core/security/EngineWorkspaceInterceptor.java:37-45`, registered by `EngineWorkspaceInterceptorConfiguration.java:15`). |
| Web app | Deployed alongside core. | None — `client-web` is never deployed with engine mode. |
| Dispatcher API | `/api/v1/dispatcher/**` behind `DispatcherAuthFilter` in both modes; `flow.dispatcher.auth.enabled` (`application.properties:31-34`) is independent of `flow.security.enabled`. | Same. |

## Feature packages inside `service-core`

`service-core` is one Maven module with eight flat feature packages under `io.boomerang` plus `config`
(the mode switch) and `common.model` (four paging response types). Boundaries are a convention: there is
no Spring Modulith and no ArchUnit rule; a package is a directory, and reviews enforce the direction below.

| Package | Owns | Imports from (verified by grep over `^import io.boomerang.*`) |
| --- | --- | --- |
| `core` | Users, tokens, roles, settings, audit, the relationship graph (`RelationshipService`), security (filters, `SecurityInterceptor`, `@AuthCriteria`), the shared `RestConfig`/`MongoConfiguration`. | nothing above it — zero upward imports |
| `workspace` | Workspaces, quotas (`FlowQuotaProperties`), insights, profile. | `core`, `workflow` |
| `workflow` | Workflow/revision definitions, task catalogue, templates, parameters, actions (approvals), the workspace-scoped v2 controllers (`WorkspaceWorkflowControllerV2`, `WorkspaceWorkflowRunControllerV2`, …), Tekton YAML conversion. | `core`, `engine`, `event`, `schedule`, `workspace` |
| `engine` | Execution: `WorkflowExecutionService`, `TaskExecutionService`, `TaskRunService`, `DAGUtility`, `WorkflowRunStateHelper`, `WorkflowWatcher`, `LogClient`. | `workflow`, `dispatcher` (the `dispatchers` repository, `engine/WorkflowWatcher.java:14-15`) |
| `dispatcher` | The worker registry and the v1 wire: `DispatcherControllerV1`, `DispatcherService`, `DispatcherAuthFilter`, `DispatcherSecurityConfiguration`. | `core`, `engine`, `workflow` |
| `schedule` | Cron schedules and their firing: `ScheduleService`, `ScheduleWatcher`, `ScheduleJob`. | `core`, `workflow` |
| `event` | Webhook and CloudEvent ingress (`WebhookEventControllerV2`), the transactional outbox (`CloudEventsBridge`, `OutboxDispatcher`), the event sink. | `core`, `engine`, `integrations`, `workflow` |
| `integrations` | GitHub App and Slack. | `core`, `workflow` |

Rules that follow from the table: `core` MUST NOT import any feature package (`core/audit/AuditInterceptor.java:98`
records the one place this bit); the engine MUST NOT call the platform side synchronously — it publishes
Spring `ApplicationEvent`s that other packages listen to (`ScheduleRequested` handled by
`schedule/ScheduleEventListener.java:32-33`; `ChildWorkflowRunCreated` handled by
`core/RelationshipEventListener.java:36-37`). Controllers live in the package of the service they inject.

## How a workflow run moves through the system

Each arrow is a direct in-process call unless marked. Every state change is a compare-and-set (CAS)
`findAndModify` on the run document; a losing caller does nothing (`engine/TaskRunService.java:667-672`).

| Step | Class → class | Where |
| --- | --- | --- |
| 1. Submit | `POST /api/v2/workspace/{workspace}/workflow/{name}/submit` → `WorkspaceWorkflowControllerV2.submitWorkflow` → `WorkflowService.submit` → `WorkflowRunService.run` | `workflow/WorkspaceWorkflowControllerV2.java:252-264`, `workflow/WorkflowService.java:1709,1792`, `workflow/WorkflowRunService.java:749-751` |
| 1a. Other entry points | A schedule firing (`ScheduleWatcher.sweep` → `ScheduleJob.execute` → `WorkflowService.submit`) or a webhook/CloudEvent (`WebhookEventControllerV2` → `WebhookEventService` → `WorkflowService.submit`) | `schedule/ScheduleWatcher.java:139`, `schedule/ScheduleJob.java:77`, `event/WebhookEventControllerV2.java:72,210`, `event/WebhookEventService.java:108` |
| 2. Queue the run | `WorkflowRunService.run` saves the `WorkflowRunEntity` and calls `WorkflowExecutionService.queue`, which builds the task list from the revision (`DAGUtility.createTaskList`), validates the graph, and admits the run with `WorkflowRunStateHelper.tryAdmit` | `engine/WorkflowExecutionService.java:58-79`, `engine/WorkflowRunStateHelper.java:135` |
| 3. Start the run | `WorkflowRunService.start` → `WorkflowExecutionService.start`, which requires phase `pending`/`queued`, builds the graph, and executes the DAG on `asyncWorkflowExecutor` | `engine/WorkflowExecutionService.java:98-122` |
| 4. Admit each task | The DAG walk calls `TaskExecutionService.queue(taskRunId)`. This is the single pause gate — a run with `pauseRequestedAt` set admits nothing — then `TaskRunService.tryAdmit` moves the task to `ready`. Only `template`, `script`, `custom` and `generic` tasks wait for a dispatcher; the engine runs every other type itself. | `engine/TaskExecutionService.java:96,141-143,181,189-192`, `engine/TaskRunService.java:278` |
| 5. Dispatcher claims | `service-dispatcher` polls `GET /api/v1/dispatcher/{id}/tasks` on a fixed delay (HTTP). `DispatcherService.getTaskQueue` pages `TaskRunService.findClaimable` for the dispatcher's task types and claims each candidate with `TaskRunService.tryClaim`; only claimed documents are returned. `flow.queue.enabled=false` stops claiming. | `service-dispatcher/.../client/EngineClient.java:165-168`, `dispatcher/DispatcherControllerV1.java:85-97`, `dispatcher/DispatcherService.java:175-210`, `engine/TaskRunService.java:75,232` |
| 6. Execute | `QueueService.processTaskRun` calls `PUT /api/v1/dispatcher/taskrun/{id}/start`, then `TaskService.execute` → `TaskExecutor.create`/`watch` (Tekton `TaskRun` or `batch/v1` `Job`), then `PUT .../taskrun/{id}/end` with the results (HTTP). | `service-dispatcher/.../dispatcher/QueueService.java:69-87`, `dispatcher/TaskService.java:56` |
| 7. Results back | `DispatcherControllerV1.startTaskRun`/`endTaskRun` → `TaskRunService.start`/`end` → `TaskExecutionService.start`/`end`, which check the claim (`claimedBy`, `claimSeq`) before writing. | `dispatcher/DispatcherControllerV1.java:135-157`, `engine/TaskRunService.java:697,730`, `engine/TaskExecutionService.java:229,399` |
| 8. Advance the DAG | `TaskExecutionService.end` → `executeNextStep` → back to step 4 for each dependant, or `finishWorkflow` → `WorkflowRunStateHelper.tryComplete`. The dispatcher finalises the run through `PUT /api/v1/dispatcher/workflowrun/{id}/finalize` → `WorkflowExecutionService.end`. | `engine/TaskExecutionService.java:1022-1077`, `engine/WorkflowExecutionService.java:125-132` |
| 9. Events out | Each CAS publishes a `TaskRunTransition`/`WorkflowRunTransition` `ApplicationEvent`; `CloudEventsBridge` inserts a row into the `events_outbox`; `OutboxDispatcher.drain` delivers it as a CloudEvent through `EventSinkService` every `flow.events.outbox.interval-ms` (sink off by default, `application.properties:38`). | `engine/TaskRunService.java:675-684`, `event/CloudEventsBridge.java:32-48`, `event/OutboxDispatcher.java:59-62` |
| 10. Recovery | `WorkflowWatcher.sweep` runs ten isolated sweeps (timeouts, stalled runs, claims from gone dispatchers, deleted workflows, …); any core instance may reap any run. | `engine/WorkflowWatcher.java:116-138` |

The one call from core to a dispatcher is log streaming: `engine/LogClient.java:34` reads
`flow.agent.logstream.url` and proxies `GET /api/v1/logs/stream` (`service-dispatcher/.../dispatcher/LogV1Controller.java:14-19`).

## Deployables, images and versioning

One git tag builds and pushes the whole compatible set (`.github/workflows/ci-release.yml:9-13`); the tag
patterns are `5.x.y`, `5.x.y-beta.z` and `5.x.y-rc.z`. `:latest` moves only on a stable tag (`:73-80`).
`sbom.yml:11-14` fires on the same patterns. Path-filtered `ci-core.yml`, `ci-dispatcher.yml`, `ci-loader.yml`
and `ci-web.yml` test each module on push and pull request (`ci-core.yml:6-16`).

| Image | Built from | Runtime | Job in `ci-release.yml` |
| --- | --- | --- | --- |
| `boomerangio/flow-service-core` | `service-core/target/service-core.jar` | `eclipse-temurin:25-jre-alpine`, port 7700 (`application.properties:1`) | `build-core`/`deploy-core` (`:18,:49`) |
| `boomerangio/flow-service-dispatcher` | `service-dispatcher/target/service-dispatcher.jar` | same base, port 7702 (`service-dispatcher/.../application.properties:1`) | `build-dispatcher`/`deploy-agent` (`:100,:131`) |
| `boomerangio/flow-service-loader` | `service-loader/target/service-loader.jar` | same base, runs to completion | `build-loader`/`deploy-loader` (`:180,:206`) |
| `boomerangio/flow-client-web` | the `client-web/` sources — a build stage in `client-web/Dockerfile` runs `pnpm install --frozen-lockfile` and `pnpm run build`, so the image is complete from a clean checkout | `node:22-alpine`, port 3000 | `deploy-webapp` (`:256`) |

The dispatcher reaches core at `flow.engine.service.host` and authenticates with `flow.engine.dispatcher.token`
(`service-dispatcher/src/main/resources/application.properties:64-77`); core runs the dispatcher's `/api/v1/**`
chain first (`dispatcher/DispatcherSecurityConfiguration.java:42-46`) and the product chain second
(`core/security/SecurityConfiguration.java:66`).

## Local stack

`docker-compose.yml` runs the product secured: `mongo`, the one-shot `service-loader` (core waits on
`service_completed_successfully`, `:143-146`), a local IDPZero OpenID provider on `idp.localhost:4380`
(`:96-110`), the `auth-oidc-seed` one-shot that points the `auth` settings at it (`:115-127`), `service-core`
on `:7700` with `FLOW_MODE=standalone` and `FLOW_SECURITY_ENABLED=true` (`:129-141`), and `client-web` on
`:3000` (`:161-191`). The browser-facing origin is `http://localhost:3000`; there is no separate gateway —
`client-web`'s server is the only thing the browser talks to, and it calls core at
`http://service-core:7700` (`:191`). `service-dispatcher` is not in the stack because it needs a Kubernetes
cluster (`:10-16`). Build the jars with Maven and the web app with pnpm first (`:18-33`); the Playwright
suite in `e2e/` runs against this stack.

## Not built

A local Docker dispatcher (no Kubernetes) and folding `lib-common` into its owners are planned, not started.

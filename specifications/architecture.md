# Architecture

Boomerang Flow is one Maven monorepo (`pom.xml:10-14`) plus one pnpm web client, built into four images
and one published library that ship together under a single product version. Workflows are directed acyclic
graphs (DAGs) executed by `service-core`; the work for each task is carried out by a dispatcher — any process
that implements the published contract (`dispatcher-contract.md`). `service-dispatcher` is the Kubernetes one.
A dispatcher runs as its own process only where a boundary exists between it and core (credentials, network
zone, trust, placement); otherwise its poll loop is embedded through `dispatcher-sdk` (decision 0105).

## Modules

| Module | Owns | Depends on |
| --- | --- | --- |
| `service-core` | The product server: the v2 REST API, authentication and authorization, workspaces, workflow definitions, the DAG execution engine, the dispatcher-facing v1 API, schedules, webhooks and outbound events, GitHub/Slack integrations, and the database migrations it runs as it starts (`io.boomerang.migration`; see `data-model.md`). One Spring Boot application, `service-core/src/main/java/io/boomerang/Application.java:36-39`. | `lib-common` (`service-core/pom.xml:47`) |
| `dispatcher-sdk` | The dispatcher protocol as a library: the client for every `/dispatcher/v1` route, the runtime that registers, polls, starts, renews leases and reports ends, and the `TaskHandler` interface a dispatcher implements (`dispatcher-contract.md`, "SDK"). It owns its wire models and is the only module published, to GitHub Packages. | `spring-web` and Jackson only (`dispatcher-sdk/pom.xml:46-53`) |
| `service-dispatcher` | Flow's Kubernetes dispatcher, built on `dispatcher-sdk` (`config/DispatcherConfig.java:34-52`): its task handler executes each task in Kubernetes through the `io.boomerang.executor.TaskExecutor` interface (`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-31`) and its workflow handler provisions workspace volumes. `dispatcher.executor` selects `tekton` (default, `TektonServiceImpl.java:59`) or `kube-jobs` (`KubeJobsExecutor.java:72`). One `dispatcher.tasks.runtimeClassName` per deployment (`service-dispatcher/README.md:15-17`). | `dispatcher-sdk` (`service-dispatcher/pom.xml:30-34`) |
| `lib-common` | `service-core`'s wire and storage model (the dispatcher uses `dispatcher-sdk`'s own): 9 entities (`WorkflowRunEntity`, `TaskRunEntity`, …), the public models (`WorkflowRun`, `TaskRun`, `Trigger`, …), enums (`RunStatus`, `RunPhase`, `TaskType`, …), `BoomerangError`/`RestErrorResponse`, and pure utilities (`Backoff`, `SweepRunner`) under `lib-common/src/main/java/io/boomerang/common/`. No beans, no repositories. | — |
| `client-web` | The React 18 + React Router 7 web app with IBM Carbon, served by React Router's own server, `react-router-serve` (`client-web/package.json`, `start`; `client-web/Dockerfile`), with server rendering on (`client-web/react-router.config.ts:12`, base path `/apps/flow`). The browser talks only to this server; it calls `service-core` server-side through `CORE_SERVICE_INTERNAL_ORIGIN` (`client-web/src/Config/serverFetch.ts:26-27`). The root loader writes the deployment's runtime values into the document as `window._SERVER_DATA` (`client-web/app/serverData.server.ts`, `client-web/app/root.tsx`); the probe is `GET /apps/flow/health` (`client-web/app/routes/health.tsx`). | `service-core` over HTTP |

All Java modules share the Spring Boot 4.1.0 parent and Java 25 (`pom.xml:18-19`, `service-core/Dockerfile:10`).
Every module reads `application.properties` overridden by environment variables (`docker-compose.yml:190-196`).

## Deployment shape of `service-core` by properties

There is one shape of `service-core`: every bean loads in every deployment, and four properties express how
a deployment is used. There is no separate engine image (`.github/workflows/ci-release.yml:24-25`).

| Property (`application.properties`) | Default | `true` | `false` |
| --- | --- | --- | --- |
| `flow.security.enabled` (`:14`) | `true` | `AuthenticationFilter` resolves a real token on every request and `@AuthCriteria` is enforced (`core/security/FlowSecurityProperties.java:19-20`; `authorization.md`). | Every request runs as the synthetic admin `UnauthenticatedGlobalToken`; the sign-in surface answers `mode=none`. |
| `flow.workspace.single` (`:18`) | `false` | The seeded `system` workspace is the only one: any other `{workspace}` path segment is refused with `TEAM_INVALID_REF` (`core/security/SingleWorkspaceInterceptor.java:32-44`, registered on `/api/v2/workspace/{workspace}` and `/**` by `SingleWorkspaceInterceptorConfiguration.java:22-26`), and `WorkspaceService.create` and `delete` refuse with `TEAM_INVALID_REQ` (`workspace/WorkspaceService.java:189,355`). Every other route, the workspace and profile reads included, is the standard one. | Any workspace. |
| `flow.dispatcher.auth.enabled` (`:38`) | `true` | `/dispatcher/**` requires a Flow token with a machine actor kind (`DispatcherAuthFilter`), independent of `flow.security.enabled`. | No dispatcher authentication; local development only. |
| `flow.queue.enabled` (`:60`) | `true` | Dispatchers claim task runs. | Claiming stops; sweeps keep running (`execution-model.md`). |

Two shapes are the worked examples:

| Shape | Properties | Used for |
| --- | --- | --- |
| The full product (defaults) | `flow.security.enabled=true`, `flow.workspace.single=false` | The compose stack and every product install: workspaces, sign-in, tokens, integrations, schedules, the webapp. |
| An embedded engine | `flow.security.enabled=false`, `flow.workspace.single=true` | Headless execution inside another product that owns identity and tenancy. The webapp runs unchanged against it; it has no sign-in, so it MUST only run for local development or behind a protected route. |

Quotas are not a deployment property: the stored `features.workspaceQuotas` setting is the only switch
(`workflow/WorkflowService.java:214-218`), and the `system` workspace is seeded with unlimited quotas
(`service-core/src/main/resources/seed/workspace.json`). Deployment shape is `flow.<subsystem>.<switch>`
properties; runtime product toggles are `features.<name>` stored settings; a switch lives in exactly one tier
(decision 0108).

The webapp never reads a property. It renders from the flags `GET /api/v2/features` returns, and a flag is on
only when its setting is on and the shape leaves a surface behind it (`core/FeatureService.java:34-82`):

| Flag | Value |
| --- | --- |
| `workspace.single` | `flow.workspace.single` — when `true`, no workspace switcher, no create or leave |
| `workspace.management`, `user.management` | the setting, and `false` under `flow.workspace.single` |
| `workspace.quotas`, `insights`, `activity`, `workspace.parameters`, `global.parameters` | the setting |
| `schedules`, `integrations` | `true` |
| `authentication` | `flow.security.enabled` |
| `tokens`, `workflow.tokens` | `flow.security.enabled` (and the setting, for `workflow.tokens`): with security off there is nothing for a token to do |

The server-built menu (`core/NavigationService.java`) reads the same flags. When `authentication` is `false` the
webapp's startup loader never requests `GET /internal/v1/auth/config` (`client-web/src/Features/App/App.tsx:142-147`).

## Feature packages inside `service-core`

`service-core` is one Maven module with eight flat feature packages under `io.boomerang` plus `config`
(the OpenAPI configuration) and `common.model` (four paging response types). Boundaries are a convention: there is
no Spring Modulith and no ArchUnit rule; a package is a directory, and reviews enforce the direction below.

| Package | Owns | Imports from (verified by grep over `^import io.boomerang.*`) |
| --- | --- | --- |
| `core` | Users, tokens, roles, settings, audit, the relationship graph (`RelationshipService`), security (filters, `AuthCriteriaAuthorizationManager`, `@AuthCriteria`), the shared `RestConfig`/`MongoConfiguration`. | nothing above it — zero upward imports |
| `workspace` | Workspaces, quotas (`FlowQuotaProperties`), the audit-trail insights roll-up the quotas read (`InsightsService`), the Insights controller, profile. | `core`, `workflow` |
| `workflow` | Workflow/revision definitions, task catalogue, read-only workflow templates, parameters, actions (approvals), the run statistics the Insights page shows (`WorkflowRunInsightService`), the workspace-scoped v2 controllers (`WorkspaceWorkflowControllerV2`, `WorkspaceWorkflowRunControllerV2`, …), Tekton YAML conversion. | `core`, `engine`, `event`, `schedule`, `workspace` |
| `engine` | Execution: `WorkflowExecutionService`, `TaskExecutionService`, `TaskRunService`, `DAGUtility`, `WorkflowRunStateHelper`, `WorkflowWatcher`, `LogClient`. | `workflow`, `dispatcher` (the `dispatchers` repository, `engine/WorkflowWatcher.java:14-15`) |
| `dispatcher` | The worker registry and the v1 wire: `DispatcherControllerV1`, `DispatcherService`, `DispatcherAuthFilter`, `DispatcherSecurityConfiguration`. | `core`, `engine`, `workflow` |
| `schedule` | Cron schedules and their firing: `ScheduleService`, `ScheduleWatcher`, `ScheduleJob`. | `core`, `workflow` |
| `event` | Webhook and CloudEvent ingress (`WebhookEventControllerV2`), the transactional outbox (`CloudEventsBridge`, `OutboxDispatcher`), the event sink. | `core`, `engine`, `integrations`, `workflow` |
| `integrations` | GitHub App and Slack. | `core`, `workflow` |

Rules that follow from the table: `core` MUST NOT import any feature package (`core/audit/AuditInterceptor.java:98`
records the one place this bit); the engine MUST NOT call the platform side synchronously — it publishes
Spring `ApplicationEvent`s that other packages listen to (`ScheduleRequested` handled by
`schedule/ScheduleEventListener.java:26-27`; `ChildWorkflowRunCreated` handled by
`core/RelationshipEventListener.java:36-37`). Controllers live in the package of the service they inject.

## How a workflow run moves through the system

Each arrow is a direct in-process call unless marked. Every state change is a compare-and-set (CAS)
`findAndModify` on the run document; a losing caller does nothing (`engine/TaskRunService.java:667-672`).

| Step | Class → class | Where |
| --- | --- | --- |
| 1. Submit | `POST /api/v2/workspace/{workspace}/workflow/{name}/submit` → `WorkspaceWorkflowControllerV2.submitWorkflow` → `WorkflowService.submit` → `WorkflowRunService.run` | `workflow/WorkspaceWorkflowControllerV2.java:252-264`, `workflow/WorkflowService.java:1709,1792`, `workflow/WorkflowRunService.java:749-751` |
| 1a. Other entry points | A schedule firing (`ScheduleWatcher.sweep` → `ScheduleJob.execute` → `WorkflowService.submit`) or a webhook/CloudEvent (`WebhookEventControllerV2` → `WebhookEventService` → `WorkflowService.submit`) | `schedule/ScheduleWatcher.java:139`, `schedule/ScheduleJob.java:77`, `event/WebhookEventControllerV2.java:72,210`, `event/WebhookEventService.java:108` |
| 2. Queue the run | `WorkflowRunService.run` saves the `WorkflowRunEntity` and calls `WorkflowExecutionService.queue`, which builds the task list from the revision (`DAGUtility.createTaskList`), validates the graph, and admits the run with `WorkflowRunStateHelper.tryAdmit` | `engine/WorkflowExecutionService.java:61-82`, `engine/WorkflowRunStateHelper.java:96` |
| 3. Start the run | `WorkflowRunService.start` → `WorkflowExecutionService.start`, which requires phase `pending`/`queued`, builds the graph, and executes the DAG on `asyncWorkflowExecutor` | `engine/WorkflowExecutionService.java:101-125` |
| 4. Admit each task | The DAG walk calls `TaskExecutionService.queue(taskRunId)`. This is the single pause gate — a run with `pauseRequestedAt` set admits nothing — then `TaskRunService.tryAdmit` moves the task to `ready`. Only `template`, `script`, `custom` and `generic` tasks wait for a dispatcher; the engine runs every other type itself. | `engine/TaskExecutionService.java:96,141-143,181,189-192`, `engine/TaskRunService.java:278` |
| 5. Dispatcher claims | `service-dispatcher`, through the dispatcher SDK, long-polls `GET /dispatcher/v1/{id}/tasks` (HTTP): the engine holds the request up to 30 s, re-checking every 1 s, and the dispatcher reconnects as soon as it returns, asking for no more than its free slots (`?limit=`). `DispatcherService.getTaskQueue` pages `TaskRunService.findClaimable` for the dispatcher's task types and claims each candidate with `TaskRunService.tryClaim`; only claimed documents are returned. `flow.queue.enabled=false` stops claiming. | `dispatcher-sdk/.../sdk/Dispatcher.java:244-248`, `QueuePoller.java:68-87`, `dispatcher/DispatcherControllerV1.java:89-101`, `dispatcher/DispatcherService.java:260-340`, `engine/TaskRunService.java:97,275` |
| 6. Execute | The SDK's `TaskRunner` calls `PUT /dispatcher/v1/taskrun/{id}/start`, then the dispatcher's `TaskService.run` → `TaskExecutor.create`/`watch` (Tekton `TaskRun` or `batch/v1` `Job`), then `PUT .../taskrun/{id}/end` with the results (HTTP), retried until the engine takes it. | `dispatcher-sdk/.../sdk/TaskRunner.java:116-206`, `service-dispatcher/.../dispatcher/TaskService.java:69-79` |
| 7. Results back | `DispatcherControllerV1.startTaskRun`/`endTaskRun` → `TaskRunService.start`/`end` → `TaskExecutionService.start`/`end`, which check the claim (`claimedBy`, `claimSeq`) before writing. | `dispatcher/DispatcherControllerV1.java:151-180`, `engine/TaskRunService.java:697,730`, `engine/TaskExecutionService.java:229,399` |
| 8. Advance the DAG | `TaskExecutionService.end` → `executeNextStep` → back to step 4 for each dependant, or `finishWorkflow` → `WorkflowRunStateHelper.tryComplete`. `completed` is terminal; a run's workspace storage is released separately by the dispatcher asking `POST /dispatcher/v1/workspaces/releasable` which of the runs it still holds volumes for are finished. | `engine/TaskExecutionService.java:1022-1077`, `engine/WorkflowRunStateHelper.java:148`, `dispatcher/DispatcherService.java:367` |
| 9. Events out | Each CAS publishes a `TaskRunTransition`/`WorkflowRunTransition` `ApplicationEvent`; `CloudEventsBridge` inserts a row into the `events_outbox`; `OutboxDispatcher.drain` delivers it as a CloudEvent through `EventSinkService` every `flow.events.outbox.interval-ms` (sink off by default, `application.properties:50`). | `engine/TaskRunService.java:675-684`, `event/CloudEventsBridge.java:32-48`, `event/OutboxDispatcher.java:59-62` |
| 10. Recovery | `WorkflowWatcher.sweep` runs ten isolated sweeps (timeouts, stalled runs, claims from gone dispatchers, deleted workflows, …); any core instance may reap any run. | `engine/WorkflowWatcher.java:129-147` |

Core calls a dispatcher for one thing: a task's live log, read from the dispatcher holding the claim at the
URL and with the bearer it registered (`engine/LogClient.java`). A finished log the dispatcher uploaded is
served from the artifact store instead, ahead of any live read (`dispatcher-contract.md`, "Logs").

## Deployables, images and versioning

One git tag builds and pushes the whole compatible set (`.github/workflows/ci-release.yml:9-13`); the tag
patterns are `5.x.y`, `5.x.y-beta.z` and `5.x.y-rc.z`. `:latest` moves only on a stable tag (`:73-80`).
`sbom.yml:11-14` fires on the same patterns. Path-filtered `ci-core.yml`, `ci-dispatcher.yml`
and `ci-web.yml` test each module on push and pull request (`ci-core.yml:6-16`).

| Image | Built from | Runtime | Job in `ci-release.yml` |
| --- | --- | --- | --- |
| `boomerangio/flow-service-core` | the checkout, with the repository root as context: a build stage on `maven:3.9-eclipse-temurin-25` runs `mvn --projects service-core --also-make package` (`service-core/Dockerfile:10-26`) | `ubuntu/jre:25-26.04_edge`, Canonical's chiseled JRE pinned by digest (decision 0108): no shell, so JVM flags go in `JDK_JAVA_OPTIONS` and an internal CA is a mounted truststore named there; runs as uid 10001 in group 0; port 7700 (`application.properties:1`) | `deploy-core` (`:22`) |
| `boomerangio/flow-service-dispatcher` | the same shape for `service-dispatcher` and the SDK it is built on (`service-dispatcher/Dockerfile:6-22`) | same base, port 7702 (`service-dispatcher/.../application.properties:1`) | `deploy-dispatcher` (`:72`) |
| `boomerangio/flow-client-web` | the `client-web/` sources — a build stage in `client-web/Dockerfile` runs `pnpm install --frozen-lockfile` and `pnpm run build`; a second stage installs `dependencies` alone (`pnpm install --prod`: the serving packages, since the server bundle is self-contained, `client-web/vite.config.mts`) | `node:24-alpine` running `react-router-serve build/server/index.js`, port 3000 | `deploy-webapp` (`:156`) |

Every image is complete from a clean checkout; no job builds a jar first. Each build stage runs on the
builder's own platform (`--platform=$BUILDPLATFORM`): a jar and a browser bundle are the same for every
target, so the compile happens once and only the runtime stage is built per platform. A root
`.dockerignore` limits the Java context to the Maven modules. Every product image is built for
`linux/amd64` and `linux/arm64` (`ci-release.yml`, `platforms:` on each build step). A tag therefore runs
on an Apple-silicon laptop as well as an x86 cluster, and a Kubernetes pod spec has no per-platform
escape, so a single-architecture image simply fails to pull on the other.

Task images are not product images and are not on this list: the catalogue images and the `ai` worker
(`boomerangio/task-ai`) are built and released from the `boomerang-io/tasks` repository on their own version
lines, and the dispatcher resolves the `ai` one at runtime from `flow.dispatcher.ai.image` (see
`task-runtime.md`). One product tag still builds every product image (decision 0006).

The dispatcher SDK, `io.boomerang:dispatcher-sdk`, is published beside the images: the `publish-sdk` job sets its
version from the same tag and deploys it to this repository's GitHub Packages Maven registry
(`ci-release.yml:120-154`, `dispatcher-sdk/pom.xml:88-94`). No other module is published.

The dispatcher reaches core at `flow.engine.url`, `http://${flow.engine.service.host}` by default, and
authenticates with `flow.engine.dispatcher.token`
(`service-dispatcher/src/main/resources/application.properties:92-95,133-135`); core runs the dispatcher's `/dispatcher/**`
chain first (`dispatcher/DispatcherSecurityConfiguration.java:37-47`) and the product chain second
(`core/security/SecurityConfiguration.java:66`).

## Local stack

One compose file runs the stack two ways. `docker compose up -d` starts what a developer keeps in Docker:
`mongo` in a named volume, published on `127.0.0.1:27017` (`docker-compose.yml:28-37`), a local IDPZero OpenID
provider on `idp.localhost:4380` (`:51-69`), SeaweedFS as the artifact store (`:71-102`), and two one-shot
seeds that wait for core's migrations to create the settings they edit, wherever core runs: `auth-oidc-seed`
points the `auth` settings at IDPZero so sign-in resolves to `oidc`, and `task-deletion-seed` sets the task
deletion policy to Always so a finished run's storage claim can be released (`:105-127`,
`docker/mongo/*.js`). `service-core` then runs on the host under the `local` Spring profile
(`application-local.properties`) and `client-web` under React Router's dev server (`pnpm dev`), which calls
core at `http://localhost:7700` by default (`serverFetch.ts:26-27`). The `stack` profile adds both
to Docker instead, secured, with core on `:7700` and `client-web` on `:3000` calling it at
`http://service-core:7700` (`:183-258`); that is what the e2e job runs (`ci-e2e.yml`, `COMPOSE_PROFILES`).
`service-dispatcher` sits behind the `dispatcher` profile because it needs a Kubernetes cluster and the
kubeconfig at `docker/kube/config`; it reaches a host-run core by default (`:140-181`). The browser-facing
origin is `http://localhost:3000` either way; there is no separate gateway. The Playwright suite in `e2e/`
and the throughput harness in `load/` (`node load/run.mjs`, see `performance.md`) run against this stack.

## Not built

A local Docker dispatcher built on `dispatcher-sdk` and folding `lib-common` into `service-core` are planned,
not started; the dispatcher no longer depends on it. Serverless and warm-pool dispatchers wait for the two
additive contract changes they need (`dispatcher-contract.md`, "Where the contract ends").

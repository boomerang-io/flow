# Architecture

Boomerang Flow is one Maven monorepo of four Java modules (`pom.xml:9-14`) plus one pnpm web client, built
into three product images and two published libraries that ship together under a single product version.
Workflows are directed acyclic graphs (DAGs) executed by `service-core`; the work for each task is carried
out by a dispatcher, which is any process that implements the published contract (`dispatcher-contract.md`).
`service-dispatcher` is the Kubernetes one. A dispatcher runs as its own process only where a boundary
(credentials, network zone, trust, placement) separates it from core; otherwise it embeds `dispatcher-sdk`.

## Modules

| Module | Owns | Depends on |
| --- | --- | --- |
| `service-core` | The product server: the v2 REST API, authentication and authorization, workspaces, workflow definitions, the DAG execution engine, the dispatcher-facing v1 API, schedules, webhooks and outbound events, the GitHub and Slack integrations, and the database migrations it runs as it starts (`io.boomerang.migration`; see `data-model.md`). One Spring Boot application, `service-core/src/main/java/io/boomerang/Application.java:37-45`. | — |
| `dispatcher-sdk` | The dispatcher protocol as a software development kit (SDK): the client for every `/dispatcher/v1` route, the runtime that registers, polls, starts, renews leases and reports ends, and the `TaskHandler` interface a dispatcher implements (`dispatcher-contract.md`, "SDK"). It owns its wire models and is published to GitHub Packages beside `flow-sdk`. | `spring-web` and Jackson only (`dispatcher-sdk/pom.xml:54-62`) |
| `flow-sdk` | The public API as a library (`io.boomerang:flow-sdk`, package `io.boomerang.flow.sdk`): `Flow` (`flow-sdk/src/main/java/io/boomerang/flow/sdk/Flow.java`) built from a base URL and a bearer token, or the host's `RestClient`; `Runs` (submit, start, get, pause, resume, cancel, retry, query, count), `TaskRuns` (query, log), `Actions` (get, query, decide) and `Insights` (summary, tasks, workflow) with `RunQuery`, `TaskRunQuery`, `ActionQuery` and `InsightsQuery` builders mapped to the routes' parameters; `health()`, the actuator probe outside `/api/v2`; `Page<T>` with `content`, `total` and `next()`; its own wire models (records, no execution-state field) and `FlowApiException` carrying the server's `RestErrorResponse`. `ApiContractTest` (`flow-sdk/src/test/java/io/boomerang/flow/sdk/ApiContractTest.java`) round-trips every model it carries from `contracts/api-v2.yaml` and checks each builder sends exactly its route's parameters. Tested by `ci-sdk.yml`. | `spring-web` and Jackson only (`flow-sdk/pom.xml:46-53`) |
| `service-dispatcher` | Flow's Kubernetes dispatcher, built on `dispatcher-sdk` (`service-dispatcher/src/main/java/io/boomerang/config/DispatcherConfig.java:40-59`). Its task handler runs each task through the `io.boomerang.executor.TaskExecutor` interface (`service-dispatcher/src/main/java/io/boomerang/executor/TaskExecutor.java:12-28`) and its workflow handler provisions workspace volumes. `dispatcher.executor` selects `tekton` (the default, `TektonServiceImpl.java:57`) or `kube-jobs` (`KubeJobsExecutor.java:71`). A deployment has one `dispatcher.tasks.runtimeClassName` (`service-dispatcher/README.md:99-101`). | `dispatcher-sdk` (`service-dispatcher/pom.xml:30-34`) |
| `io.boomerang.common` (a package in `service-core`) | `service-core`'s storage and API model: 10 entities in `common/entity` (`WorkflowRunEntity`, `TaskRunEntity`, …), the public models (`WorkflowRun`, `TaskRun`, `Trigger`, …), enums (`RunStatus`, `RunPhase`, `TaskType`, …), `BoomerangError` and `RestErrorResponse`, and pure utilities (`Backoff`, `SweepRunner`, `ParameterUtil`, `DataAdapterUtil`). No beans, no repositories. The dispatcher wire has its own models in `dispatcher/model/`; `service-dispatcher` uses the SDK's. | — |
| `client-web` | The React 18 + React Router 7 web app with IBM Carbon, served by React Router's own server, `react-router-serve` (`client-web/package.json:15`), with server rendering on and base path `/apps/flow/` (`client-web/react-router.config.ts:11-17`). The browser talks only to this server; it calls `service-core` server-side at `CORE_SERVICE_INTERNAL_ORIGIN` (`client-web/src/Config/serverFetch.ts:34-36`). The root loader writes the deployment's runtime values into the page as `window._SERVER_DATA` (`client-web/app/root.tsx:29,45-46`, `client-web/app/serverData.server.ts`); the probe is `GET /apps/flow/health` (`client-web/app/routes/health.tsx`). | `service-core` over HTTP |

`service-core` and `service-dispatcher` inherit the root pom, whose parent is Spring Boot 4.1.1
(`pom.xml:15-20`, `service-core/pom.xml:11-15`); `dispatcher-sdk` has no parent and imports the same Spring
Boot version's dependency management for versions only (`dispatcher-sdk/pom.xml:28-50`). All three compile
for Java 25 (`service-core/pom.xml:18`, `service-dispatcher/pom.xml:12`, `dispatcher-sdk/pom.xml:27`). Each
service reads its `application.properties`, overridden by environment variables (`docker-compose.yml:195-214`).

## Deployment shape of `service-core` by properties

There is one shape of `service-core`: every bean loads in every deployment, and four properties in
`service-core/src/main/resources/application.properties` (`:14`, `:18`, `:43`, `:65`, in the order below)
express how a deployment is used. There is no separate engine image (`.github/workflows/ci-release.yml:31-36`).

| Property | Default | `true` | `false` |
| --- | --- | --- | --- |
| `flow.security.enabled` | `true` | `AuthenticationFilter` resolves a real token on every request and `@AuthCriteria` is enforced (`core/security/FlowSecurityProperties.java:19-20`; `authorization.md`). | Every request runs as the synthetic admin `UnauthenticatedGlobalToken`; the sign-in surface answers `mode=none`. |
| `flow.workspace.single` | `false` | The seeded `system` workspace is the only one: any other `{workspace}` path segment is refused with `WORKSPACE_INVALID_REF` (`core/security/SingleWorkspaceInterceptor.java:32-48`, registered on `/api/v2/workspace/{workspace}` and `/api/v2/workspace/{workspace}/**` by `SingleWorkspaceInterceptorConfiguration.java:22-26`), and `WorkspaceService.create` and `delete` refuse with `WORKSPACE_INVALID_REQ` (`workspace/WorkspaceService.java:163,331`). Every other route, the workspace and profile reads included, is the standard one. | Any workspace. |
| `flow.dispatcher.auth.enabled` | `true` | `/dispatcher/**` requires a Flow token with a machine actor kind (`DispatcherAuthFilter`), whatever `flow.security.enabled` says. | No dispatcher authentication; local development only. |
| `flow.queue.enabled` | `true` | Dispatchers claim task runs. | Claiming stops (`dispatcher/DispatcherService.java:340-343`); the recovery sweeps keep running (`execution-model.md`). |

Two shapes are the worked examples:

| Shape | Properties | Used for |
| --- | --- | --- |
| The full product (the defaults) | `flow.security.enabled=true`, `flow.workspace.single=false` | The compose stack and every product install: workspaces, sign-in, tokens, integrations, schedules, the webapp. |
| An embedded engine | `flow.security.enabled=false`, `flow.workspace.single=true` | Headless execution inside another product that owns identity and tenancy. The webapp runs unchanged against it; it has no sign-in, so it MUST only run for local development or behind a protected route. |

Quotas are not a deployment property: the stored `features.workspaceQuotas` setting is the only switch
(`workflow/QuotaService.java:91-96`). The `system` workspace is seeded with four limits at `0`, which is
unlimited, and no run duration of its own, so its runs take the platform ceiling from the `quotas` settings
(`service-core/src/main/resources/seed/workspace.json`, `workflow/QuotaService.java:175-186`). Deployment
shape is a `flow.<subsystem>.<switch>` property; a runtime product toggle is a `features.<name>` stored
setting; a switch lives in exactly one of the two.

The webapp never reads a property. It renders from the flags `GET /api/v2/features` returns, and a flag is on
only when its setting is on and the shape leaves a surface behind it (`core/FeatureService.java:31-86`):

| Flag | Value |
| --- | --- |
| `workspace.single` | `flow.workspace.single`: when `true`, no workspace switcher, no create or leave |
| `workspace.management`, `user.management` | the setting, and `false` under `flow.workspace.single` |
| `workspace.quotas`, `workflow.triggers`, `workspace.parameters`, `global.parameters`, `activity`, `insights`, `workspace.tasks` | the setting of the same name in the `features` document |
| `enable.verified.tasks.edit` | the `task` document's `edit.verified` setting |
| `schedules`, `integrations` | `true` |
| `authentication` | `flow.security.enabled` |
| `tokens`, `workflow.tokens` | `flow.security.enabled` (and the setting, for `workflow.tokens`): with security off a token has nothing to do |

The server-built menu (`core/NavigationService.java`) reads the same flags. When `authentication` is `false`
the webapp's startup loader never requests `GET /internal/v1/auth/config`
(`client-web/src/Features/App/App.tsx:142-146`).

## Feature packages inside `service-core`

`service-core` is one Maven module with eight flat feature packages under `io.boomerang`, plus `common` (the
domain model) and `migration` (the change units). Boundaries are a convention: there is no Spring Modulith and
no ArchUnit rule; a package is a directory, and reviews enforce the direction below.

| Package | Owns | Imports from, besides `common` (from `^import io.boomerang.*`) |
| --- | --- | --- |
| `core` | Users, tokens, roles, settings, audit, the relationship graph (`RelationshipService`), security (filters, `AuthCriteriaAuthorizationManager`, `@AuthCriteria`), the shared `RestConfig` and `MongoConfiguration`, the OpenAPI configuration, the one `@EnableScheduling` (`core/config/SchedulingConfiguration.java:14`). | three entity imports in one class (below) |
| `workspace` | Workspaces, members, the workspace and profile routes; its quota routes read through `workflow/QuotaService`. | `core`, `workflow` |
| `workflow` | Workflow and revision definitions, the task catalogue, read-only workflow templates, global parameters and the parameter layers (`ParamLayerService`), actions (approvals), artifacts, quotas (`QuotaService`), run retention and the deleted-workflow prune (`WorkflowRetentionService`), Insights (`WorkspaceInsightsControllerV2`, `WorkflowRunInsightService`), the workspace-scoped v2 controllers, Tekton YAML conversion. | `core`, `engine`, `workspace` and `event` (entities, models and repositories), `schedule` (`ScheduleService`, through a lazy provider, `workflow/WorkflowService.java:41,714`) |
| `engine` | Execution: `WorkflowExecutionService`, `TaskExecutionService`, `TaskRunService`, `DAGUtility`, `ParameterManager`, `WorkflowRunStateHelper`, `WorkflowWatcher`, `LogClient`, task locks. | `core`, `workflow`, `dispatcher` (the `dispatchers` entity and repository, `engine/WorkflowWatcher.java:18-19`, `engine/LogClient.java:6-7`) |
| `dispatcher` | The worker registry and the v1 wire: `DispatcherControllerV1`, `DispatcherService`, `ClaimFilterService`, `DispatcherAuthFilter`, `DispatcherSecurityConfiguration`. | `core`, `engine`, `workflow` |
| `schedule` | Cron and one-off schedules and their firing: `ScheduleService`, `ScheduleWatcher`, `ScheduleJob`. | `core`, `engine` (`EngineConstants`), `workflow` |
| `event` | Webhook and CloudEvent ingress (`WebhookEventControllerV2`), the transactional outbox (`CloudEventsBridge`, `OutboxDispatcher`, `OutboxControllerV2`), the event sink. | `core`, `engine`, `integrations`, `workflow` |
| `integrations` | GitHub App and Slack. | `core`, `workflow` |

Rules that follow from the table: `core` MUST NOT import any feature package; the one exception is the save hook
that seals secret values, which names the three feature entities it seals
(`core/config/SecretValueMongoListener.java:11-13`). The engine MUST NOT call the platform side synchronously:
it publishes Spring `ApplicationEvent`s that other packages listen to (`ScheduleRequested`, handled by
`schedule/ScheduleEventListener.java:26-27`; `ChildWorkflowRunCreated`, handled by
`core/RelationshipEventListener.java:36-37`). The transition records those listeners and the audit and outbox
bridges read live in `common/model`. Controllers live in the package of the service they inject.

## How a workflow run moves through the system

Each arrow is a direct in-process call unless marked. Every state change is a compare-and-set: a
`findAndModify` on the run document whose query names the expected prior state, and a losing caller does
nothing (`engine/TaskRunService.java:905-916`).

| Step | Class → class | Where |
| --- | --- | --- |
| 1. Submit | `POST /api/v2/workspace/{workspace}/workflow/{name}/submit` → `WorkspaceWorkflowControllerV2.submitWorkflow` → `WorkflowService.submit` → `internalSubmit` (the quota checks) → `submit(workflowId, …)` → `WorkflowRunService.run` | `workflow/WorkspaceWorkflowControllerV2.java:273-314`, `workflow/WorkflowService.java:560,616,1812,1916`, `workflow/WorkflowRunService.java:792-794` |
| 1a. Other entry points | A schedule firing (`ScheduleWatcher.sweep` → `ScheduleJob.execute` → `WorkflowService.submit`), a webhook or CloudEvent (`WebhookEventControllerV2` → `WebhookEventService` → `WorkflowService.submit`), or a Run workflow task (`TaskExecutionService` → `WorkflowService.submit`) | `schedule/ScheduleWatcher.java:136`, `schedule/ScheduleJob.java:76`, `event/WebhookEventControllerV2.java:116,245`, `event/WebhookEventService.java:117,139`, `engine/TaskExecutionService.java:1173` |
| 2. Queue the run | `WorkflowRunService.run` saves the `WorkflowRunEntity` and calls `WorkflowExecutionService.queue`, which resolves the run's params, builds the task runs from the revision (`DAGUtility.createTaskList`), validates the graph, and admits the run with `WorkflowRunStateHelper.tryAdmit` | `engine/WorkflowExecutionService.java:62-85`, `engine/WorkflowRunStateHelper.java:158-167` |
| 3. Start the run | `WorkflowRunService.start` → `WorkflowExecutionService.start`, which requires phase `pending` or `queued` and executes the DAG on `asyncWorkflowExecutor`. A run with a workspace is left for its dispatcher to provision and start | `workflow/WorkflowRunService.java:796-804,843`, `engine/WorkflowExecutionService.java:107-131` |
| 4. Admit each task | The DAG walk calls `TaskExecutionService.queue(taskRunId)`. This is the single pause gate: a run with `pauseRequestedAt` set admits nothing. Admission resolves the task's params, then `TaskRunService.tryAdmit` moves it to `ready`. Seven types wait for a dispatcher: `template`, `script`, `custom`, `generic`, `ai`, `uploadartifact` and `downloadartifact`; the engine runs every other type itself | `engine/TaskExecutionService.java:142,176,201,244-260`, `engine/TaskRunService.java:362-377` |
| 5. Dispatcher claims | `service-dispatcher`, through the SDK, long-polls `GET /dispatcher/v1/{id}/tasks` (HTTP) for no more than its free slots; while full it polls with `limit=0`, which claims nothing and still hands out terminate orders. The engine holds the request up to 30 s, re-checking when a task is admitted on that instance (the workflow poll, when a run with a workspace is submitted to start) and every `flow.dispatcher.poll.recheck-ms` (one second by default); `DispatcherService.getTaskQueue` pages `TaskRunService.findClaimable` for the dispatcher's filter and claims each candidate with `TaskRunService.tryClaim`; only claimed documents are returned. `flow.queue.enabled=false` stops claiming | `dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:259-282`, `dispatcher/DispatcherControllerV1.java:103-143`, `dispatcher/DispatcherService.java:338-418`, `engine/TaskRunService.java:116,317` |
| 6. Execute | The SDK's `TaskRunner` calls `PUT /dispatcher/v1/taskrun/{id}/start`, then the dispatcher's `TaskService.run` → `TaskExecutor.create` and `watch` (a Tekton `TaskRun` or a `batch/v1` `Job`), then `PUT …/taskrun/{id}/end` with the results (HTTP), retried until the engine takes it | `dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/TaskRunner.java:121-191`, `service-dispatcher/src/main/java/io/boomerang/dispatcher/TaskService.java:89,137-138` |
| 7. Results back | `DispatcherControllerV1.startTaskRun` and `endTaskRun` → `DispatcherService` → `TaskRunService.start` and `end` → `TaskExecutionService.start` and `end`. A request that names its dispatcher must match `claim.by` on a task in `queued` or `running`, or it is refused with `TASKRUN_CLAIM_SUPERSEDED`; `claim.seq` is not on the wire | `dispatcher/DispatcherControllerV1.java:193-221`, `engine/TaskRunService.java:943,995,1186-1201`, `engine/TaskExecutionService.java:274,494` |
| 8. Advance the DAG | `TaskExecutionService.end` → `executeNextStep` → back to step 4 for each dependant, or `finishWorkflow` → `WorkflowRunStateHelper.tryComplete`. `completed` is terminal; a run's workspace storage is released separately, when the dispatcher asks `POST /dispatcher/v1/workspaces/releasable` which of the runs it holds volumes for are finished | `engine/TaskExecutionService.java:630,1452,1491`, `engine/WorkflowRunStateHelper.java:205`, `dispatcher/DispatcherService.java:498` |
| 9. Events out | Each compare-and-set publishes a `TaskRunTransition` or `WorkflowRunTransition` `ApplicationEvent`; `CloudEventsBridge` inserts a row into `events_outbox`; `OutboxDispatcher.drain` delivers it as a CloudEvent through `EventSinkService` every `flow.events.outbox.interval-ms` (5 s; the sink is off by default, `service-core/src/main/resources/application.properties:51`) | `engine/TaskRunService.java:918-928`, `event/CloudEventsBridge.java:34-67`, `event/OutboxDispatcher.java:64-67` |
| 10. Recovery | `WorkflowWatcher.sweep` runs fourteen isolated sweeps (timeouts, stalled runs, for-each recovery, claims from gone dispatchers, lapsed leases, deleted workflows, artifact expiry, …); retention runs on its own hourly schedule; any core instance may reap any run | `engine/WorkflowWatcher.java:153-175,192-199` |

Core calls a dispatcher for one thing: a task's live log, read from the dispatcher holding the claim at the URL
and with the bearer it registered (`engine/LogClient.java:81-90`). Once a task has ended, the log its
dispatcher uploaded is served from the artifact store; while it runs, the live read comes first and a copy
stored during this attempt answers only when the dispatcher cannot (`engine/TaskRunService.java:1200-1221`;
`dispatcher-contract.md`, "Logs").

## Deployables, images and versioning

One git tag builds and pushes the whole compatible set (`.github/workflows/ci-release.yml:9-12`); the tag
patterns are `5.x.y`, `5.x.y-beta.z` and `5.x.y-rc.z`, and `:latest` moves only on a stable tag (`:69-72`).
`.github/workflows/sbom.yml:10-14` fires on the same patterns and weekly, and scans the three images it builds
from the checkout. Path-filtered `ci-core.yml`, `ci-dispatcher.yml` and `ci-web.yml` test each module on a
push to `main` and on pull requests (`.github/workflows/ci-core.yml:6-19`); `ci-dispatcher.yml` also builds the
dispatcher image without pushing it. `ci-e2e.yml` builds the core and webapp images and runs the browser suite
against them; it is the only pull-request build of the core image (`.github/workflows/ci-e2e.yml:40-67`).
Every image build reads GitHub's layer cache, one scope per image, and `ci-dispatcher.yml`, `ci-e2e.yml` and `ci-release.yml` write it
(`ci-release.yml:85-86`), so a release starts from the layers `main` last built.

| Image | Built from | Runtime | Leg of the `deploy` matrix job in `ci-release.yml` |
| --- | --- | --- | --- |
| `boomerangio/flow-service-core` | the checkout, with the repository root as context: a build stage on `maven:3.9-eclipse-temurin-25` runs `mvn --projects service-core --also-make package` and extracts the jar to `service.jar` plus `lib/` (`service-core/Dockerfile:11-32`); a train stage on the runtime JRE starts the application once, with no database, to record the JDK AOT cache (`service-core/Dockerfile:45-66`, `performance.md`, "Start-up") | `ubuntu/jre:25-26.04_edge`, Canonical's chiseled Java runtime pinned by digest (`service-core/Dockerfile:68`): no shell, so Java options go in `JDK_JAVA_OPTIONS` and an internal certificate authority is a mounted truststore named there; the command reads the recorded cache and turns compact object headers on (`service-core/Dockerfile:84-85`); runs as uid 10001 in group 0; port 7700 (`service-core/src/main/resources/application.properties:1`) | `deploy-core` (`ci-release.yml:33`) |
| `boomerangio/flow-service-dispatcher` | the same shape for `service-dispatcher` and the SDK it is built on (`service-dispatcher/Dockerfile:7-27`), with a train stage that starts it against no cluster (`service-dispatcher/Dockerfile:34-44`) | the same base and command; port 7702 (`service-dispatcher/src/main/resources/application.properties:1`) | `deploy-dispatcher` (`ci-release.yml:37`) |
| `boomerangio/flow-client-web` | the `client-web/` sources: a build stage runs `pnpm install --frozen-lockfile` and `pnpm run build`; a second stage installs the `dependencies` alone (`pnpm install --prod`), which are the serving packages, since the server bundle is self-contained (`client-web/Dockerfile:14-48`) | `node:24-alpine` running `react-router-serve build/server/index.js` on node alone, with npm and corepack removed and Alpine's packages upgraded at build; runs as uid 2000 in group 0; port 3000 (`client-web/Dockerfile:53-76`) | `deploy-webapp` (`ci-release.yml:41`) |

Every image is complete from a clean checkout; no job builds a jar first. Each build stage runs on the
builder's own platform (`--platform=$BUILDPLATFORM`): a jar and a browser bundle are the same for every target,
so the compile happens once and only the train and runtime stages are built per platform, since an AOT cache is
tied to the JVM build that records it. A root `.dockerignore` limits the
Java context to the Maven modules. Every product image is built for `linux/amd64` and `linux/arm64`
(`ci-release.yml:80`), so a tag runs on an Apple-silicon laptop as well as an x86 cluster; a Kubernetes pod spec
has no per-platform escape, so a single-architecture image fails to pull on the other.

Task images are not product images: the catalogue images and the AI worker (`boomerangio/task-ai`, which the
`ai-prompt` and `ai-agent` revisions pin by tag) are built and released from the `boomerang-io/tasks`
repository on their own version lines, and the dispatcher runs the image an `ai` revision declares, under an
allowed prefix (`dispatcher-kubernetes.md`).

Two libraries are published beside the images, `io.boomerang:dispatcher-sdk` and `io.boomerang:flow-sdk`:
the `publish-sdk` job runs once per library, sets its version from the same tag and deploys it to this
repository's GitHub Packages Maven registry (`ci-release.yml:96-127`, `dispatcher-sdk/pom.xml:97-103`,
`flow-sdk/pom.xml:87-93`). A library's version is therefore the server version it was built against; an
application picks the `flow-sdk` of the server it runs against, and a newer server still serves an older
library because `/api/v2` only gains routes and fields within 5.x (`api-contract.md`). No other module is
published.

The dispatcher reaches core at `flow.engine.url`, `http://${flow.engine.service.host}` by default, and
authenticates with `flow.engine.dispatcher.token` (`service-dispatcher/src/main/resources/application.properties:86-87,143`);
core runs the dispatcher's `/dispatcher/**` filter chain first (`dispatcher/DispatcherSecurityConfiguration.java:37-47`)
and the product chain second (`core/security/SecurityConfiguration.java:89-90`).

## Local stack

One compose file runs the stack two ways. `docker compose up -d` starts what a developer keeps in Docker:
`mongo` in a named volume, published on `127.0.0.1:27017` (`docker-compose.yml:28-40`), a local IDPZero OpenID
Connect provider at `idp.localhost:4380` (`:51-65`), SeaweedFS as the artifact store and a one-shot that
creates its bucket (`:71-97`), and two one-shot seeds that wait for core's migrations to create the settings
they edit, wherever core runs: `auth-oidc-seed` points the `auth` settings at IDPZero so sign-in resolves to
`oidc`, and `task-deletion-seed` sets the task deletion policy to Always so a finished run's storage claim can
be released (`:105-127`, `docker/mongo/*.js`). `service-core` then runs on the host under the `local` Spring
profile (`service-core/src/main/resources/application-local.properties`) and `client-web` under React Router's
dev server (`pnpm dev`), which calls core at `http://localhost:7700` by default
(`client-web/src/Config/serverFetch.ts:31-35`).

The `stack` profile adds both to Docker instead, secured, with core on `:7700` and `client-web` on `:3000`
calling it at `http://service-core:7700` (`docker-compose.yml:188-271`); that is what the e2e job runs
(`.github/workflows/ci-e2e.yml:35`). `service-dispatcher` sits behind the `dispatcher` profile, which naming
the service on the command line enables, because it needs a Kubernetes cluster and the kubeconfig at
`docker/kube/config`; it runs the `kube-jobs` executor and reaches a host-run core by default
(`docker-compose.yml:140-184`). The browser-facing origin is `http://localhost:3000` either way; there is no
separate gateway. The Playwright suite in `e2e/` and the throughput harness in `load/` (`node load/run.mjs`,
see `performance.md`) run against this stack.

## Not built

A local Docker dispatcher built on `dispatcher-sdk` is planned, not started. Serverless and warm-pool
dispatchers wait for the two additive contract changes they need (`dispatcher-contract.md`, "Where the
contract ends").

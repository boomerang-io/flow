# Performance and Scaling

Flow scales by running identical `service-core` instances against one MongoDB with no leader, no
partitioning, and no distributed lock: every state change is a single-document compare-and-set (CAS), so
any instance may do any job and duplicates are no-ops. This records what is measured, configured, and assumed.
Paths are under `service-core/src/main/java/io/boomerang/` unless they start with a module name.

## Running several instances

Every instance runs the full API, the claim endpoints, and every sweep; nothing is pinned to an
instance. Correctness rests on three primitives, all in the live path:

| Primitive | Where | What it guarantees |
| --- | --- | --- |
| Claim CAS (`findAndModify` on `_id` + full eligibility) | `engine/TaskRunService.java:327-365` | One dispatcher owns a TaskRun; losers get `null` and skip. Each claim increments `claim.seq` (`:350`). A dispatcher's start and end are refused unless it still holds the claim (`claim.by`, `:1307-1322`), and the engine's own requeue and abandon fence on the `claim.seq` they read (`:963-967`), so a superseded claimant cannot write results. |
| Status-CAS transitions (`transition`, `tryComplete`, `tryRequeue`) | `engine/TaskRunService.java:538-614,776-846,972-985`, `engine/WorkflowRunStateHelper.java` | Concurrent advance from two instances converges: the second CAS finds the state already changed and performs no side effects. |
| Level-triggered sweeps on every instance | `engine/WorkflowWatcher.java:44-49,172-198` | Any survivor reaps a crashed instance's or dispatcher's work; overlapping sweeps are harmless because each acts only through the CAS above. |

Crash recovery is the lease the claim sets 90 s ahead (`flow.dispatcher.lease-ms`,
`service-core/src/main/resources/application.properties:73`; `engine/TaskRunService.java:343`) and each 30 s
heartbeat renews (`engine/TaskRunService.java:662-674`; `flow.dispatcher.lease.beat-ms`,
`service-dispatcher/src/main/resources/application.properties:132`), served by the sparse `lease_sweep` index
(`migration/_0021__Indexes.java:64`), which holds only live claims because completion clears the lease
(`engine/TaskRunService.java:637-644`); the absolute `timeoutAt` written at claim = now + timeout + 5 s grace
(`engine/RunTimeouts.java:15-19`, `engine/EngineConstants.java:16`); and the gone-dispatcher sweep below.

Two caches are per instance and trade a bounded staleness for fewer reads; a write clears them only on the
instance that took it.

| Cache | Holds | Lifetime / size | Another instance sees a write |
| --- | --- | --- | --- |
| `core/security/TokenLookupCache.java` | Validated tokens by hash | 60 s / 10,000 | A revoked token is honoured for up to 60 s |
| `core/ParamLayerCache.java` | Global, workspace and context parameter layers by workflow and revision; revisions by id | 10 s / 1,000 | A parameter, setting or revision edit reaches its task admissions within 10 s |

Without the parameter cache each task admission and each for-each item reads the revision, the workflow, the
features setting, the global parameters and the workspace: five reads. With it, the tasks of every run of one
revision admitted within 10 s share those five. A workflow's owning workspace is cached apart
with no time limit, because a workflow never changes workspace (`core/ParamLayerCache.java:38-39,88-94`). Every
global parameter, workspace, setting, workflow and revision write calls `evictAll`
(`workflow/ParameterService.java:104,157,172`, `workspace/WorkspaceService.java:237,335,645`,
`core/SettingsService.java:199,221`, `workflow/WorkflowService.java:1652,2091`); token writes do not, because the
scheduler mints a token on every fire, so a new workflow token reaches the context within 10 s. Redaction reads
secured values uncached (`workflow/ParamLayerService.java:194-207`).

## Queue fairness and the indexes behind it

The claim page is oldest-first with backoff exclusion inside the query, so a retried task cannot
starve fresh work and a burst of one workflow's tasks cannot jump the queue.
`claimableQuery` (`engine/TaskRunService.java:130-154`) selects `status=ready`, `phase=pending`, `type` in the
dispatcher's registered types, `claim.by` absent, and `retry.after` absent or elapsed, narrowed by the poll's
task and workflow filters, sorted by `creationDate`, projected to `_id` and `timeout`, then claims each candidate
by CAS (page-then-CAS, `dispatcher/DispatcherService.java:428-440`). Backoff is one generic class: 10 s base,
doubling per attempt, 5 min ceiling, up to 5 s jitter (`common/util/Backoff.java:15-34`), with a budget of 3
requeues (`engine/EngineConstants.java:43`).

A for-each task adds up to `max.foreach.items` (default 256, `service-core/src/main/resources/seed/settings.json`)
claimable items at once, each created at fan-out time (`engine/TaskExecutionService.java:888-890`), so a large fan-out
queues behind older work and ahead of newer work like any other burst. Each item end checks its parent with an
`exists` and a `count` and loads no item; only the last end loads the items, projected
(`engine/TaskRunService.java:504-519`).

Dispatchers long-poll for 30 s, re-querying every `flow.dispatcher.poll.recheck-ms` (1000 by default, never under
100) with a page of 20 (`dispatcher/DispatcherService.java:58-75`, `:380`), and reconnect as soon as a poll returns
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:114-122`). Each connected dispatcher
therefore costs about 4 indexed queries per re-check when idle (the task claim page, the two termination pages and
the run provision page), all on `claim_page`, and one request every 30 s per queue; a dispatcher's idle queries per
second scale with its pools × 1000 / the interval. Admitting a task wakes the task polls held on that engine instance
whose filter can claim it, by type and, when the poll filtered on them, task and workflow, and each re-runs its claim
page at once; submitting a run with a workspace to start wakes the workflow polls alone
(`dispatcher/PollWakeups.java:41-160`, `engine/TaskExecutionService.java:278-280`,
`workflow/WorkflowRunService.java:885-888`, `dispatcher/DispatcherService.java:397-400`, `:562-584`): one indexed query
per admission per poll that can claim the task, none for the others and none while nothing is admitted, so a
dispatcher holding eight typed pools costs one find per admission, not eight
(`DispatcherRequestDatabaseCommandsTest.anAdmissionWakesOnlyThePollsWhoseFilterMatches`). The two termination
pages run once per re-check interval for a dispatcher and filter, never on an admission wake and not again on a poll
re-opened within the interval: the due time lives in a map keyed by dispatcher and filter that successive polls
share (`:395`, `:487-499`), so a held task poll's three looks - entry, a wake, the timed re-check - cost 3, 1 and 3
claim-page finds, and a poll re-opened right after a claim costs 1 on its first look
(`DispatcherRequestDatabaseCommandsTest`). They also run at once when a task the dispatcher holds ends cancelled or
timed out or is parked for retry on this instance, which wakes that dispatcher's polls alone
(`engine/TaskRunService.java:619-634`, `:843`, `DispatcherPollWakeTest.aCancelOfAHeldTaskWakesTheHoldersPollAtOnce`),
so a cancel reaches the running task within the end round trip and the timed turn is the safety net for a holder
polling another instance. Under load, where a poll returns on every claim and is re-opened at once, the shared
cadence is the difference between one and three finds per claim. A hop in a sequential chain costs the end round trip, the admission and the claim round trip, independent of
the interval. Measured by an embedder on one engine and one in-process dispatcher, the same 41-hop scenario before
and after: the hop from a task's end to the next task's start fell from 0.88 s median and 1.03 s p90 to 0.05–0.11 s
median and 0.09–0.26 s p90, with the interval at its 1 s default. The interval bounds the wait for a task that becomes claimable without an admission
on the polled instance: a `retry.after` that elapses, an admission on another replica, or a slot that frees while
the engine holds a full dispatcher's `limit=0` poll. A multi-replica engine whose chains hop often can trade queries
for that latency: one dispatcher with seven pools at 200 ms costs 35 queries/s instead of 7. A dispatcher holds at most `flow.dispatcher.task.max-in-flight` tasks (25,
`service-dispatcher/src/main/resources/application.properties:98`), a Pending pod and a retried end report
included, and each poll claims no more than its free slots, so under a backlog the work it cannot run stays
claimable by other dispatchers (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/InFlightTasks.java:29-33`,
`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:342-365`).
`flow.queue.enabled=false` stops claiming only; sweeps keep running (`dispatcher/DispatcherService.java:69-71,259-262,364-367`).
While claiming is off the engine answers each poll at once with 204, and the dispatcher then waits 5 s between
polls (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:23`).

Indexes come only from migration change units: `_0021__Indexes` holds the inventory, and
`_0029__ClaimIndexKeysTheClaimant` re-keys `claim_page` on a database built before the index held `claim.by`
(`migration/_0029__ClaimIndexKeysTheClaimant.java:39-66`). Entity annotations are inert
(`spring.data.mongodb.auto-index-creation=false`, `service-core/src/main/resources/application.properties:118`).

| Index | Collection / keys | Defined at (`migration/_0021__Indexes.java`) | Serves |
| --- | --- | --- | --- |
| `claim_page` | `task_runs {type, status, phase, claim.by, creationDate}` | `:58-62` | `claimableQuery` and its sort (a claimable task run has no `claim.by`, which the index holds as null); the two termination pages, which ask for a string `claim.by` and so read only held task runs: 3 documents for 3 held among 2,003 cancelled, where the earlier keys read all 2,003 (`ClaimIndexQueryPlanTest`) |
| `node_uniqueness` (unique) | `task_runs {workflowRunRef, name}` | `:68-72` | One TaskRun per DAG (directed acyclic graph) node; duplicate creation fails at insert |
| `run_tasks` | `task_runs {workflowRunRef, status, name}` | `:63` | A run's task runs; the Insights reads of a period's task runs and its one usage aggregation per response, matched on the counted runs' ids (`workflow/WorkflowRunInsightService.java:526,570,645`) |
| `timeout_sweep`, `wait_sweep` (sparse) | `task_runs {timeoutAt}`, `{waitUntil}` | `:65-66` | `reapTaskTimeouts`, `resumeDueWaitingTasks` |
| `claimed_sweep` | `task_runs {phase, claim.at}` | `:67` | `reapClaimsFromGoneDispatchers` |
| `parent_index` (sparse) | `task_runs {parentRef, index}` | `:74` | A for-each parent's items: an item end's `exists` and `count`, and the last end's projected load |
| `label_wildcard` | `task_runs {labels.$**}`, `workflow_runs {labels.$**}` | `:75,94` | Label filters on run and task-run queries |
| `claim_page`, `timeout_sweep`, `paused_lookup`, `purge_sweep` | `workflow_runs {status, phase, creationDate}`, `{timeoutAt}`, `{pauseRequestedAt}`, `{purgeRequestedAt}` | `:76-80` | The run provision page and `recoverStaleProvisionClaims`; `reapWorkflowTimeouts`; `purgeRequestedRuns` |
| `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase` | `workflow_runs {phase, creationDate}`, `{phase, startTime}`, `{workflowRef, phase}` | `:80-83` | The retention page of `purgeExpiredRuns`; `recoverStalledRuns`; `cancelDeletedWorkflowRuns` |
| `workflow_ref_creation`, `workflow_ref_status`, `initiated_by_phase` | `workflow_runs {workflowRef, creationDate}`, `{workflowRef, status}`, `{initiatedByRef, phase}` | `:84-93` | The quota run counts; the retention lineage check and the cascade cancel |
| `status_sweep` | `actions {status, creationDate}` | `:138` | `closeStrayActions` |
| `dispatch_page`, `sent_ttl` (7-day expiry) | `events_outbox {status, occurredAt}`, `{sentAt}` | `:112-117` | Outbox drain; delivered rows expire |

## Sweep cadences and their properties

Every sweep runs on every instance with a random start offset; correctness never depends on ticks not
colliding. The sweeps' paged queries carry `maxTimeMsec(5000)` so a slow database cannot hang the sweeper
(`engine/TaskRunService.java:656,689,707,723,896`, `engine/SweepCursor.java:77-82`,
`engine/WorkflowRunStateHelper.java:121,255,463,527,542`), and each sweep runs isolated, so a timeout in one does not stop
the rest (`engine/WorkflowWatcher.java:178-198`).

| Sweeper | Property (default) | Start jitter | Page | Notes |
| --- | --- | --- | --- | --- |
| `DueWaitPoller` (`engine/DueWaitPoller.java:21`) | `flow.watcher.wait-interval-ms` (1000); same `flow.watcher.enabled` switch | none | 50 | `resumeDueWaitingTasks` on its own virtual thread, not the scheduler's: due sleeps and `acquirelock` retries end within about a second. One query on the sparse `wait_sweep` index a second per instance, usually empty; a resume holds the wait with a 60 s lease on `waitUntil` |
| `WorkflowWatcher.sweep` (16 sweeps, `engine/WorkflowWatcher.java:172-198`) | `flow.watcher.interval-ms` (30000); `flow.watcher.enabled` (true); `flow.watcher.provision-claim-grace-ms` (300000) | up to 30 s (`:168-171`) | 50 (`engine/EngineConstants.java:19`) | Stall grace 60 s (`WorkflowWatcher.java:59`); dispatcher declared gone after 60 s without a poll (`:87`) and its registration deleted after 7 days (`:93`, `:534`); a provisioning claim released after the grace, the run failed on its third (`:75,117-120`). The stalled-run, for-each and stray-action sweeps resume after the last row they read, so rows they leave alone do not hide the ones behind (`engine/SweepCursor.java:35-60`); the gone-dispatcher reap reads the live dispatchers once and pages only the other dispatchers' claims (`WorkflowWatcher.java:504-527`); `pruneDeletedWorkflows` hard-deletes a deleted workflow's documents once its runs finish (`workflow/WorkflowRetentionService.java:257-290`) |
| `WorkflowWatcher.purgeExpiredRunsOnSchedule` (`engine/WorkflowWatcher.java:218-223`) | `flow.watcher.retention-interval-ms` (3600000); same `flow.watcher.enabled` switch | up to 60 s | 50, at most 20 pages a pass (`workflow/WorkflowRetentionService.java:61`) so the single scheduler thread returns to the recovery sweeps; a backlog drains over several passes | Finished runs past the `workflowrun`/`retention.days` setting (365, floored at 60, `WorkflowRetentionService.java:53-57`) are purged with their task runs, actions, artifacts (stored logs included) and relationship node (`WorkflowRetentionService.java:208-248,314-322`); two lineage checks per candidate, `existsByInitiatedByRef` on the run id and `existsByInitiatedByRefIn` on its task-run ids (read by id only on `run_tasks`), both on the `initiated_by_phase` index |
| `AuditRetentionService.reapplyOnChange` (`core/audit/AuditRetentionService.java:56-65`) | `flow.audit.retention-interval-ms` (60000) | none | none | Reads the `audit` settings document and runs `collMod` on the `createdAt_ttl` index only when the floored `retentionDays` differs from what this instance last applied, so a change made through the settings API reaches every instance within one interval without a restart; a failed apply is retried on the next tick. One indexed read a minute per instance |
| `ScheduleWatcher.sweep` | `flow.schedule.watcher.interval-ms` (30000); `flow.schedule.watcher.enabled` (true) | up to 30 s (`schedule/ScheduleWatcher.java:65-67`) | 50 | Cron fires by a `nextFireAt` CAS; 3 retries per failed fire (`:37`) |
| `OutboxDispatcher.drain` | `flow.events.outbox.interval-ms` (5000); bean exists only when `flow.events.sink.enabled=true` (`event/OutboxDispatcher.java:34-37`) | up to 5 s (`:64-66`) | 50 | 3 delivery attempts, then the row is marked dead (`:43`) |
| `GitHubService.refreshSharedTokens` (`integrations/GitHubService.java:191-228`) | none: every 45 minutes, inside the hour an installation token lives | up to 60 s | all GitHub installations | Each workspace sharing the token gets one GitHub call and one targeted write. Instances do not coordinate, so N instances mint N tokens per workspace per 45 minutes; any of them is valid |

No interval is set in `application.properties`; the defaults come from the `@Scheduled` fallbacks. Cost
(assumed, not load-tested): one paged indexed query per sweep plus a batched or per-row check, so 20 instances add
well under 10 fixed queries per second; per-row checks grow with in-flight runs.

## Virtual threads

Request handling runs on virtual threads (`spring.threads.virtual.enabled=true`,
`service-core/src/main/resources/application.properties:132`) because each connected dispatcher parks one request
thread for its 30 s poll; the dispatcher sets it too (`service-dispatcher/src/main/resources/application.properties:9`),
where a live log stream parks its request thread for up to two hours. **Measured**: `service-core/src/test/java/io/boomerang/dispatcher/DispatcherPollerVirtualThreadTest.java`
drives 200 pollers through 10 cycles each under Java Flight Recorder and asserts carrier pinning stays under 1% of
the poll cycles, so pins are one-time warm-up rather than per claim and dispatcher count is not bound by the
platform-thread pool. **Not measured**: any before/after throughput comparison; the idle poll's database load
(above) is the remaining known cost.

`@Async` work runs on virtual threads too, one thread per hand-off, from three named
`SimpleAsyncTaskExecutor` beans in `service-core` and one in the dispatcher. The property alone does not reach
them: Boot's virtual-thread `applicationTaskExecutor` backs off whenever the context already declares an
`Executor` bean, so each of these beans asks for virtual threads itself.

| Bean | Where | Carries |
| --- | --- | --- |
| `asyncTaskExecutor` | `engine/config/AsyncConfig.java:20-23` | Task-run transitions (`engine/TaskExecutionService.java:146`), audit writes (`core/audit/AuditEventWriter.java:27`) |
| `asyncWorkflowExecutor` | `engine/config/AsyncConfig.java:25-28` | The workflow-run timeout (`engine/WorkflowExecutionService.java:360-363`) |
| `logStreamExecutor` | `core/config/AsyncConfiguration.java:24-30` | The default `@Async` executor, and Spring MVC's async dispatch — a log stream parks its thread for the life of the stream |
| `applicationTaskExecutor` (dispatcher) | `service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:32-39` | The dispatcher's one `@Async` method, `TaskService.deleteTaskRun` |
| Dispatcher SDK work | `dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:85-86` | One virtual thread per claimed task (start, handler, end report) and per workflow run to provision; the polls and the heartbeat have virtual threads of their own (`QueuePoller.java:68`, `Dispatcher.java:378-380`) |

The dispatcher bean has to stay `@Primary`. Spring resolves `@Async` by asking for the one `TaskExecutor`
bean, and a `ThreadPoolTaskScheduler` is itself a `TaskExecutor` — so without it every hand-off would land on
the 2-thread `taskScheduler` (`service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:15-24`)
behind the reconcilers. `AsyncExecutorResolutionTest` pins the resolution.

Nothing caps how many tasks run at once on any of them. What shapes load is the admission gate in
`TaskExecutionService.queue`, the dispatcher's claim limits (20 per poll, `flow.dispatcher.task.max-in-flight` in
flight), and quotas.

## Start-up

Both Java images start from a JDK AOT cache (JEP 514/515) recorded at image build. The build extracts the jar to
`service.jar` plus `lib/`, which the cache requires (`service-core/Dockerfile:31-32`), then a train stage on the
runtime JRE starts the application to context refresh with no database (`spring.context.exit=onRefresh`,
migrations off, a placeholder encryption key, a Mongo URI that resolves nowhere with a 1 s selection timeout,
the OTLP exporters excluded because BuildKit hands every build step `OTEL_TRACES_EXPORTER=otlp` and an endpoint
on its own Unix socket) and writes `service.aot`: every class loaded in that start, linked, with its initialised
state (`service-core/Dockerfile:62-68`). The dispatcher's train stage starts it against no cluster with a
placeholder namespace (`service-dispatcher/Dockerfile:43-46`). The runtime command maps the cache and turns
compact object headers on (`service-core/Dockerfile:91-93`), 4 bytes off every object. Both the train run and the
runtime command turn adapter caching off (`-XX:-AOTAdapterCaching`), which the JVM otherwise enables with a cache: an
adapter recorded on one build machine crashed `service-core` with `SIGILL` at start on another. The train and runtime
stages are the only per-platform stages, since a cache is tied to the JVM build that records it.

**Measured**: `service-core` start to Boot's `Started Application` against a local Mongo 7, security off, on the
same laptop as the throughput baseline but under a load average of 40 to 60 on 8 cores, which is why the figures
are wide. Each pair ran back to back after one warm-up start.

| Start | With the cache | Without it |
| --- | --- | --- |
| Boot's own figure, `Started Application in` | 46, 67, 85 s | 100, 216 s |
| Same start on the host JVM, to refresh, no database | 48, 56 s | 76, 106 s |

The cache took 40 to 60 % off every pair, and the JVM confirms the mapping at start (`-Xlog:aot=info`: `Opened
AOT cache service.aot`, with the three region mappings) as uid 10001. **Not measured**: a quiet machine, the
dispatcher's start, memory after compact headers. **The costs**: the train stage adds one application start to
each image build per platform (270 s on that loaded laptop; the arm64 leg of a release runs it under emulation),
and the cache adds about 120 MB to each image (`flow-service-core` 287 MB to 405 MB). The JVM checks the cache
against its own build, the jar sizes and modification times and the heap-layout flags: a `JDK_JAVA_OPTIONS`
that sets another garbage collector or turns compact headers off makes it skip the cache with one warning and
start as before, never fail.

## HTTP client timeouts and the custom client requirement

Every outbound template carries real transport timeouts; log streaming has its own template so a
quiet-but-healthy stream is not cut at the control read timeout (`core/config/RestConfig.java:35-38,48-54`).

| Template (`core/config/RestConfig.java`) | Connect | Read (idle) | Pool lease | Trust | Used for |
| --- | --- | --- | --- | --- | --- |
| `internalRestTemplate` (`:90-94`), `selfRestTemplate` (`:96-102`) | 10 s | 60 s | 10 s | Default JVM trust | In-cluster control calls |
| `externalRestTemplate` (`:56-78`) | 10 s | 60 s | 10 s | Default; routes via `proxy.host`/`proxy.port` when both are set (`:42-46`) | Outbound integrations, OpenID Connect discovery |
| `insecureRestTemplate` (`:80-88`) | 10 s | 60 s | 10 s | Trust-all (`:120-122`) | Internal endpoints with self-signed certificates |
| `streamingRestTemplate` (`:108-116`) | 10 s | 10 min | 10 s | Trust-all | Dispatcher log streams |

Pool: 200 total / 200 per route for the internal, self and external templates (`:48-49,174-185`); the two
trust-all templates use the HTTP client's default pool; keep-alive 5 min for all (`:187-189`). Companions: Mongo
`connectTimeoutMS=10000&socketTimeoutMS=120000` (`service-core/src/main/resources/application.properties:109`);
async request timeout two hours for a streamed response, so a running task's log is followed for that long, on
both core (`:72`) and the dispatcher (`service-dispatcher/src/main/resources/application.properties:6`). It
reaches only the three routes that answer asynchronously: core's task log and artifact download and the
dispatcher's task log (`workflow/TaskRunControllerV2.java:49`, `workflow/WorkspaceArtifactControllerV2.java:85`,
`service-dispatcher/src/main/java/io/boomerang/dispatcher/LogV1Controller.java:20`); the dispatcher queue's long
polls are synchronous and keep their 30 s hold. A log the dispatcher has sent nothing on for 10 minutes ends at the
streaming template's read timeout, with a closing line to the reader (`engine/LogClient.java:133-139`). A
dispatcher task with no timeout of its own gets 60 min, plus a 2 min watch grace
(`service-dispatcher/src/main/resources/application.properties:29,54`).

Enterprises run Flow behind proxies and private certificate authorities, so proxy routing, the trust-all
template, and per-template timeouts are product features that a framework upgrade MUST preserve with
equivalent behaviour. A run's timeout MUST be at least the transport timeout of the work it guards.

## Quotas

`workflow/QuotaService.java` holds every quota read and check. Quotas are enforced at workflow create and save,
run submit and retry, and artifact upload; nothing in the engine path re-checks them. The workflow count is checked
before the create, so a workspace at its limit takes no more (`QuotaService.java:328-343`). With quotas on, a submit
saves the run before queueing it, recounts, and removes a run that pushed a count over; one admitted is then queued and
started as with quotas off (`workflow/WorkflowService.java:801-831`). The stored
`features.workspaceQuotas` setting switches every check but one (`QuotaService.java:100-105`): the run-duration
ceiling holds with it off, since a run is always bounded by the workspace's own limit or the platform default
(`QuotaService.java:198-208`, pinned by `SubmitRunTimeoutPolicyTest`). A stored quota of `0` is
unlimited and the check is skipped (`workspace/model/Quotas.java:11`), an artifact retention of `0` keeping the
artifact with no expiry (`workflow/ArtifactService.java:183-197`); an absent quota inherits the platform
default from the `quotas` settings document (`QuotaService.java:108-120,190-194`). The `system` workspace an
embedded engine serves is seeded with its workflow count, monthly runs, workspace storage and concurrent runs at
`0` and no run duration or artifact storage of its own, so it inherits the platform defaults for those two
(`service-core/src/main/resources/seed/workspace.json`).

A check finds the workspace by id, else by name ignoring case (`workspace/repository/WorkspaceRepository.java:16-21`,
`QuotaService.java:132-142,201-212,220-222`): an API path names it, while a schedule, a webhook or a GitHub trigger
passes the id its workflow's `hasWorkflow` edge yields. Usage is counted under the workspace's own name, the key its
audit events carry, whatever form the caller used (`QuotaService.java:242-244`). A workflow no workspace owns has no
workspace quota and passes the run checks (`QuotaService.java:353-381,388-409`).

| Quota (settings key, `QuotaService.java:63-68`) | Enforced at | Code |
| --- | --- | --- |
| `max.workflow.count` | Workflow create | `workflow/WorkflowService.java:492`, `QuotaService.java:328-346` |
| `max.workflowrun.concurrent`, `max.workflowrun.monthly` | Run submit and retry, before the run is created; a submit again once it is created (below) | `workflow/WorkflowService.java:782,809`, `workflow/WorkflowRunService.java:1061-1064`, `QuotaService.java:353-381,388-409` |
| `max.workspace.storage` | Workflow save, create and update, and run submit and retry, against the saving workspace's quota (size in Gi, else `QUOTA_EXCEEDED`); a blank size at save takes the platform default, lowered to the saving workspace's quota when enforced | `workflow/WorkflowService.java:589-608`, `QuotaService.java:132-142,417-427` |
| `max.workflowrun.duration` | Run submit and retry, as the ceiling on the requested timeout; a workspace quota of `0` sets no ceiling, and a run that declares no timeout still takes the platform default (`workflow/RunTimeoutPolicy.java:161-172`) | `workflow/WorkflowService.java:2171-2176`, `workflow/WorkflowRunService.java:1064-1070`, `QuotaService.java:201-212` |
| `max.artifact.storage` | Artifact upload, at hand-out and at completion, against the workspace's quota; a stored `0` is unlimited | `workflow/ArtifactService.java:263-272,333-344,691-702`, `QuotaService.java:310-323` |

The two run counters come from different sources, both counted in `QuotaService.current` (`:228-303`).
Concurrent = the live count of non-terminal runs (`notstarted`/`ready`/`running`/`waiting`, `:71-72`) over the
workspace's Workflows (`countRuns` `:451-466`, the `workflow_ref_status` index) plus the runs in their finally stage, which
carry a final status while their finally tasks still run (`countFinallyStage`) — what is running now, which deletion
legitimately reduces. The finally stage's budget is the `workflowrun` setting `finally.timeout`, an operator's ceiling
outside `max.workflowrun.duration` ("Finally tasks" in `execution-model.md`). Monthly = `max(audit count, live count)`: the month's
`{workspaceId, action=CREATE, resourceType=workflowrun}` audit events
(`core/audit/AuditQueryService.countRunsCreated`, the `workspace_time` index) versus the month's
live run documents (the `workflow_ref_creation` index). The max is deliberate — the audit write is
async best-effort, so a just-admitted run may not be audited yet (the live count covers that
race), while a deleted Workflow's runs leave the live count (the audit count covers deletion).
Submit re-checks the same counters once the run is saved and before it is queued, so a burst cannot over-fill a
limit (`QuotaService.assertAdmittedRunWithinQuotas` `:388-409`, called from `workflow/WorkflowService.java:804-816`,
which removes the run on any failure of the re-check, so no un-queued run is left without an owner). A removed run
never left `notstarted`, so it published no transition and has no `CREATE` audit event: a refused submit uses no
monthly quota. Racers that each count the others may all be refused, leaving the slot free for the next submit; the
limit is never over-filled. A retry is checked only before it is created. Audit retention is floored at
60 days so the month window always holds (`core/audit/AuditRetentionService.java:29`).

## Workspace list and profile

`GET /api/v2/workspace/query` and `GET /api/v2/profile`, which the home page reads, cost a fixed number of MongoDB
commands whatever the number of workspaces: each figure is read for the whole page and put together in memory
(`workspace/WorkspaceService.java:876-955`, `:821-850`). A single workspace's get, create and patch run the same code
with a page of one (`:869-871`).

| Figure | Commands per page | Code |
| --- | --- | --- |
| Members and roles | One workspace-node query, skipped when the request has already resolved the nodes, and one `memberOf` edge query on `to_label` | `core/RelationshipService.java:112-140,717-736` |
| Member and approver names | One users query | `core/UserService.java:243-251` |
| Quotas | The platform defaults once (eight settings reads), two audit aggregations grouped by workspace on `workspace_time`, one `hasWorkflow` edge query on `from_label` and one node query, then three run aggregations, one artifact aggregation and one workflows read over every workflow on the page, grouped by workflow | `workflow/QuotaService.java:237-303,447-542` |
| Approver groups, and on the profile each workspace's workflows | One relationship walk from the caller through any of the page's workspaces, the walk and access rule a single workspace's read uses, then one query for the edges into the nodes found, on `to_label`, to place each under its workspace | `core/RelationshipService.java:555-585` |

`WorkspaceQueryDatabaseCommandsTest` pins it: a page of 10 and a page of 60 workspaces cost the same 32 commands
for a member and 37 for a global caller, and each workspace on a page reads as it does alone. Before, each workspace
cost 23 commands for a member and 25 for a global caller, one relationship walk among them: for the 60 on
Testcontainers, 1 389 commands and 5.9 s for a member and 1 512 commands and 7.1 s for a global caller, against 32
commands and 0.5 s and 37 commands and 0.8 s after. What still grows is the walk itself for an admin
or a global token, which starts at the root and reads every node under every workspace, workflow runs included
(`core/RelationshipService.java:874-952`) — now once per page rather than once per workspace.

## Payload caps

Two byte caps bound what the engine stores (`service-core/src/main/resources/application.properties:231-232`):
resolved params ≤ `flow.engine.task.params.max-bytes` (16384) at admission — oversize invalidates the task before
it is claimable (`engine/TaskExecutionService.java:237-253`) — and results ≤ `flow.engine.task.results.max-bytes`
(1 MB) at end, a storage guard behind each dispatcher's own limit (4096 bytes, Kubernetes' termination message) —
oversize fails the task and keeps the prior results (`engine/TaskRunService.java:70-71,1123-1137`). Large values
pass by reference (a workspace path or a URI).

## Storage

Per run, the dispatcher creates at most one persistent volume claim, keyed by the run id
(`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkflowService.java:37-60`). Defaults: 1Gi, the
cluster default class, `ReadWriteMany` (`service-dispatcher/src/main/resources/application.properties:46-48`),
with a 30 s wait for the claim to reach `Bound` or `Pending`
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeServiceImpl.java:160,167-172`). Both executors mount the
same claim (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:321-345`); a completed run's
claim is deleted by the dispatcher's reconciliation, which asks the engine which of the runs it holds volumes for
are finished (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkspaceReconciler.java:57-72`,
`dispatcher/DispatcherService.java:596-619`). Nothing is shared between runs; files kept for later runs are
workflow-scoped artifacts, held in the artifact store under the artifact quota.

## Throughput baseline

Measured with `load/run.mjs` (`load/README.md`) on one laptop: Apple M1, 8 cores (4 performance
+ 4 efficiency), 16 GiB; OrbStack VM with 8 CPUs and 8 GiB running the compose stack — one `service-core`
(standalone, security on), MongoDB 7.0, Node 20 client on the host. Profile `inline`: 6 engine-executed
tasks per run (`setwfproperty`, `decision`, 4 parallel `setwfproperty`), no dispatcher, no workspace.
Each row is one load run; poll traffic (one list query per 100 outstanding runs per second) is included.

| Runs / in flight | Submit p50 / p95 / p99 | Complete p50 / p95 (submit → terminal status) | Runs/min completed | Peak `service-core` / Mongo |
| --- | --- | --- | --- | --- |
| 50 / 5 | 0.37 / 0.84 / 0.89 s | 1.6 / 4.4 s | 548 (64 task runs/s) | — |
| 200 / 20 | 0.61 / 1.27 / 1.50 s | 3.6 / 7.2 s | 1 658 (193 task runs/s) | 319 % CPU, 0.9 GiB / 224 %, 0.5 GiB |
| 500 / 50 | 1.25 / 1.80 / 1.99 s | 7.9 / 13.1 s | 2 245 (262 task runs/s) | 350 % CPU, 0.9 GiB / 180 %, 0.3 GiB |
| 1 000 / 100 | 3.77 / 5.73 / 6.55 s | 24.8 / 40.4 s | 1 383 (161 task runs/s) | 329 % CPU, 0.9 GiB / 201 %, 0.4 GiB |

Every run in every row ended `succeeded` with zero non-2xx responses and nothing in the server log. Throughput
peaks near 50 concurrent submitters and falls at 100, where submit latency triples: the single instance is
CPU-bound (3.5 of 8 VM cores) before Mongo is. What they do not say: anything about a cluster, several
instances, template tasks, a dispatcher, or storage — this is an engine baseline on a laptop, not a
multi-instance saturation test. Reproduce with
`FLOW_TOKEN=$(node load/mint-token.mjs) node load/run.mjs --runs 500 --concurrency 50`.

## Not built

Each item is deliberately absent; a saturation load test on a cluster or an incident reopens it, not
speculation. The laptop baseline above is not that test.

| Not built | What exists instead | Reopen when |
| --- | --- | --- |
| Per-class or per-type concurrency caps on tasks | A workflow bounds its own runs at admission (`concurrency.limit`, `engine/WorkflowExecutionService.java:95-118`, two counts on `workflow_ref_status`); `claimableQuery` selects by task type and the poll's filters with no cap per type (`engine/TaskRunService.java:130-154`); a dispatcher caps only its own total | Load testing shows one task type starving others or overrunning the cluster |
| A per-task resource request or limit | Six deployment-wide `kube.resource.*` values sized by the operator and applied to every task container this dispatcher runs (`service-dispatcher/src/main/java/io/boomerang/executor/TaskResourceResolver.java:48-63`), the same per-deployment shape as the isolation tier (`dispatcher-kubernetes.md`) | A deployment genuinely needs mixed sizing behind one dispatcher, rather than a second dispatcher deployment with its own task types |
| Per-class kill switches | One global `flow.queue.enabled` that stops claiming only | An incident needs one class stopped while others run |
| Partitioning or leader election | Every instance does every job; CAS absorbs duplicates | Never expected; the escalation is cooperative `_id`-hash sharding of the sweep page |
| Per-class retry policies (a rate-limit backoff of the engine's own, a per-reason selector) | One `Backoff` shape, parameterised by a task node's `retry.backoff`; a node's `retry.max` retries every reported failure except the fixed `NOT_RETRIABLE` list, and the three could-not-start reasons keep the engine's budget (`common/model/StatusReason.java:52-74`, `engine/TaskRunService.java:1178-1228`) | A runtime whose rate-limit signal needs a different backoff from the shared one, or a dispatcher whose reasons separate transient from final failures |
| A Kubernetes informer, shared across in-flight Tasks | One fabric8 `Watcher` plus a `CountDownLatch` per Task, on a virtual thread, with a listing reconcile every `kube.timeout.reconcileSeconds` (30 s) catching a dropped watch (`service-dispatcher/src/main/java/io/boomerang/kube/TektonServiceImpl.java:506-545`, `service-dispatcher/src/main/java/io/boomerang/executor/JobWatcher.java:23-35`) | A measured per-Task cost at the in-flight Task count a dispatcher actually carries — API-server watch connections or dispatcher memory, not thread count, which virtual threads already made cheap |
| A change stream on task runs entering `ready`, waking polls on every engine instance | A 30 s long poll woken by the admissions on its own instance that its filter can claim (`dispatcher/PollWakeups.java:41-158`) and re-checking every `flow.dispatcher.poll.recheck-ms`, 1 s by default, for the rest (`dispatcher/DispatcherService.java:73-75`, `:562-584`); the dispatcher reconnects at once | A measured hop latency on a multi-replica engine that the interval dominates, or an idle-query cost at the dispatcher count in use |
| Indexes for filtered claims | A poll's `taskRef`/`workflowRef` filters apply after `claim_page` (type, status, phase, claim.by, creationDate) has picked the ready tasks, keeping oldest first (`engine/TaskRunService.java:228-237`); folding them into `claim_page` would cost the unfiltered claim its index sort | A load test or incident where one engine's queue is large enough that the filtered scan shows: then separate indexes led by `taskRef` and by `workflowRef` |
| A capacity figure read from the cluster (namespace quota, node resources, or how long pods sit Pending) | A fixed slot count per dispatcher, `flow.dispatcher.task.max-in-flight`, sized by the operator; Kubernetes queues what the cluster cannot place, and a pod still Pending after `kube.timeout.startMinutes` (15, `service-dispatcher/src/main/resources/application.properties:40`) is handed back to the engine | A load test or incident where a fixed count both under-uses and overruns the same cluster |

## Also worth knowing

- Sent outbox rows and received inbox rows expire after 7 days; `task_locks` documents expire on their own index — all built by `_0021__Indexes` (`migration/_0021__Indexes.java:112-128`).
- Sweeps page 50 documents at a time; the dispatcher claims at most 20 per poll.
- A run's graph is read once per task, at its queue, projected to the six fields the graph walk reads
  (`DAGUtility.retrieveGraph`, `engine/DAGUtility.java:168-173`), and once more when the run finishes. A task's end
  reads only its dependants and the tasks they depend on, by the stored `dependencies.taskRef` under the `run_tasks`
  index and by name under `node_uniqueness` (`DAGUtility.retrieveDependants`, `:182-218`): in a chain of 200 tasks
  an end returns 4 task run documents where the graph read returned 202, and a wider fan-in costs one document per
  neighbour, never per task in the run
  (`DispatcherRequestDatabaseCommandsTest.anEndOfA200TaskRunReadsItsDependantsNotTheGraph`). A completion returns
  only the fields its transition publishes (`TaskRunService.transition`, `engine/TaskRunService.java:972-985`). An
  end therefore reads no other task run's params, spec or results
  (`DispatcherRequestDatabaseCommandsTest.anEndReadsNoOtherTaskRunWhole`). `advance` and the watcher's recovery keep
  the whole-graph read: they run rarely.
- A for-each parent completes on its last item's end thread and its dependants are admitted from there
  (`engine/TaskExecutionService.java:976-1021`), so nothing but the admission hand-off sits between the last item
  and the next task.

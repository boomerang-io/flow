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
| Level-triggered sweeps on every instance | `engine/WorkflowWatcher.java:47-52,185-212` | Any survivor reaps a crashed instance's or dispatcher's work; overlapping sweeps are harmless because each acts only through the CAS above. |

Crash recovery is the lease the claim sets 90 s ahead (`flow.dispatcher.lease-ms`,
`service-core/src/main/resources/application.properties:75`; `engine/TaskRunService.java:343`) and each 30 s
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

The token cache holds a session's token, not its grants: each request a session makes resolves them from the user's
record, membership edges, the workspace roles and the `features` setting, four reads, or two for an admin or operator
(`core/TokenService.java:643-677,957-1009`), so a membership change applies on the next request on every instance.

Without the parameter cache each task admission and each for-each item reads the revision, the workflow, the
features setting, the global parameters and the workspace: five reads. With it, the tasks of every run of one
revision admitted within 10 s share those five. A workflow's owning workspace is cached apart
with no time limit, because a workflow never changes workspace (`core/ParamLayerCache.java:38-39,88-94`). Every
global parameter, workspace, setting, workflow and revision write calls `evictAll`
(`workflow/ParameterService.java:104,157,172`, `workspace/WorkspaceService.java:244,357,725`,
`core/SettingsService.java:199,221`, `workflow/WorkflowService.java:1841,2290`); token writes do not, because the
scheduler mints a token on every fire, so a new workflow token reaches the context within 10 s. Redaction reads
secured values uncached (`workflow/ParamLayerService.java:194-207`).

## Queue fairness and the indexes behind it

The claim page is oldest-first with backoff exclusion inside the query, so a retried task cannot
starve fresh work and a burst of one workflow's tasks cannot jump the queue.
`claimableQuery` (`engine/TaskRunService.java:130-154`) selects `status=ready`, `phase=pending`, `type` in the
dispatcher's registered types, `claim.by` absent, and `retry.after` absent or elapsed, narrowed by the poll's
task and workflow filters, sorted by `creationDate`, projected to `_id` and `timeout`, then claims each candidate
by CAS (page-then-CAS, `dispatcher/DispatcherService.java:436-448`). Backoff is one generic class: 10 s base,
doubling per attempt, 5 min ceiling, up to 5 s jitter (`common/util/Backoff.java:15-34`), with a budget of 3
requeues (`engine/EngineConstants.java:43`).

A for-each task adds up to `max.foreach.items` (default 256, `service-core/src/main/resources/seed/settings.json`)
claimable items at once, each created at fan-out time (`engine/TaskExecutionService.java:888-890`), so a large fan-out
queues behind older work and ahead of newer work like any other burst. Each item end checks its parent with an
`exists` and a `count` and loads no item; only the last end loads the items, projected (`engine/TaskRunService.java:504-519`).

Dispatchers long-poll for 30 s, re-querying every `flow.dispatcher.poll.recheck-ms` (1000 by default, never under
100) with a page of 20 (`dispatcher/DispatcherService.java:58-75`, `:386`), and reconnect as soon as a poll returns
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:114-122`). When idle, each pool a
dispatcher polls costs one indexed query per re-check (its task claim page; none for a full pool's `limit=0` poll),
two per `flow.dispatcher.poll.residue-ms` (the termination pages, 30000 by default), and the workflow poll one
provision page per re-check, all on `claim_page`, with one request every 30 s per queue. Admitting a task wakes the task polls held on that engine instance
whose filter can claim it, by type and, when the poll filtered on them, task and workflow, and each re-runs its claim
page at once; submitting a run with a workspace to start wakes the workflow polls alone
(`dispatcher/PollWakeups.java:36-251`, `engine/TaskExecutionService.java:278-280`,
`workflow/WorkflowRunService.java:881-884`, `dispatcher/DispatcherService.java:405-408`, `:581-603`): one indexed query
per admission per poll that can claim the task, none for the others and none while nothing is admitted, so a
dispatcher holding eight typed pools costs one find per admission, not eight
(`DispatcherRequestDatabaseCommandsTest.anAdmissionWakesOnlyThePollsWhoseFilterMatches`). The two termination
pages run once per residue interval per dispatcher and filter, on the first look that finds the turn due - never on
a re-check or an admission wake in between, nor on a poll re-opened within the interval - through a due-time map that
successive polls share (`:422-425`, `dispatcher/PollWakeups.java:146-174`). A held task poll's three looks - entry, a wake, the timed
re-check - cost 3, 1 and 1 finds, a poll re-opened after a claim 1 (`DispatcherRequestDatabaseCommandsTest`,
`DispatcherPollRecheckTest`), and a page of 20 orders keeps the turn due (`:452-467`). They also run at once when a
task the dispatcher holds ends cancelled or timed out or is parked for retry on this instance, which wakes its held
polls alone and makes the turn due for the next poll it opens (`engine/TaskRunService.java:619-634`, `:843`,
`PollWakeups.java:189-216`, `DispatcherPollWakeTest.aCancelOfAHeldTaskWakesTheHoldersPollAtOnce`), so the timed turn
only catches an end written on another instance. An embedding product measured one dispatcher with 8 pools and a 5 s re-check at 359 `task_runs` queries a minute idle with the pages on every re-check;
2 finds per pool per 30 s instead of per 5 s takes off 8 × (24 − 4) = 160, to about 200. Under load, where a poll
returns on every claim and is re-opened at once, the shared cadence is one find per claim instead of three. A hop in a sequential chain costs the end round trip, the admission and the claim round trip, independent of
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
`flow.queue.enabled=false` stops claiming only; sweeps keep running (`dispatcher/DispatcherService.java:69-71,263-266,370-373`).
While claiming is off the engine answers each poll at once with 204, and the dispatcher then waits 5 s between
polls (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:23`).

Indexes come only from migration change units: `_0021__Indexes` holds the inventory, and
`_0029__ClaimIndexKeysTheClaimant` re-keys `claim_page` on a database built before the index held `claim.by`
(`migration/_0029__ClaimIndexKeysTheClaimant.java:39-66`). Entity annotations are inert
(`spring.data.mongodb.auto-index-creation=false`, `service-core/src/main/resources/application.properties:140`).

| Index | Collection / keys | Defined at (`migration/_0021__Indexes.java`) | Serves |
| --- | --- | --- | --- |
| `claim_page` | `task_runs {type, status, phase, claim.by, creationDate}` | `:58-62` | `claimableQuery` and its sort (a claimable task run has no `claim.by`, which the index holds as null); the two termination pages, which ask for a string `claim.by` and so read only held task runs: 3 documents for 3 held among 2,003 cancelled, where the earlier keys read all 2,003 (`ClaimIndexQueryPlanTest`) |
| `node_uniqueness` (unique) | `task_runs {workflowRunRef, name}` | `:68-72` | One TaskRun per DAG (directed acyclic graph) node; duplicate creation fails at insert |
| `run_tasks` | `task_runs {workflowRunRef, status, name}` | `:63` | A run's task runs; the Insights reads of a period's task runs and its one usage aggregation per response, matched on the counted runs' ids (`workflow/WorkflowRunInsightService.java:535,579,654`) |
| `timeout_sweep`, `wait_sweep` (sparse) | `task_runs {timeoutAt}`, `{waitUntil}` | `:65-66` | `reapTaskTimeouts`, `resumeDueWaitingTasks` |
| `claimed_sweep` | `task_runs {phase, claim.at}` | `:67` | `reapClaimsFromGoneDispatchers` |
| `parent_index` (sparse) | `task_runs {parentRef, index}` | `:74` | A for-each parent's items: an item end's `exists` and `count`, and the last end's projected load |
| `label_wildcard` | `task_runs {labels.$**}`, `workflow_runs {labels.$**}` | `:75,95` | Label filters on run and task-run queries |
| `claim_page`, `timeout_sweep`, `paused_lookup`, `purge_sweep` | `workflow_runs {status, phase, creationDate}`, `{timeoutAt}`, `{pauseRequestedAt}`, `{purgeRequestedAt}` | `:76-80` | The run provision page and `recoverStaleProvisionClaims`; `reapWorkflowTimeouts`; `purgeRequestedRuns` |
| `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase` | `workflow_runs {phase, creationDate}`, `{phase, startTime}`, `{workflowRef, phase}` | `:81-84` | The retention page of `purgeExpiredRuns`; `recoverStalledRuns`; `cancelDeletedWorkflowRuns` |
| `workflow_ref_creation`, `workflow_ref_status`, `initiated_by_phase` | `workflow_runs {workflowRef, creationDate}`, `{workflowRef, status}`, `{initiatedByRef, phase}` | `:85-94` | The quota run counts; the retention lineage check and the cascade cancel |
| `status_sweep` | `actions {status, creationDate}` | `:138` | `closeStrayActions` |
| `dispatch_page`, `sent_ttl` (7-day expiry) | `events_outbox {status, occurredAt}`, `{sentAt}` | `:112-116` | Outbox drain; delivered rows expire |

## Sweep cadences and their properties

Every sweep runs on every instance with a random start offset; correctness never depends on ticks not
colliding. The sweeps' paged queries carry `maxTimeMsec(5000)` so a slow database cannot hang the sweeper
(`engine/TaskRunService.java:656,689,707,723,896`, `engine/SweepCursor.java:77-82`,
`engine/WorkflowRunStateHelper.java:121,295,535,599,614`), and each sweep runs isolated, so a timeout in one does not stop
the rest (`engine/WorkflowWatcher.java:191-212`).

| Sweeper | Property (default) | Start jitter | Page | Notes |
| --- | --- | --- | --- | --- |
| `DueWaitPoller` (`engine/DueWaitPoller.java:21`) | `flow.watcher.wait-interval-ms` (1000); same `flow.watcher.enabled` switch | none | 50 | `resumeDueWaitingTasks` on its own virtual thread, not the scheduler's: due sleeps and `acquirelock` retries end within about a second. One query on the sparse `wait_sweep` index a second per instance, usually empty; a resume holds the wait with a 60 s lease on `waitUntil` |
| `WorkflowWatcher.sweep` (17 sweeps, `engine/WorkflowWatcher.java:185-212`) | `flow.watcher.interval-ms` (30000); `flow.watcher.enabled` (true); `flow.watcher.provision-claim-grace-ms` (300000) | up to 30 s (`:181-184`) | 50 (`engine/EngineConstants.java:19`) | Stall grace 60 s (`WorkflowWatcher.java:62`); dispatcher declared gone after 60 s without a poll (`:90`) and its registration deleted after 7 days (`:96`, `:595`); a provisioning claim released after the grace, the run failed on its third (`:78,127-130`). The stalled-run, for-each and stray-action sweeps resume after the last row they read, so rows they leave alone do not hide the ones behind (`engine/SweepCursor.java:35-60`); the gone-dispatcher reap reads the live dispatchers once and pages only the other dispatchers' claims (`WorkflowWatcher.java:565-588`); `pruneDeletedWorkflows` hard-deletes a deleted workflow's documents once its runs finish (`workflow/WorkflowRetentionService.java:278-311`) |
| `WorkflowWatcher.purgeExpiredRunsOnSchedule` (`engine/WorkflowWatcher.java:232-237`) | `flow.watcher.retention-interval-ms` (3600000); same `flow.watcher.enabled` switch | up to 60 s | 50, at most 20 pages a pass (`workflow/WorkflowRetentionService.java:64`) so the single scheduler thread returns to the recovery sweeps; a backlog drains over several passes | Finished runs past the `workflowrun`/`retention.days` setting (365, floored at 60, `WorkflowRetentionService.java:56-60`) are purged with their task runs, actions, artifacts (stored logs included) and relationship node (`WorkflowRetentionService.java:229-269,335-343`); two lineage checks per candidate, `existsByInitiatedByRef` on the run id and `existsByInitiatedByRefIn` on its task-run ids (read by id only on `run_tasks`), both on the `initiated_by_phase` index |
| `AuditRetentionService.reapplyOnChange` (`core/audit/AuditRetentionService.java:56-65`) | `flow.audit.retention-interval-ms` (60000) | none | none | Reads the `audit` settings document and runs `collMod` on the `createdAt_ttl` index only when the floored `retentionDays` differs from what this instance last applied, so a change made through the settings API reaches every instance within one interval without a restart; a failed apply is retried on the next tick. One indexed read a minute per instance |
| `ScheduleWatcher.sweep` | `flow.schedule.watcher.interval-ms` (30000); `flow.schedule.watcher.enabled` (true) | up to 30 s (`schedule/ScheduleWatcher.java:66-68`) | 50 | Cron fires by a `nextFireAt` CAS; 3 retries per failed fire (`:38`) |
| `OutboxDispatcher.drain` | `flow.events.outbox.interval-ms` (5000); bean exists only when `flow.events.sink.enabled=true` (`event/OutboxDispatcher.java:34-37`) | up to 5 s (`:64-66`) | 50 | 3 delivery attempts, then the row is marked dead (`:43`) |
| Dispatcher poll termination pages (`dispatcher/PollWakeups.java:146-174`) | `flow.dispatcher.poll.residue-ms` (30000), never under `flow.dispatcher.poll.recheck-ms` | none | 20 a page, two pages | Per dispatcher and poll filter, taken on the first look that finds the turn due; a termination on the same instance runs them at once. Two `claim_page` finds per pool per interval, independent of the re-check |
| `GitHubService.refreshSharedTokens` (`integrations/GitHubService.java:195-232`) | none: every 45 minutes, inside the hour an installation token lives | up to 60 s | all GitHub installations | Each workspace sharing the token gets one GitHub call and one targeted write. Instances do not coordinate, so N instances mint N tokens per workspace per 45 minutes; any of them is valid |

No sweep interval is set in `application.properties` (the poll's termination turn is); the defaults come from the `@Scheduled` fallbacks. Cost
(assumed, not load-tested): one paged indexed query per sweep plus a batched or per-row check, so 20 instances add
well under 10 fixed queries per second; per-row checks grow with in-flight runs.

## Virtual threads

Request handling runs on virtual threads (`spring.threads.virtual.enabled=true`,
`service-core/src/main/resources/application.properties:153`) because each connected dispatcher parks one request
thread for its 30 s poll; the dispatcher sets it too (`service-dispatcher/src/main/resources/application.properties:9`),
where a live log stream parks its request thread for up to two hours. **Measured**: `service-core/src/test/java/io/boomerang/dispatcher/DispatcherPollerVirtualThreadTest.java`
drives 200 pollers through 10 cycles each under Java Flight Recorder and asserts carrier pinning stays under 1% of
the poll cycles, so pins are one-time warm-up rather than per claim and dispatcher count is not bound by the
platform-thread pool. **Not measured**: any before/after throughput comparison; the idle poll's database load (above) is the remaining known cost.

`@Async` work runs on virtual threads too, one thread per hand-off, from three named
`SimpleAsyncTaskExecutor` beans in `service-core` and one in the dispatcher. The property alone does not reach
them: Boot's virtual-thread `applicationTaskExecutor` backs off whenever the context already declares an
`Executor` bean, so each of these beans asks for virtual threads itself.

| Bean | Where | Carries |
| --- | --- | --- |
| `asyncTaskExecutor` | `engine/config/AsyncConfig.java:20-23` | Task-run transitions (`engine/TaskExecutionService.java:146`), audit writes (`core/audit/AuditEventWriter.java:27`) |
| `asyncWorkflowExecutor` | `engine/config/AsyncConfig.java:25-28` | The workflow-run timeout (`engine/WorkflowExecutionService.java:481-484`) |
| `logStreamExecutor` | `core/config/AsyncConfiguration.java:24-30` | The default `@Async` executor, and Spring MVC's async dispatch — a log stream parks its thread for the life of the stream |
| `applicationTaskExecutor` (dispatcher) | `service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:32-39` | The dispatcher's one `@Async` method, `TaskService.deleteTaskRun` |
| Dispatcher SDK work | `dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:85-86` | One virtual thread per claimed task (start, handler, end report) and per workflow run to provision; the polls and the heartbeat have virtual threads of their own (`QueuePoller.java:68`, `Dispatcher.java:378-380`) |

The dispatcher bean has to stay `@Primary`. Spring resolves `@Async` by asking for the one `TaskExecutor`
bean, and a `ThreadPoolTaskScheduler` is itself a `TaskExecutor` — so without it every hand-off would land on
the 2-thread `taskScheduler` (`service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:15-24`)
behind the reconcilers. `AsyncExecutorResolutionTest` pins the resolution.

Nothing caps how many tasks run at once on any of them. What shapes load is the admission gate in
`TaskExecutionService.queue`, the dispatcher's claim limits (20 per poll, `flow.dispatcher.task.max-in-flight` in flight), and quotas.

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
that sets another garbage collector or turns compact headers off makes it skip the cache with one warning and start as before, never fail.

**Memory in the local stack.** The images set only `-XX:+ExitOnOutOfMemoryError`, so a restart policy recovers a JVM
out of heap (`service-core/Dockerfile:93-96`); sizing goes in `JDK_JAVA_OPTIONS`. `docker-compose.yml` (local and
end-to-end) gives `service-core` 1g and `restart: unless-stopped`, `service-dispatcher` 768m, each with
`-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=128m -Xss512k`. Under about 1.8 GB the JVM
picks the serial collector and the cache still maps. The probes pass `-Xmx16m`; `service-core`'s runs every 2 s for up
to 180 s of start, then every 30 s. Mongo's cache is uncapped. Override both in a `compose.override.yaml`, never with a
collector or compact-header flag; for a heap dump add `-XX:+HeapDumpOnOutOfMemoryError` with a mounted `HeapDumpPath`.

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
`connectTimeoutMS=10000&socketTimeoutMS=120000` (`service-core/src/main/resources/application.properties:131`);
async request timeout two hours for a streamed response, so a running task's log is followed for that long, on
both core (`:136`) and the dispatcher (`service-dispatcher/src/main/resources/application.properties:6`). It
reaches only the three routes that answer asynchronously: core's task log and artifact download and the
dispatcher's task log (`workflow/TaskRunControllerV2.java:49`, `workflow/WorkspaceArtifactControllerV2.java:88`,
`service-dispatcher/src/main/java/io/boomerang/dispatcher/LogV1Controller.java:20`); the dispatcher queue's long
polls are synchronous and keep their 30 s hold. A log the dispatcher has sent nothing on for 10 minutes ends at the
streaming template's read timeout, with a closing line to the reader (`engine/LogClient.java:133-139`). A
dispatcher task with no timeout of its own gets 60 min, plus a 2 min watch grace
(`service-dispatcher/src/main/resources/application.properties:29,54`).

Enterprises run Flow behind proxies and private certificate authorities, so proxy routing, the trust-all
template, and per-template timeouts are product features that a framework upgrade MUST preserve with
equivalent behaviour. A run's timeout MUST be at least the transport timeout of the work it guards.

## Quotas

`workflow/QuotaService.java` holds every quota read and check. Quotas are enforced at workflow create and save, run submit and retry, and artifact upload, and the concurrent-run limit at
the engine's admission of a run. The workflow count is checked before the create, so a workspace at its limit takes no more (`QuotaService.java:351-366`). With quotas on, a submit saves the
run before queueing it, recounts, and removes a run that pushed a count over; one that stays is queued and started as with quotas off (`workflow/WorkflowService.java:853-870`). The stored
`features.workspaceQuotas` setting switches every check but one (`QuotaService.java:111-116`): the run-duration ceiling holds with it off, since a run is always bounded by the workspace's own
limit or the platform default (`QuotaService.java:213-223`, pinned by `SubmitRunTimeoutPolicyTest`). A stored quota of `0` is unlimited and the check is skipped
(`workspace/model/Quotas.java:11`), an artifact retention of `0` keeping the artifact with no expiry (`workflow/ArtifactService.java:183-197`); an absent quota inherits the platform default
from the `quotas` settings document (`QuotaService.java:119-132,205-209`). The `system` workspace an embedded engine serves is seeded with its workflow count, monthly runs, workspace storage
and concurrent runs at `0` and no run duration, artifact storage or queue limit of its own, so it inherits the platform defaults for those three, and with no concurrent limit it queues nothing
(`service-core/src/main/resources/seed/workspace.json`).

A check finds the workspace by id, else by name ignoring case (`workspace/repository/WorkspaceRepository.java:16-21`, `QuotaService.java:144-154,216-227,235-237`): an API path names it,
while a schedule, a webhook or a GitHub trigger passes the id its workflow's `hasWorkflow` edge yields. Usage is counted under the workspace's own name, the key its audit events carry,
whatever form the caller used (`QuotaService.java:257-259`). A workflow no workspace owns has no workspace quota and passes the run checks (`QuotaService.java:381-385,413-416,498-500`).

| Quota (settings key, `QuotaService.java:63-70`) | Enforced at | Code |
| --- | --- | --- |
| `max.workflow.count` | Workflow create | `workflow/WorkflowService.java:510`, `QuotaService.java:351-369` |
| `max.workflowrun.concurrent` | The engine's admission of a run, on submit, retry and every re-admission. Never a refusal: a run over it is parked `waiting/pending` and admitted oldest first as slots free (`execution-model.md`, "Starting a run") | `engine/WorkflowExecutionService.java:118-127,146-149`, `QuotaService.java:493-516` |
| `max.workflowrun.queued` (20) | Run submit and retry, before the run is created, and a submit again once it is created: refused `429 QUOTA_EXCEEDED`, "Queued runs", when every slot is taken and the queue is full. `0` is unlimited; with the concurrent limit unlimited nothing is held, so nothing is refused | `workflow/WorkflowService.java:834,862`, `workflow/WorkflowRunService.java:1057-1060`, `QuotaService.java:377-427` |
| `max.workflowrun.monthly` (100) | Run submit and retry, before the run is created; a submit again once it is created (below). A queued run counts from the moment it is parked | as above |
| `max.workspace.storage` | Workflow save, create and update, and run submit and retry, against the saving workspace's quota (size in Gi, else `QUOTA_EXCEEDED`); a blank size at save takes the platform default, lowered to the saving workspace's quota when enforced | `workflow/WorkflowService.java:607-626`, `QuotaService.java:144-154,587-596` |
| `max.workflowrun.duration` | Run submit and retry, as the ceiling on the requested timeout; a workspace quota of `0` sets no ceiling, and a run that declares no timeout still takes the platform default (`workflow/RunTimeoutPolicy.java:161-172`) | `workflow/WorkflowService.java:2370-2375`, `workflow/WorkflowRunService.java:1060-1066`, `QuotaService.java:216-227` |
| `max.artifact.storage` | Artifact upload, at hand-out and at completion, against the workspace's quota; a stored `0` is unlimited | `workflow/ArtifactService.java:263-272,333-344,694-705`, `QuotaService.java:333-346` |

**What the run figures count.** Concurrent is the runs holding a slot: `ready` or `running`, plus the runs in their finally stage, which carry a final status while their finally tasks still
run. Queued is the `waiting` runs: parked for a slot of the workspace's limit or of their own workflow's, which hold none (`QuotaService.java:77-83,316-320`). Both are live counts over the
workspace's workflows on `workflow_ref_status` and `workflow_ref_phase`, which deletion legitimately reduces. The finally stage's budget is the `workflowrun` setting `finally.timeout`, an
operator's ceiling outside `max.workflowrun.duration` ("Finally tasks" in `execution-model.md`). Monthly = `max(audit count, live count)`: the month's
`{workspaceId, action=CREATE, resourceType=workflowrun}` audit events (`core/audit/AuditQueryService.countRunsCreated`, the `workspace_time` index) versus the month's live run documents (the `workflow_ref_creation` index)
less the entries dropped schedule fires left, `statusReason` `SubmitRefused`, counted on their own partial index `refused_submit_month` (`QuotaService.java:305-315,556-568,638-651`). The max is
deliberate: the audit write is async best-effort, so a just-admitted run may not be audited yet (the live count covers that race), while a deleted workflow's runs leave the live count (the
audit count covers deletion). A dropped-fire entry never changed status, so it has no `CREATE` event either and uses none of the month. Audit retention is floored at 60 days so the month
window always holds (`core/audit/AuditRetentionService.java:29`).

**What an admission reads.** A submit, a retry and the engine's admission do not call `QuotaService.current`, which also reads audit totals, storage and workflow counts. `admission` reads the
workspace, at most four settings, the workspace node and its `hasWorkflow` edges (`core/RelationshipService.java:1174-1187`), then only the counts behind a limited quota: one audit and two run
counts for the month, two for the runs in flight, one for the queue, each run count a count of index keys over the workspace's own workflows (`QuotaService.java:455-484,532-579`). `runSlots`, the
engine's read, is one settings read, the workflow's `hasWorkflow` edge, the workspace, its node and edges and the two in-flight counts (`:493-516`). `RunAdmissionDatabaseCommandsTest` pins both: the
same commands and no run document read for a workspace of 1 workflow and of 200, but for the second batch of the edge read past 101 workflows. A run's completion with nothing parked anywhere
costs one count on `claim_page` before it returns (`engine/ConcurrencyAdmissionListener.java:46-48`, `engine/WorkflowRunStateHelper.java:273-275`).

**Bursts.** Submit re-checks the month and the queue once the run is saved and before it is queued (`QuotaService.java:411-427`, called from `workflow/WorkflowService.java:853-869`, which
removes the run on any failure of the re-check, so no un-queued run is left without an owner). With every slot taken, the runs not yet admitted, `notstarted` and `waiting`, may not outnumber the
queue's places, so a burst against a full workspace leaves no more runs waiting than the queue holds; a burst that finds slots free is not held to the cap until they are taken. A removed run never left `notstarted`, so it published no transition and
has no `CREATE` audit event: a refused submit uses no monthly quota. Racers that each count the others may all be refused, leaving the place free for the next submit; a limit is never
over-filled. A retry is checked only before it is created. The concurrent limit itself can be overshot by a burst, by up to the number admitted together (`execution-model.md`).

## Workspace list and profile

`GET /api/v2/workspace/query` and `GET /api/v2/profile` cost the same commands whatever the number of workspaces: each
figure is read for the whole page (`workspace/WorkspaceService.java:958-1038`, `:901-931`); a get, create or patch is a page of one (`:950-952`).

| Figure | Commands per page | Code |
| --- | --- | --- |
| Members and roles | One workspace-node query, skipped when the request has already resolved the nodes, and one `memberOf` edge query on `to_label` | `core/RelationshipService.java:126-154,965-984` |
| Member and approver names | One users query | `core/UserService.java:247-255` |
| Quotas | The platform defaults once (nine settings reads), two audit aggregations grouped by workspace on `workspace_time`, one `hasWorkflow` edge query on `from_label` and one node query, then five run aggregations (the month's runs, the dropped-fire entries among them, in flight, queued, finally stage), one artifact aggregation and one workflows read over every workflow on the page, grouped by workflow | `workflow/QuotaService.java:252-326,612-727` |
| Approver groups; on the profile, workflows per workspace | Approver groups: one relationship walk from the caller through any of the page's workspaces, the walk and access rule a single workspace's read uses, following `memberOf` then `hasApproverGroup` only, so no workflow edge or node is read; each group goes under the named workspaces whose edges into it the walk followed, and a named workspace the walk did not reach costs one more edge query. Workflow counts: the workspaces that walk would descend from (two node queries), then one aggregation counting their `hasWorkflow` edges on `from_label`, so no workflow node is read; the count is the listing's on a consistent graph, and an edge whose workflow node is gone still counts. For a member of 100 workspaces of 200 workflows (1.12 million nodes), approver groups cost 6.0 ms, 701 documents, 107 KB returned and 1.9 MB allocated, and the counts 21.8 ms, 301 documents, 64 KB and 1.4 MB, against 143 ms, 41,501 documents, 4.99 MB and 119 MB for listing the workflows | `core/RelationshipService.java:684-787,812-833,1504-1528`, `workspace/WorkspaceService.java:913-914` |

`WorkspaceQueryDatabaseCommandsTest` pins 32 commands for a member and 31 for a global caller, for 10 or 60 workspaces,
and that the profile reads the same relationship nodes whatever the number of workflows.

## Relationship reads per request and per tick

Grants are resolved on every request; the caller's memberships are read once per request, by authentication, and shared
with every lookup in it (`core/TokenService.java:654-678,981-984`, `core/RelationshipService.java:193-237`). No lookup's cost depends on the run count, and a walk level reads, as plain documents, only
the edges whose label leads to its target (`:1504-1541`); `RelationshipReadsDatabaseCommandsTest` pins the documents at 10 and 1,000 runs.

| Path | Reads | Code |
| --- | --- | --- |
| Health probe; any route with a global token | 0: the probe runs no authentication, and a `global` grant answers before the workspace is resolved | `core/security/AuthenticationFilter.java:428-436`, `core/RelationshipService.java:618-632` |
| Workspace-scoped read, global token, admin session or scheduled run | 2 node reads: the workspace node, then its children; the walk starts at the named workspaces, not at the root | `core/RelationshipService.java:1310-1321` |
| A member's workflow lookup in a workspace by name (also a schedule query and get) | 3 nodes and W + M edges for a workspace of W workflows and a member of M workspaces, whatever the run count: the member's node, the M memberships (the request's one read), the named workspace's node, its W `hasWorkflow` and `linksTemplate` edges and the one workflow node named; 7 documents at W = 1, M = 3 (8 for the schedule query, 4 for a get). At 200 workflows the server fetches 405 documents and returns 20.9 KB in 2.6 ms, from 417 and 25.6 KB | `core/RelationshipService.java:1346-1371,1530-1541` |
| A member's Workflows page (`GET …/workflow/query`); workspace delete; platform Insights with no workspace named | The page: one walk for the workspace's workflows (W edges, W nodes), the workspace's `hasWorkflow` edge ends once more to tell its own from a claim in flight, and only the links among them read from `workflows`, as their template reference; a request that names workflows also asks for those by name, one more walk of the same edges, because a workflow's own token reaches its workflow only by name. Delete resolves the workspace's workflows once, not once per workflow. Insights reads every workspace's workflows in one edge query and one node query, not three queries per workspace | `workflow/WorkflowService.java:390-405,1083-1098,2534-2552`, `workspace/WorkspaceService.java:390-394`, `workflow/WorkflowRunInsightService.java:357-382` |
| Every workspace, for an admin or a global token | the root's `contains` edges and the workspace nodes among their ends, not every user and membership: 8.2 ms and 3,002 documents for 500 workspaces and 2,001 users, from 34.9 ms and 11,403 | `core/RelationshipService.java:1332-1336,1375-1392` |
| A run check, `check(WORKFLOWRUN, …)` from a member or workspace token | 4 documents in 4 queries beyond the request's memberships: the caller's node, the run node, the edge into it and that run's workspace node | `core/RelationshipService.java:1337-1345,1396-1443` |
| A global task lookup by name | 3 documents: the task nodes of the name and their edge from the root, not every user, workspace and run in the install | `core/RelationshipService.java:1327-1336,1447-1475` |
| Dispatcher poll, filter resolved | 1 node read for any number of bare task slugs, 2 for workspace tasks; a filter resolves once per poll and is cached 30 s per filter text, so the re-checks inside a poll and the polls after read none | `dispatcher/ClaimFilterService.java:60-73,91-199`, `dispatcher/DispatcherService.java:381-382` |
| Schedule fire | 6 node reads: the schedule's workflow name, the submit walk (2), the owner's name for the quota, the admission audit, the workspace the run's node hangs from; no request, so no memo | `schedule/ScheduleService.java:253-268`, `workflow/WorkflowService.java:732-881` |
| Run completion; watcher and schedule sweeps with nothing due | 1 node read, the audit event's workflow and workspace names in one read; 0 | `core/audit/WorkflowRunAuditBridge.java:80-89`, `engine/WorkflowWatcher.java:181-212` |

## Payload caps

Two byte caps bound what the engine stores (`service-core/src/main/resources/application.properties:244-245`):
resolved params ≤ `flow.engine.task.params.max-bytes` (16384) at admission — oversize invalidates the task before
it is claimable (`engine/TaskExecutionService.java:237-253`) — and results ≤ `flow.engine.task.results.max-bytes`
(1 MB) at end, a storage guard behind each dispatcher's own limit (4096 bytes, Kubernetes' termination message) —
oversize fails the task and keeps the prior results (`engine/TaskRunService.java:70-71,1123-1137`). Large values
pass by reference (a workspace path or a URI).

## Storage

Per run, the dispatcher creates at most one persistent volume claim, keyed by the run id
(`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkflowService.java:37-56`). Defaults: 1Gi, the
cluster default class, `ReadWriteMany` (`service-dispatcher/src/main/resources/application.properties:46-48`),
with a 30 s wait for the claim to reach `Bound` or `Pending`
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeServiceImpl.java:160,167-172`). Both executors mount the
same claim (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:321-345`); a completed run's
claim is deleted by the dispatcher's reconciliation, which asks the engine which of the runs it holds volumes for
are finished (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkspaceReconciler.java:57-72`,
`dispatcher/DispatcherService.java:615-638`). Nothing is shared between runs; files kept for later runs are
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

Each item is deliberately absent; a cluster load test or an incident reopens it, not the laptop baseline above.

| Not built | What exists instead | Reopen when |
| --- | --- | --- |
| Per-class or per-type concurrency caps on tasks | A workflow bounds its own runs at admission, and a workspace its workflows' (`concurrency.limit` and `max.workflowrun.concurrent`, `engine/WorkflowExecutionService.java:106-150`, two counts each); `claimableQuery` selects by task type and the poll's filters with no cap per type (`engine/TaskRunService.java:130-154`); a dispatcher caps only its own total | Load testing shows one task type starving others or overrunning the cluster |
| A per-task resource request or limit | Six deployment-wide `kube.resource.*` values sized by the operator and applied to every task container this dispatcher runs (`service-dispatcher/src/main/java/io/boomerang/executor/TaskResourceResolver.java:48-63`), the same per-deployment shape as the isolation tier (`dispatcher-kubernetes.md`) | A deployment genuinely needs mixed sizing behind one dispatcher, rather than a second dispatcher deployment with its own task types |
| Per-class kill switches | One global `flow.queue.enabled` that stops claiming only | An incident needs one class stopped while others run |
| Partitioning or leader election | Every instance does every job; CAS absorbs duplicates | Never expected; the escalation is cooperative `_id`-hash sharding of the sweep page |
| Per-class retry policies (a rate-limit backoff of the engine's own, a per-reason selector) | One `Backoff` shape, parameterised by a task node's `retry.backoff`; a node's `retry.max` retries every reported failure except the fixed `NOT_RETRIABLE` list, and the three could-not-start reasons keep the engine's budget (`common/model/StatusReason.java:56-78`, `engine/TaskRunService.java:1178-1228`) | A runtime whose rate-limit signal needs a different backoff from the shared one, or a dispatcher whose reasons separate transient from final failures |
| A Kubernetes informer, shared across in-flight Tasks | One fabric8 `Watcher` plus a `CountDownLatch` per Task, on a virtual thread, with a listing reconcile every `kube.timeout.reconcileSeconds` (30 s) catching a dropped watch (`service-dispatcher/src/main/java/io/boomerang/kube/TektonServiceImpl.java:506-545`, `service-dispatcher/src/main/java/io/boomerang/executor/JobWatcher.java:23-35`) | A measured per-Task cost at the in-flight Task count a dispatcher actually carries — API-server watch connections or dispatcher memory, not thread count, which virtual threads already made cheap |
| A change stream on task runs entering `ready`, waking polls on every engine instance | A 30 s long poll woken by the admissions on its own instance that its filter can claim (`dispatcher/PollWakeups.java:36-251`) and re-checking every `flow.dispatcher.poll.recheck-ms`, 1 s by default, for the rest (`dispatcher/DispatcherService.java:73-75`, `:581-603`); the dispatcher reconnects at once | A measured hop latency on a multi-replica engine that the interval dominates, or an idle-query cost at the dispatcher count in use |
| Indexes for filtered claims | A poll's `taskRef`/`workflowRef` filters apply after `claim_page` (type, status, phase, claim.by, creationDate) has picked the ready tasks, keeping oldest first (`engine/TaskRunService.java:228-237`); folding them into `claim_page` would cost the unfiltered claim its index sort | A load test or incident where one engine's queue is large enough that the filtered scan shows: then separate indexes led by `taskRef` and by `workflowRef` |
| A capacity figure read from the cluster (namespace quota, node resources, or how long pods sit Pending) | A fixed slot count per dispatcher, `flow.dispatcher.task.max-in-flight`, sized by the operator; Kubernetes queues what the cluster cannot place, and a pod still Pending after `kube.timeout.startMinutes` (15, `service-dispatcher/src/main/resources/application.properties:40`) is handed back to the engine | A load test or incident where a fixed count both under-uses and overruns the same cluster |

## Also worth knowing

- Outbox and inbox rows expire after 7 days and `task_locks` on their own index (`migration/_0021__Indexes.java:120-136`); sweeps page 50 documents, a poll claims at most 20.
- A run's graph is read once per task, at its queue, projected to six fields (`engine/DAGUtility.java:168-173`), and once
  when the run finishes. A task's end reads only its dependants and their dependencies, by `dependencies.taskRef` under
  `run_tasks` and by name under `node_uniqueness` (`:182-218`): 4 documents for an end in a chain of 200, never one per
  task in the run, and no other task run's params, spec or results (`DispatcherRequestDatabaseCommandsTest`). A
  completion returns only the fields its transition publishes (`engine/TaskRunService.java:972-985`); `advance` and
  the watcher's recovery keep the whole-graph read, as they run rarely.
- A for-each parent completes on its last item's end thread, which admits its dependants
  (`engine/TaskExecutionService.java:976-1021`): nothing but that hand-off sits between the last item and the next task.

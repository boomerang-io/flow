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
| Claim CAS (`findAndModify` on `_id` + full eligibility) | `engine/TaskRunService.java:317-355` | One dispatcher owns a TaskRun; losers get `null` and skip. Each claim increments `claim.seq` (`:340`). A dispatcher's start and end are refused unless it still holds the claim (`claim.by`, `:1162-1191`), and the engine's own requeue and abandon fence on the `claim.seq` they read (`:886-890`), so a superseded claimant cannot write results. |
| Status-CAS transitions (`transition`, `tryComplete`, `tryRequeue`) | `engine/TaskRunService.java:543-595,735-780,895-916`, `engine/WorkflowRunStateHelper.java` | Concurrent advance from two instances converges: the second CAS finds the state already changed and performs no side effects. |
| Level-triggered sweeps on every instance | `engine/WorkflowWatcher.java:40-45,149-176` | Any survivor reaps a crashed instance's or dispatcher's work; overlapping sweeps are harmless because each acts only through the CAS above. |

Crash recovery is the lease the claim sets 90 s ahead (`flow.dispatcher.lease-ms`,
`service-core/src/main/resources/application.properties:47`; `engine/TaskRunService.java:333`) and each 30 s
heartbeat renews (`engine/TaskRunService.java:622-634`; `flow.dispatcher.lease.beat-ms`,
`service-dispatcher/src/main/resources/application.properties:130`), served by the sparse `lease_sweep` index
(`migration/_0021__Indexes.java:64`), which holds only live claims because completion clears the lease
(`engine/TaskRunService.java:598-604`); the absolute `timeoutAt` written at claim = now + timeout + 5 s grace
(`engine/RunTimeouts.java:15-19`, `engine/EngineConstants.java:13`); and the gone-dispatcher sweep below.

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
(`workflow/ParameterService.java:104,157,172`, `workspace/WorkspaceService.java:212,311,566`,
`core/SettingsService.java:166`, `workflow/WorkflowService.java:1344,1748`); token writes do not, because the
scheduler mints a token on every fire, so a new workflow token reaches the context within 10 s. Redaction reads
secured values uncached (`workflow/ParamLayerService.java:201-214`).

## Queue fairness and the indexes behind it

The claim page is oldest-first with backoff exclusion inside the query, so a retried task cannot
starve fresh work and a burst of one workflow's tasks cannot jump the queue.
`claimableQuery` (`engine/TaskRunService.java:120-144`) selects `status=ready`, `phase=pending`, `type` in the
dispatcher's registered types, `claim.by` absent, and `retry.after` absent or elapsed, narrowed by the poll's
task and workflow filters, sorted by `creationDate`, projected to `_id` and `timeout`, then claims each candidate
by CAS (page-then-CAS, `dispatcher/DispatcherService.java:371-389`). Backoff is one generic class: 10 s base,
doubling per attempt, 5 min ceiling, up to 5 s jitter (`common/util/Backoff.java:12-22`), with a budget of 3
requeues (`engine/EngineConstants.java:34`).

A for-each task adds up to `max.foreach.items` (default 256, `service-core/src/main/resources/seed/settings.json`)
claimable items at once, each created at fan-out time (`engine/TaskExecutionService.java:754`), so a large fan-out
queues behind older work and ahead of newer work like any other burst. Each item end checks its parent with an
`exists` and a `count` and loads no item; only the last end loads the items, projected
(`engine/TaskRunService.java:489-504`).

Dispatchers long-poll for 30 s, re-querying every `flow.dispatcher.poll.recheck-ms` (1000 by default, never under
100) with a page of 20 (`dispatcher/DispatcherService.java:55-73`, `:444-446`), and reconnect as soon as a poll returns
(`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:104-123`). Each connected dispatcher
therefore costs about 4 indexed queries per re-check when idle (the task claim page, the two termination pages and
the run provision page), all on `claim_page`, and one request every 30 s per queue; a dispatcher's idle queries per
second scale with its pools × 1000 / the interval. Admitting a task, or submitting a run with a workspace to start,
wakes every poll held on that engine instance, which re-runs its claim page at once
(`dispatcher/PollWakeups.java:24-55`, `engine/TaskExecutionService.java:254`, `workflow/WorkflowRunService.java:815`,
`dispatcher/DispatcherService.java:509-517`): one indexed query per held poll per admission, none while nothing is
admitted. The two termination pages run on a task poll's first look and then once per re-check interval, never on
a wake (`:384-425`): a held task poll's three looks - entry, a wake, the timed re-check - cost 3, 1 and 3 claim-page
finds (`DispatcherRequestDatabaseCommandsTest`), 7 where every look cost 3, and a termination still reaches the
dispatcher within one interval. A hop in a sequential chain costs the end round trip, the admission and the claim round trip, independent of
the interval. Measured by an embedder on one engine and one in-process dispatcher, the same 41-hop scenario before
and after: the hop from a task's end to the next task's start fell from 0.88 s median and 1.03 s p90 to 0.05–0.11 s
median and 0.09–0.26 s p90, with the interval at its 1 s default. The interval bounds the wait for a task that becomes claimable without an admission
on the polled instance: a `retry.after` that elapses, an admission on another replica, or a slot that frees while
the engine holds a full dispatcher's `limit=0` poll. A multi-replica engine whose chains hop often can trade queries
for that latency: one dispatcher with seven pools at 200 ms costs 35 queries/s instead of 7. A dispatcher holds at most `flow.dispatcher.task.max-in-flight` tasks (25,
`service-dispatcher/src/main/resources/application.properties:96`), a Pending pod and a retried end report
included, and each poll claims no more than its free slots, so under a backlog the work it cannot run stays
claimable by other dispatchers (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/InFlightTasks.java:29-33`,
`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:259-283`).
`flow.queue.enabled=false` stops claiming only; sweeps keep running (`dispatcher/DispatcherService.java:64-65,239-242,340-343`).
While claiming is off the engine answers each poll at once with 204, and the dispatcher then waits 5 s between
polls (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/QueuePoller.java:23`).

Indexes come only from migration change units: `_0021__Indexes` holds the inventory, and
`_0029__ClaimIndexKeysTheClaimant` re-keys `claim_page` on a database built before the index held `claim.by`
(`migration/_0029__ClaimIndexKeysTheClaimant.java:39-66`). Entity annotations are inert
(`spring.data.mongodb.auto-index-creation=false`, `service-core/src/main/resources/application.properties:76`).

| Index | Collection / keys | Defined at (`migration/_0021__Indexes.java`) | Serves |
| --- | --- | --- | --- |
| `claim_page` | `task_runs {type, status, phase, claim.by, creationDate}` | `:58-62` | `claimableQuery` and its sort (a claimable task run has no `claim.by`, which the index holds as null); the two termination pages, which ask for a string `claim.by` and so read only held task runs: 3 documents for 3 held among 2,003 cancelled, where the earlier keys read all 2,003 (`ClaimIndexQueryPlanTest`) |
| `node_uniqueness` (unique) | `task_runs {workflowRunRef, name}` | `:68-72` | One TaskRun per DAG (directed acyclic graph) node; duplicate creation fails at insert |
| `run_tasks` | `task_runs {workflowRunRef, status, name}` | `:63` | A run's task runs; the Insights reads of a period's task runs and its one usage aggregation per response, matched on the counted runs' ids (`workflow/WorkflowRunInsightService.java:331,375,450`) |
| `timeout_sweep`, `wait_sweep` (sparse) | `task_runs {timeoutAt}`, `{waitUntil}` | `:65-66` | `reapTaskTimeouts`, `resumeDueWaitingTasks` |
| `claimed_sweep` | `task_runs {phase, claim.at}` | `:67` | `reapClaimsFromGoneDispatchers` |
| `parent_index` (sparse) | `task_runs {parentRef, index}` | `:74` | A for-each parent's items: an item end's `exists` and `count`, and the last end's projected load |
| `label_wildcard` | `task_runs {labels.$**}`, `workflow_runs {labels.$**}` | `:75,94` | Label filters on run and task-run queries |
| `claim_page`, `timeout_sweep`, `paused_lookup` | `workflow_runs {status, phase, creationDate}`, `{timeoutAt}`, `{pauseRequestedAt}` | `:76-79` | The run provision page and `recoverStaleProvisionClaims`; `reapWorkflowTimeouts` |
| `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase` | `workflow_runs {phase, creationDate}`, `{phase, startTime}`, `{workflowRef, phase}` | `:80-83` | The retention page of `purgeExpiredRuns`; `recoverStalledRuns`; `cancelDeletedWorkflowRuns` |
| `workflow_ref_creation`, `workflow_ref_status`, `initiated_by_phase` | `workflow_runs {workflowRef, creationDate}`, `{workflowRef, status}`, `{initiatedByRef, phase}` | `:84-93` | The quota run counts; the retention lineage check and the cascade cancel |
| `status_sweep` | `actions {status, creationDate}` | `:138` | `closeStrayActions` |
| `dispatch_page`, `sent_ttl` (7-day expiry) | `events_outbox {status, occurredAt}`, `{sentAt}` | `:112-117` | Outbox drain; delivered rows expire |

## Sweep cadences and their properties

Every sweep runs on every instance with a random start offset; correctness never depends on ticks not
colliding. The sweeps' paged queries carry `maxTimeMsec(5000)` so a slow database cannot hang the sweeper
(`engine/TaskRunService.java:616,649,667,830`, `engine/SweepCursor.java:77-82`,
`engine/WorkflowRunStateHelper.java:119,311,390`), and each sweep runs isolated, so a timeout in one does not stop
the rest (`engine/WorkflowWatcher.java:159-175`).

| Sweeper | Property (default) | Start jitter | Page | Notes |
| --- | --- | --- | --- | --- |
| `WorkflowWatcher.sweep` (14 sweeps, `engine/WorkflowWatcher.java:162-175`) | `flow.watcher.interval-ms` (30000); `flow.watcher.enabled` (true); `flow.watcher.provision-claim-grace-ms` (300000) | up to 30 s (`:149-152`) | 50 (`engine/EngineConstants.java:16`) | Stall grace 60 s (`WorkflowWatcher.java:55`); dispatcher declared gone after 60 s without a poll (`:74`); a provisioning claim released after the grace, the run failed on its third (`:62,100-101`). The stalled-run, for-each and stray-action sweeps resume after the last row they read, so rows they leave alone do not hide the ones behind (`engine/SweepCursor.java:35-60`); the gone-dispatcher reap reads the live dispatchers once and pages only the other dispatchers' claims (`WorkflowWatcher.java:450-473`); `pruneDeletedWorkflows` hard-deletes a deleted workflow's documents once its runs finish (`workflow/WorkflowRetentionService.java:137`) |
| `WorkflowWatcher.purgeExpiredRunsOnSchedule` (`engine/WorkflowWatcher.java:192-201`) | `flow.watcher.retention-interval-ms` (3600000); same `flow.watcher.enabled` switch | up to 60 s | 50, at most 20 pages a pass (`workflow/WorkflowRetentionService.java:52`) so the single scheduler thread returns to the recovery sweeps; a backlog drains over several passes | Finished runs past the `workflowrun`/`retention.days` setting (365, floored at 60, `WorkflowRetentionService.java:44-48`) are purged with their task runs, actions, artifacts (stored logs included) and relationship node (`WorkflowRetentionService.java:97-128,194-201`); two lineage checks per candidate, `existsByInitiatedByRef` on the run id and `existsByInitiatedByRefIn` on its task-run ids (read by id only on `run_tasks`), both on the `initiated_by_phase` index |
| `ScheduleWatcher.sweep` | `flow.schedule.watcher.interval-ms` (30000); `flow.schedule.watcher.enabled` (true) | up to 30 s (`schedule/ScheduleWatcher.java:65-67`) | 50 | Cron fires by a `nextFireAt` CAS; 3 retries per failed fire (`:37`) |
| `OutboxDispatcher.drain` | `flow.events.outbox.interval-ms` (5000); bean exists only when `flow.events.sink.enabled=true` (`event/OutboxDispatcher.java:34-37`) | up to 5 s (`:64-66`) | 50 | 3 delivery attempts, then the row is marked dead (`:43`) |

No interval is set in `application.properties`; the defaults come from the `@Scheduled` fallbacks. Cost
(assumed, not load-tested): one paged indexed query per sweep plus a batched or per-row check, so 20 instances add
well under 10 fixed queries per second; per-row checks grow with in-flight runs.

## Virtual threads

Request handling runs on virtual threads (`spring.threads.virtual.enabled=true`,
`service-core/src/main/resources/application.properties:90`) because each connected dispatcher parks one request
thread for its 30 s poll. **Measured**: `service-core/src/test/java/io/boomerang/dispatcher/DispatcherPollerVirtualThreadTest.java`
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
| `asyncTaskExecutor` | `engine/config/AsyncConfig.java:20-23` | Task-run transitions (`engine/TaskExecutionService.java:141`), audit writes (`core/audit/AuditEventWriter.java:27`) |
| `asyncWorkflowExecutor` | `engine/config/AsyncConfig.java:25-28` | The workflow-run timeout (`engine/WorkflowExecutionService.java:211-212`) |
| `logStreamExecutor` | `core/config/AsyncConfiguration.java:24-30` | The default `@Async` executor, and Spring MVC's async dispatch — a log stream parks its thread for the life of the stream |
| `applicationTaskExecutor` (dispatcher) | `service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:32-39` | The dispatcher's one `@Async` method, `TaskService.deleteTaskRun` |
| Dispatcher SDK work | `dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/Dispatcher.java:70-71` | One virtual thread per claimed task (start, handler, end report) and per workflow run to provision; the polls and the heartbeat have virtual threads of their own (`QueuePoller.java:66`, `Dispatcher.java:292-294`) |

The dispatcher bean has to stay `@Primary`. Spring resolves `@Async` by asking for the one `TaskExecutor`
bean, and a `ThreadPoolTaskScheduler` is itself a `TaskExecutor` — so without it every hand-off would land on
the 2-thread `taskScheduler` (`service-dispatcher/src/main/java/io/boomerang/config/ThreadConfig.java:15-24`)
behind the reconcilers. `AsyncExecutorResolutionTest` pins the resolution.

Nothing caps how many tasks run at once on any of them. What shapes load is the admission gate in
`TaskExecutionService.queue`, the dispatcher's claim limits (20 per poll, `flow.dispatcher.task.max-in-flight` in
flight), and quotas.

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
`connectTimeoutMS=10000&socketTimeoutMS=120000` (`service-core/src/main/resources/application.properties:67`);
async request timeout two hours for a streamed response, so a running task's log is followed for that long, on
both core (`:72`) and the dispatcher (`service-dispatcher/src/main/resources/application.properties:6`). It
reaches only the three routes that answer asynchronously: core's task log and artifact download and the
dispatcher's task log (`workflow/TaskRunControllerV2.java:49`, `workflow/WorkspaceArtifactControllerV2.java:88`,
`service-dispatcher/src/main/java/io/boomerang/dispatcher/LogV1Controller.java:20`); the dispatcher queue's long
polls are synchronous and keep their 30 s hold. A log the dispatcher has sent nothing on for 10 minutes ends at the
streaming template's read timeout, with a closing line to the reader (`engine/LogClient.java:123-127`). A
dispatcher task with no timeout of its own gets 60 min, plus a 2 min watch grace
(`service-dispatcher/src/main/resources/application.properties:26,51`).

Enterprises run Flow behind proxies and private certificate authorities, so proxy routing, the trust-all
template, and per-template timeouts are product features that a framework upgrade MUST preserve with
equivalent behaviour. A run's timeout MUST be at least the transport timeout of the work it guards.

## Quotas

`workflow/QuotaService.java` holds every quota read and check. Quotas are enforced at workflow create and save,
run submit and retry, and artifact upload; nothing in the engine path re-checks them. The stored
`features.workspaceQuotas` setting is the only switch (`QuotaService.java:91-96`). A stored quota of `0` is
unlimited and the check is skipped (`workspace/model/Quotas.java:11`); an absent quota inherits the platform
default from the `quotas` settings document (`QuotaService.java:99-111,164-168`). The `system` workspace an
embedded engine serves is seeded with its workflow count, monthly runs, workspace storage and concurrent runs at
`0` and no run duration or artifact storage of its own, so it inherits the platform defaults for those two
(`service-core/src/main/resources/seed/workspace.json`).

| Quota (settings key, `QuotaService.java:53-59`) | Enforced at | Code |
| --- | --- | --- |
| `max.workflow.count` | Workflow create | `workflow/WorkflowService.java:412`, `QuotaService.java:269-282` |
| `max.workflowrun.concurrent`, `max.workflowrun.monthly` | Run submit and retry, before the run is created | `workflow/WorkflowService.java:614`, `workflow/WorkflowRunService.java:974`, `QuotaService.java:289-313` |
| `max.workspace.storage` | Workflow save, create and update, and run submit and retry, against the saving workspace's quota (size in Gi, else `QUOTA_EXCEEDED`); a blank size at save takes the platform default, lowered to the saving workspace's quota when enforced | `workflow/WorkflowService.java:459-487`, `QuotaService.java:118-133,357-372` |
| `max.workflowrun.duration` | Run submit and retry, as the ceiling on the requested timeout; a workspace quota of `0` sets no ceiling, and a run that declares no timeout still takes the platform default (`workflow/RunTimeoutPolicy.java:161-166`) | `workflow/WorkflowService.java:1830-1835`, `workflow/WorkflowRunService.java:975`, `QuotaService.java:175-186` |
| `max.artifact.storage` | Artifact upload, at hand-out and at completion, against the workspace's quota; a stored `0` is unlimited | `workflow/ArtifactService.java:256-264,324-333,660-671`, `QuotaService.java:264-281` |

The two run counters come from different sources, both counted in `QuotaService.current` (`:207-244`).
Concurrent = the live count of non-terminal runs (`notstarted`/`ready`/`running`/`waiting`, `:61-63`) over the
workspace's Workflows (`countRuns` `:371-384`, the `workflow_ref_status` index) — what is running now, which
deletion legitimately reduces. Monthly = `max(audit count, live count)`: the month's
`{workspaceId, action=CREATE, resourceType=workflowrun}` audit events
(`core/audit/AuditQueryService.countRunsCreated`, the `workspace_time` index) versus the month's
live run documents (the `workflow_ref_creation` index). The max is deliberate — the audit write is
async best-effort, so a just-admitted run may not be audited yet (the live count covers that
race), while a deleted Workflow's runs leave the live count (the audit count covers deletion).
Submit re-checks the same counters after admitting the run, so a burst cannot over-fill a limit
(`QuotaService.assertAdmittedRunWithinQuotas` `:320-337`, called from `workflow/WorkflowService.java:636-646`,
which discards the run it refuses); a retry is checked only before it is created. Audit retention is floored at
60 days so the month window always holds (`core/audit/AuditRetentionService.java:28`).

## Payload caps

Two byte caps bound what the engine stores (`service-core/src/main/resources/application.properties:187-188`):
resolved params ≤ `flow.engine.task.params.max-bytes` (16384) at admission — oversize invalidates the task before
it is claimable (`engine/TaskExecutionService.java:211-226`) — and results ≤ `flow.engine.task.results.max-bytes`
(1 MB) at end, a storage guard behind each dispatcher's own limit (4096 bytes, Kubernetes' termination message) —
oversize fails the task and keeps the prior results (`engine/TaskRunService.java:64-65,1037-1051`). Large values
pass by reference (a workspace path or a URI).

## Storage

Per run, the dispatcher creates at most one persistent volume claim, keyed by the run id
(`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkflowService.java:36-56`). Defaults: 1Gi, the
cluster default class, `ReadWriteMany` (`service-dispatcher/src/main/resources/application.properties:43-45`),
with a 30 s wait for the claim to reach `Bound` or `Pending`
(`service-dispatcher/src/main/java/io/boomerang/kube/KubeServiceImpl.java:160,167-172`). Both executors mount the
same claim (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:283-305`); a completed run's
claim is deleted by the dispatcher's reconciliation, which asks the engine which of the runs it holds volumes for
are finished (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkspaceReconciler.java:57-72`,
`dispatcher/DispatcherService.java:498-521`). Nothing is shared between runs; files kept for later runs are
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
| Per-class or per-type concurrency caps | `claimableQuery` selects by task type and the poll's filters with no cap per type (`engine/TaskRunService.java:120-144`); a dispatcher caps only its own total | Load testing shows one task type starving others or overrunning the cluster |
| A per-task resource request or limit | Six deployment-wide `kube.resource.*` values sized by the operator and applied to every task container this dispatcher runs (`service-dispatcher/src/main/java/io/boomerang/executor/TaskResourceResolver.java:48-63`), the same per-deployment shape as the isolation tier (`dispatcher-kubernetes.md`) | A deployment genuinely needs mixed sizing behind one dispatcher, rather than a second dispatcher deployment with its own task types |
| Per-class kill switches | One global `flow.queue.enabled` that stops claiming only | An incident needs one class stopped while others run |
| Partitioning or leader election | Every instance does every job; CAS absorbs duplicates | Never expected; the escalation is cooperative `_id`-hash sharding of the sweep page |
| Per-class retry policies (a rate-limit backoff, failures known to be final) | One `Backoff` for every requeue; `retry` carries only `after` and `count`; a dispatcher's failure is requeued only for the three reasons that mean the task never started, `ExceededQuota`, `StartTimeout` and `RateLimited`, and every other failure ends the task (`common/model/StatusReason.java:45`, `engine/TaskRunService.java:1088-1111`) | A runtime whose rate-limit signal needs a different backoff from the shared one |
| A Kubernetes informer, shared across in-flight Tasks | One fabric8 `Watcher` plus a `CountDownLatch` per Task, on a virtual thread, with a listing reconcile every `kube.timeout.reconcileSeconds` (30 s) catching a dropped watch (`service-dispatcher/src/main/java/io/boomerang/kube/TektonServiceImpl.java:455-473`, `service-dispatcher/src/main/java/io/boomerang/executor/JobWatcher.java:23-33`) | A measured per-Task cost at the in-flight Task count a dispatcher actually carries — API-server watch connections or dispatcher memory, not thread count, which virtual threads already made cheap |
| A change stream on task runs entering `ready`, waking polls on every engine instance | A 30 s long poll woken by admissions on its own instance (`dispatcher/PollWakeups.java:24-55`) and re-checking every `flow.dispatcher.poll.recheck-ms`, 1 s by default, for the rest (`dispatcher/DispatcherService.java:70-73`, `:494-497`); the dispatcher reconnects at once | A measured hop latency on a multi-replica engine that the interval dominates, or an idle-query cost at the dispatcher count in use |
| Indexes for filtered claims | A poll's `taskRef`/`workflowRef` filters apply after `claim_page` (type, status, phase, claim.by, creationDate) has picked the ready tasks, keeping oldest first (`engine/TaskRunService.java:215-227`); folding them into `claim_page` would cost the unfiltered claim its index sort | A load test or incident where one engine's queue is large enough that the filtered scan shows: then separate indexes led by `taskRef` and by `workflowRef` |
| A capacity figure read from the cluster (namespace quota, node resources, or how long pods sit Pending) | A fixed slot count per dispatcher, `flow.dispatcher.task.max-in-flight`, sized by the operator; Kubernetes queues what the cluster cannot place, and a pod still Pending after `kube.timeout.startMinutes` (15, `service-dispatcher/src/main/resources/application.properties:37`) is handed back to the engine | A load test or incident where a fixed count both under-uses and overruns the same cluster |

## Also worth knowing

- Sent outbox rows and received inbox rows expire after 7 days; `task_locks` documents expire on their own index — all built by `_0021__Indexes` (`migration/_0021__Indexes.java:112-128`).
- Sweeps page 50 documents at a time; the dispatcher claims at most 20 per poll.
- A run's graph is read once per task, at its queue, projected to the six fields the graph walk reads
  (`DAGUtility.retrieveGraph`, `engine/DAGUtility.java:131-136`), and once more when the run finishes. A task's end
  reads only its dependants and the tasks they depend on, by the stored `dependencies.taskRef` under the `run_tasks`
  index and by name under `node_uniqueness` (`DAGUtility.retrieveDependants`, `:145-181`): in a chain of 200 tasks
  an end returns 4 task run documents where the graph read returned 202, and a wider fan-in costs one document per
  neighbour, never per task in the run
  (`DispatcherRequestDatabaseCommandsTest.anEndOfA200TaskRunReadsItsDependantsNotTheGraph`). A completion returns
  only the fields its transition publishes (`TaskRunService.transition`, `engine/TaskRunService.java:895-899`). An
  end therefore reads no other task run's params, spec or results
  (`DispatcherRequestDatabaseCommandsTest.anEndReadsNoOtherTaskRunWhole`). `advance` and the watcher's recovery keep
  the whole-graph read: they run rarely.
- A for-each parent completes on its last item's end thread and its dependants are admitted from there
  (`engine/TaskExecutionService.java:811-856`), so nothing but the admission hand-off sits between the last item
  and the next task.

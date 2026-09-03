# Performance and Scaling

Flow scales by running identical `service-core` instances against one MongoDB with no leader, no
partitioning, and no distributed lock: every state change is a single-document compare-and-set (CAS), so
any instance may do any job and duplicates are no-ops. This records what is measured, configured, and assumed.

## Running several instances

Every instance runs the full API, the claim endpoints, and every sweep; nothing is pinned to an
instance. Correctness rests on three primitives, all in the live path:

| Primitive | Where | What it guarantees |
| --- | --- | --- |
| Claim CAS (`findAndModify` on `_id` + full eligibility) | `service-core/src/main/java/io/boomerang/engine/TaskRunService.java:232-270` | One dispatcher owns a TaskRun; losers get `null` and skip. `claim.seq` is incremented per claim and checked on start/end writes (`:428`), so a stale claimant cannot write results. |
| Status-CAS transitions (`tryTransition`, `tryComplete`, `tryRequeue`) | `TaskRunService.java:548-590`, `engine/WorkflowRunStateHelper.java` | Concurrent advance from two instances converges: the second CAS finds the state already changed and performs no side effects. |
| Level-triggered sweeps on every instance | `engine/WorkflowWatcher.java:36-41` | Any survivor reaps a crashed instance's or dispatcher's work; overlapping sweeps are harmless because each acts only through the CAS above. |

Worker leases are not used (`claim.leaseExpiresAt` is only ever unset, `TaskRunService.java:185,216,583`,
so the `lease_sweep` index at `_0017__RunIndexes.java:78-83` stays empty);
crash recovery is the absolute `timeoutAt` written at claim = now + timeout + 5 s grace
(`TaskRunService.java:251,260`; `engine/RunTimeouts.java:14-17`) plus the gone-dispatcher sweep below.

## Queue fairness and the indexes behind it

The claim page is oldest-first with backoff exclusion inside the query, so a retried task cannot
starve fresh work and a burst of one workflow's tasks cannot jump the queue.
`findClaimable` (`TaskRunService.java:75-95`) selects `status=ready`, `phase=pending`, `type` in the
dispatcher's registered types, `claim.by` absent, and `retry.after` absent or elapsed, sorted by
`creationDate`, projected to `_id` only, then claims each candidate by CAS (page-then-CAS,
`dispatcher/DispatcherService.java:206-212`). Backoff is one generic class: 10 s base, doubling per
attempt, 5 min ceiling, up to 5 s jitter (`lib-common/src/main/java/io/boomerang/common/util/Backoff.java:12-22`),
with a budget of 3 requeues (`WorkflowWatcher.java:58`).

Dispatchers long-poll for 30 s, re-querying every 1 s with a page of 20 (`DispatcherService.java:34-36`).
Each connected dispatcher therefore costs about 4 indexed queries per second when idle (task claim,
task termination, run provision, run teardown). `flow.queue.enabled=false` stops claiming only; sweeps
keep running (`DispatcherService.java:38-40,111,176`).

Indexes are loader-owned; entity annotations are inert (`spring.data.mongodb.auto-index-creation=false`, `service-core/src/main/resources/application.properties:56`).

| Index | Collection / keys | Loader change unit | Serves |
| --- | --- | --- | --- |
| `claim_page` | `task_runs {type, status, phase, creationDate}` | `service-loader/src/main/java/io/boomerang/loader/migration/_0017__RunIndexes.java:66-71` | `findClaimable` page and its sort |
| `node_uniqueness` (unique) | `task_runs {workflowRunRef, name}` | `_0017__RunIndexes.java:117-122` | One TaskRun per DAG (directed acyclic graph) node; duplicate creation fails at insert |
| `timeout_sweep`, `wait_sweep` (sparse) | `task_runs {timeoutAt}`, `{waitUntil}` | `_0017__RunIndexes.java:84-87` | `reapTaskTimeouts`, `resumeDueWaitingTasks` |
| `claim_page`, `timeout_sweep`, `paused_lookup` | `workflow_runs {status, phase, creationDate}`, `{timeoutAt}`, `{pauseRequestedAt}` | `_0017__RunIndexes.java:164-181` | Run provision claim page; `reapWorkflowTimeouts` |
| `phase_creation_sweep`, `phase_start_sweep`, `workflow_ref_phase` | `workflow_runs {phase, creationDate}`, `{phase, startTime}`, `{workflowRef, phase}` | `_0037__SweepIndexes.java:76-93` | Teardown claim page (1/s per dispatcher), `recoverStalledRuns`, `cancelDeletedWorkflowRuns` |
| `claimed_sweep` | `task_runs {phase, claim.at}` | `_0037__SweepIndexes.java:95-100` | `reapClaimsFromGoneDispatchers` |
| `status_sweep` | `actions {status, creationDate}` | `_0037__SweepIndexes.java:102-107` | `closeStrayActions` |
| `dispatch_page`, `sent_ttl` (7-day expiry) | `events_outbox {status, occurredAt}`, `{sentAt}` | `_0018__EventAndLockIndexes.java:44-55` | Outbox drain; delivered rows expire |

## Sweep cadences and their properties

Every sweep runs on every instance with a random start offset; correctness never depends on ticks not
colliding. Each paged query carries `maxTimeMsec(5000)` so a slow database cannot hang the sweeper
(`TaskRunService.java:454,468`; `WorkflowRunStateHelper.java:267-304`).

| Sweeper | Property (default) | Start jitter | Page | Notes |
| --- | --- | --- | --- | --- |
| `WorkflowWatcher.sweep` (10 sweeps, `WorkflowWatcher.java:138-148`) | `flow.watcher.interval-ms` (30000); `flow.watcher.enabled` (true) | up to 30 s (`:125-128`) | 50 (`EngineConstants.java:12`) | Stall grace 60 s (`:55`); dispatcher declared gone after 60 s without a poll (`:73`); `pruneDeletedWorkflows` hard-deletes a deleted workflow's documents once its runs finalise (`:284`) |
| `ScheduleWatcher.sweep` | `flow.schedule.watcher.interval-ms` (30000); `flow.schedule.watcher.enabled` (true) | up to 30 s (`schedule/ScheduleWatcher.java:69-71`) | 50 | Cron fires by a `nextFireAt` CAS; 3 retries per failed fire (`:41`) |
| `OutboxDispatcher.drain` | `flow.events.outbox.interval-ms` (5000); bean exists only when `flow.events.sink.enabled=true` (`event/OutboxDispatcher.java:32-35`) | up to 5 s (`:59-61`) | 50 | 3 delivery attempts, then the row is marked dead (`:41`) |

No interval is set in `application.properties`; the defaults come from the `@Scheduled` fallbacks. Cost
(assumed, not load-tested): one paged indexed query per sweep plus a per-row count or `existsById`, so
20 instances add well under 10 fixed queries per second; per-row checks grow with in-flight runs.

## Virtual threads

Request handling runs on virtual threads (`spring.threads.virtual.enabled=true`, `application.properties:64`)
because each connected dispatcher parks one request thread for its 30 s poll. **Measured**:
`service-core/src/test/java/io/boomerang/dispatcher/DispatcherPollerVirtualThreadTest.java` drives 200
pollers through 10 cycles under Java Flight Recorder and asserts zero carrier-pinning events on the claim
path, so dispatcher count is not bound by the platform-thread pool. **Not measured**: any before/after
throughput comparison; the idle poll's database load (above) is the remaining known cost.

## HTTP client timeouts and the custom client requirement

Every outbound template carries real transport timeouts; log streaming has its own template so a
quiet-but-healthy stream is not cut at the control read timeout (`core/config/RestConfig.java:35-38`).

| Template (`RestConfig.java`) | Connect | Read (idle) | Pool lease | Trust | Used for |
| --- | --- | --- | --- | --- | --- |
| `internalRestTemplate` (`:92-94`), `selfRestTemplate` (`:98-102`) | 10 s | 60 s | 10 s | Default JVM trust | In-cluster control calls |
| `externalRestTemplate` (`:58-78`) | 10 s | 60 s | 10 s | Default; routes via `proxy.host`/`proxy.port` when both are set (`:42-46`) | Outbound integrations |
| `insecureRestTemplate` (`:82-88`) | 10 s | 60 s | 10 s | Trust-all (`:120-123`) | Internal endpoints with self-signed certificates |
| `streamingRestTemplate` (`:110-116`) | 10 s | 10 min | 10 s | Trust-all | Dispatcher log streams |

Pool: 200 total / 200 per route, keep-alive 5 min (`:48-54,174-189`). Companions: Mongo
`connectTimeoutMS=10000&socketTimeoutMS=120000` (`application.properties:45`); async request timeout 600 s
for the log stream (`:49`); dispatcher task default 60 min when the task sets none plus a 2 min watch
grace (`service-dispatcher/src/main/resources/application.properties:41,28`).

Enterprises run Flow behind proxies and private certificate authorities, so proxy routing, the trust-all
template, and per-template timeouts are product features that a framework upgrade MUST preserve with
equivalent behaviour (decision 0062). A run's timeout MUST be at least the transport timeout of the work
it guards (decision 0063).

## Quotas

Quotas are enforced at workflow create and run submit in `standalone` mode only; nothing in the engine
path re-checks them. `flow.quotas.enabled` defaults from `flow.mode` (`workspace/FlowQuotaProperties.java:32-38`),
and enforcement additionally requires the `features.workspaceQuotas` setting (`workflow/WorkflowService.java:209-214`).

| Quota (settings key, `workspace/WorkspaceService.java:81-86`) | Enforced at | Code |
| --- | --- | --- |
| `max.workflow.count` | Workflow create | `WorkflowService.java:392,888-899` |
| `max.workflowrun.concurrent`, `max.workflowrun.monthly` | Run submit | `WorkflowService.java:611,905-920` |
| `max.workflow.storage`, `max.workflowrun.storage` | Run submit (workspace size ≤ quota, else `QUOTA_EXCEEDED`) | `WorkflowService.java:445-491,921-950` |
| `max.workflowrun.duration` | Run submit, as the ceiling on the requested timeout | `WorkflowService.java:224-234` |

## Payload caps

Two byte caps bound what every executor must carry (`application.properties:149-155`): resolved params
≤ `flow.engine.task.params.max-bytes` (16384) at admission — oversize invalidates the task before it is
claimable (`engine/TaskExecutionService.java:61-62,161-175`) — and results ≤
`flow.engine.task.results.max-bytes` (4096, the portable Kubernetes termination-message ceiling) at end —
oversize fails the task and keeps the prior results (`TaskRunService.java:48-49,764-774`). Large values
pass by reference (a workspace path or a URI).

## Storage

Per run, the dispatcher creates at most one persistent volume claim per declared workspace: type
`workflowRun` is keyed by the run id (one per run), type `workflow` by the workflow id (shared, created
once) (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkflowService.java:37-72`,
`dispatcher/WorkspaceService.java:105-109`). Defaults: 1Gi, the cluster default class, `ReadWriteMany`
(`service-dispatcher/.../application.properties:30-35`), with a 30 s wait to bind
(`kube/KubeServiceImpl.java:143-199`). Both executors mount the same claims (`kube/KubeJobsExecutor.java:249-259`);
a completed run with workspaces is torn down by a claimed dispatcher (`WorkflowRunStateHelper.java:62-75`).

The only measured storage numbers are 2021 `fio` runs (60 s, 10 GiB file, `iodepth=64`) from the archived
2021 benchmarks (raw fio reports: the archived `boomerang-io/community` repo, `architecture/flow/benchmarking/`); re-run them on the target cluster.

| Cluster / storage class (2021) | Reads/s (random 4K) | Read MiB/s (1M seq ×8) | Writes/s (random 4K) | Write MiB/s (1M seq ×8) |
| --- | --- | --- | --- | --- |
| IBM Cloud Classic, GlusterFS | 4 829 | 407 | 276 | 70 |
| IBM Cloud Classic, OpenShift Container Storage (Ceph, 3 × 400 GB local SSD) | 15 800 | 214 | 3 547 | 13.9 |
| IBM Cloud ROKS VPC gen2, `ibmc-vpc-block-10iops-tier` | 2 993 | 49.2 | 2 000 | 49.1 |

## Not built

Each item is deliberately absent; load testing or an incident reopens it, not speculation (decisions 0060, 0061).

| Not built | What exists instead | Reopen when |
| --- | --- | --- |
| Per-class or per-type concurrency caps | `findClaimable` filters only by task type (`TaskRunService.java:75-95`) | Load testing shows one task type starving others or overrunning the cluster |
| Per-class kill switches | One global `flow.queue.enabled` that stops claiming only | An incident needs one class stopped while others run |
| Partitioning or leader election | Every instance does every job; CAS absorbs duplicates | Never expected; the escalation is cooperative `_id`-hash sharding of the sweep page |
| Retry rate-limit and deterministic-terminal classes | One `Backoff`; `retry` carries only `after`/`count`; a dispatcher-reported failure is not retried | A runtime that returns typed rate-limit signals is integrated |
| Worker leases and renewal | Absolute `timeoutAt` at claim; gone-dispatcher sweep at 60 s | Worker-crash recovery latency is shown to matter |

## Also worth knowing

- Sent outbox rows expire after 7 days; `task_locks` documents expire on their own index — both created by the loader.
- Sweeps page 50 documents at a time; the dispatcher claims 20 per poll.

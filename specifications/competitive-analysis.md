# Competitive analysis

How comparable products approach the same problems Flow's engine and dispatcher solve: claiming work,
liveness, timeouts, retry, schedules, payload limits, governance of AI agents, and the shape of a pull-based
execution tier. Each product section records what it is, what Flow could adopt and where the two conflict.
Flow facts are cited as `path:line`; competitor facts carry a link in the Sources section. Two abbreviations
are used: DAG (directed acyclic graph) and RBAC (role-based access control).

## IBM Cloud Code Engine

A managed serverless platform on Kubernetes: apps are Knative Services, jobs use IBM's own `batch-job-controller`
and a `JobRun` resource, and fleets run task queues on virtual servers, scaling to zero. A `TaskExecutor` could
target its job runs.

| Primitive | Model | Facts that matter for Flow |
| --------- | ----- | -------------------------- |
| Job / JobRun | One run = N array instances | Per-instance typed status `indices_details[i] = {status, retries, started_at, finished_at, last_failure_reason}` with reasons such as `OOMKilled`, `ContainerExitedCode1`, `ExceededEphemeralStorage`. Defaults 1 vCPU / 4 GB / 400 MB ephemeral; `scale_max_execution_time` 7200 s (max 86400); `scale_retry_limit` 3 — failed instances are retried by default; up to 1000 parallel instances; injected `JOB_INDEX`, `JOB_ARRAY_SIZE`, `JOB_INDEX_RETRY_COUNT`, `JOB_RETRY_LIMIT`. Submitted jobs queue as `pending` against project quota. Completed runs are deleted after 7 days. |
| Fleet | Pull-based task queue in an object-storage bucket; workers pull one task per container | Task states `Pending → Running → Succeeded/Failed`; a failed task returns to pending until 3 retries are spent. The same shape as Flow's Mongo claim queue: no broker, a durable queue in cheap storage, workers pull. |
| App | Knative Service | `--concurrency` hard per-instance limit; `--request-timeout` default 300 s, max 600 s; SIGTERM then SIGKILL. |
| Events | Knative sources (cron, Kafka, object storage) | One job run per event; the event arrives as `CE_*` environment variables; cron data capped at 4096 bytes; no documented retry or dead-letter. |
| Identity | Compute resource token | A projected token file exchanged for a cloud identity token, never an environment variable. |

| Code Engine does | Flow today | Flow could adopt |
| ---------------- | ---------- | ---------------- |
| Typed failure reason per instance (`last_failure_reason`, `retries`) | `statusReason` is a typed string on `TaskRunEndRequest` and `TaskRunEntity` (`OOMKilled`, `ImagePull`, `DeadlineExceeded`, `AdmissionDenied`, `ResultsTooLarge`, `DispatchError`, `DispatcherGone`, `LeaseExpired`); the container's exit code is not recorded | `exitCode` beside `statusReason`. Needs the data-model review. |
| Attempt number visible to the task (`JOB_INDEX_RETRY_COUNT`, `JOB_RETRY_LIMIT`) | The engine tracks `retry.count` (`lib-common/src/main/java/io/boomerang/common/model/RunRetry.java:15-19`) but the pod environment carries none of it (`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:111-150`); a requeued task re-runs blind | `FLOW_ATTEMPT` and `FLOW_MAX_ATTEMPTS` beside `FLOW_VERSION`. Dispatcher only; lets tasks checkpoint and key their side effects. |
| Resources mandatory, defaulted and clamped | No `resources` on `TaskSpec`/`TaskRunSpec` (`lib-common/src/main/java/io/boomerang/common/model/TaskSpec.java:15-23`); `KubeJobsExecutor` sets no `ResourceRequirements`; Tekton's are commented out (`service-dispatcher/src/main/java/io/boomerang/kube/TektonServiceImpl.java:205-228, 410`). Every task pod is `BestEffort`: first evicted, invisible to bin-packing | `resources {cpu, memory, ephemeralStorage}` on `TaskSpec`, dispatcher defaults plus a per-dispatcher clamp, reusing the quota family that already clamps `maxWorkflowDuration` (`WorkflowService.java:614-619`). Needs the data-model review. |
| Admission is quota-bounded: runs wait `pending` until capacity | Dispatcher fan-out is unbounded: `QueueService.processTaskRun` is `@Async` on the default executor (`service-dispatcher/src/main/java/io/boomerang/dispatcher/QueueService.java:41, 68`); the only bound is `PAGE_SIZE=20` per 5 s poll (`service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:36`) | One `dispatcher.tasks.maxInFlight` ceiling that skips the poll when full; unclaimed work stays in Mongo for other dispatchers. Not the per-type caps that wait for load evidence. |

Parity already: 7-day cleanup (`kube.task.ttlDays`, `service-dispatcher/src/main/resources/application.properties:39`); one run per
inbound event with a small typed payload (their 4 KB cron cap against Flow's 16 KB `flow.engine.task.params.max-bytes`,
`service-core/src/main/resources/application.properties:154`); big data by reference (object-storage mounts against the deferred artefact store).

| Code Engine | Flow | Position |
| ----------- | ---- | -------- |
| Retries failed instances by default (3 per index) | Never retries a dispatcher-reported failure: `TaskRunService.end` persists `failed` as terminal (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:754-757`); only timeouts, lost leases and gone dispatchers requeue | Keep Flow's default; `ContainerExitedCode1` is usually deterministic. `OOMKilled` and image-pull failures are the two classes worth an opt-in `retryOn` later. |
| Indexed job: one run, N instances, per-index status, rerun failed indices | Fan-out is DAG-level only; no matrix primitive | Do not add now. If a for-each task becomes a requirement, copy the per-index typed status and stable array size rather than Kubernetes `completions`, which loses the per-index reason. |
| Warm workers (fleets) keep the image and run tasks back-to-back | Pod per task | The strongest argument for a long-lived-worker executor whose `create` is "enqueue to a warm worker"; the fleet design is the reference when that executor is built. |
| Per-workload identity via a projected token file | Tasks have no identity; only the dispatcher holds a token (`service-dispatcher/src/main/java/io/boomerang/config/RestConfig.java:51-54`) | When tasks call back into Flow (the artefact store), mint a per-TaskRun short-lived token and mount it as a file, never in `PARAM_*`. |

## GitHub Agentic Workflows

A GitHub command-line extension (Go, MIT, GitHub Next) that compiles a Markdown file with YAML frontmatter into a
hardened `.lock.yml` GitHub Actions workflow which runs a coding agent (Copilot by default; Claude Code, Codex,
Gemini and custom engines) with repository context. Public preview since 2026-06-11. GitHub's own readme:
"requires careful attention to security considerations and careful human supervision, and even then things can
still go wrong."

| Job (per compiled lock file) | Token | Does |
| ---------------------------- | ----- | ---- |
| pre-activation | none | Role check (`roles:` default `[admin, maintainer, write]`), `skip-bots`, `cooldown`, `stop-after` deadline, `skip-if-match` dedup, `max-daily-ai-credits` budget check |
| activation | read | Sanitises event text, validates the lock file, bundles `prompt.txt` |
| agent | read-only | Runs the engine inside the Agent Workflow Firewall (container plus egress proxy; rootless Docker, gVisor or microVM profiles). Tools reach the agent through a Model Context Protocol gateway exposing only `allowed:` tools. Writes `agent_output.json` (requested actions) and `aw.patch`. Default `timeout-minutes` 20, `max-turns` 500, `max-ai-credits` 1000 |
| threat detection | read | The same engine re-reads the output and classifies prompt injection / secret leak / malicious patch; on detection the safe-output jobs are skipped |
| safe outputs | scoped write | Validates each requested action against a schema, `max:` counts and field allowlists, then applies it (~45 handlers: `create-issue`, `create-pull-request`, `add-labels`, `dispatch-workflow`, …) plus `noop` / `missing-tool` / `missing-data`; `staged: true` records without applying |

Vocabulary worth borrowing: **safe output** (a write the agent may request; a separate privileged job performs it
after validation), **staged mode** (recorded, not applied), **integrity filtering** (the gateway drops content whose
author trust is below `min-integrity`: `merged` > `approved` > `unapproved` > `none`), **outcome** (repository state
after a safe output lands: `accepted`, `rejected`, `pending`, `ignored`) and **ecosystem allowlist**
(`network.allowed: [defaults, node, python, github, …]` as named domain bundles). Known failure: GHSA-8h78-hpm7-29gg
(critical, versions 0.83.3–0.85.3) — the safe-output job captured raw stdout into an artifact before runner masking,
leaking a git authorization header; remediation required regenerating every lock file. The 2026 arXiv study of
agentic-workflow injection (2605.07135) found 496 exploitable cases in 13,392 workflows: issue text is the source in
86.5 %, GitHub write operations the sink in 78.8 %, and only 6.4 % had an effective guard. The rows below are the
inputs to the propose/dispose node type, where an AI task may only append tasks drawn from a declared menu.

| GitHub Agentic Workflows does | Flow today | Flow could adopt |
| ----------------------------- | ---------- | ---------------- |
| Safe outputs = a schema-validated, count-limited menu applied by a separate privileged job; `staged` dry run; `noop` required | Nothing built; the admission chokepoint (`TaskExecutionService.queue`) and materialise-all exist to hang it on | The AI node emits `proposals[]` validated against the workflow's declared menu (`allowedTasks`, per-entry `max`, field allowlists); the engine appends the TaskRuns; `staged: true` records without appending; `noop` is a mandatory explicit outcome. Touches `DAGUtility`/`TaskExecutionService` and needs the data-model review. |
| Input provenance decides what the agent may see (`min-integrity` by author role) | Trigger payloads (`POST /api/v2/webhook`, `/event`, `WebhookEventControllerV2.java:69-75, 207-241`) become params with no origin marker | An `origin` typed field on run params (`trigger`, `user`, `system`, `task-result`) so an AI node can be restricted to trusted origins. Needs the data-model review. |
| Budgets at every scope: per run (`max-ai-credits`, `max-turns`), per workflow per 24 h, per user, per subject (`cooldown`), absolute deadline | Workspace quotas only (`Quotas.java:5-10`; concurrency enforced at `WorkflowService.java:909-915`) | Per-run cost/turn ceiling on the AI node, per-workflow rolling window, per-actor trigger rate, per-subject cooldown; spend stored as typed fields on the TaskRun result. Needs the data-model review. |
| Outcomes and cost per accepted outcome | `ActionService` computes an approval rate (`service-core/src/main/java/io/boomerang/workflow/ActionService.java:310-322`) | Each proposal records `disposition` (`appended`, `staged`, `rejected`, `approved`) and later the appended TaskRun's terminal status. |
| Egress allowlists by named ecosystem, enforced by a per-agent proxy | No NetworkPolicy or egress proxy in either executor | `network.allowed: [ecosystems…, domains…]` on the task template, mapped to a NetworkPolicy (Jobs) or a proxy allowlist. |
| Opt-outs require a written justification (`dangerously-disable-sandbox-agent: "<≥20 chars why>"`) | Opt-outs are bare properties (`flow.security.enabled=false`) | A required `reason` string in the audit log for an unrestricted AI menu or a task below the isolation floor. |
| Lock-file drift is a security property | Runs pin `workflowRevisionRef` (`WorkflowRunEntity.java:46-48`, set `WorkflowService.java:1741-1743`) and revisions pin task versions (`WorkflowService.java:1554-1560`); a task with `taskVersion == null` resolves "latest" at run time (`TaskService.java:869-871`) | Near parity; resolve and write the version at revision save to close the null-version float. |

| GitHub Agentic Workflows | Flow | Position |
| ------------------------ | ---- | -------- |
| The agent job is the unit of work: 20-minute default, 6-hour ceiling, no durable state between jobs, no pause, resume or retry | Durable WorkflowRun/TaskRun records, claims with `claim.seq` fencing, timeout reaping, pause as an admission gate, run-scoped storage (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkspaceService.java:44-102`) | Differentiator. Do not chase Markdown-as-workflow authoring at the cost of durability. |
| Fan-out without join: `dispatch-workflow` is fire-and-forget; `call-workflow` is synchronous per worker | DAG with joins, `runworkflow` (`lib-common/src/main/java/io/boomerang/common/enums/TaskType.java:28`), lineage on `initiatedByRef` | Differentiator. Multi-repo rollouts run as one run with visible joins. |
| Threat detection by the same model as the agent | Proposals validated by the engine against a declared menu | Keep Flow's deterministic gate; a model-based scan only in addition to it. |
| Governance at compile time (`gh aw compile`) | Governance at save and admission (`PARAM_INVALID_NAME`, `PARAM_NAME_COLLISION`; payload caps via `tryInvalidate`, `TaskExecutionService.java:155-175`) | Equivalent placement; the gap is justified opt-outs. |
| Concurrency: one agent job per engine plus per-subject groups | `maxConcurrentRuns` per workspace; no per-workflow limit, no per-subject key | Both are named here; neither reopens the concurrency-cap decision alone. |
| Everything is GitHub: identity, audit, state, triggers | Workspace relationship graph, `audit` module, first-class tokens, CloudEvents in and out | Differentiator for non-GitHub estates; a GitHub integration exposing the same safe-output menu would be a catalogue item. |

Lessons: the privileged side is the crown jewel (their one critical advisory was in the dispose job) —
`FilterValuesOutputStream` masks declared sensitive params in `WorkflowRunService.streamTaskRunLog`, and a privileged
step run on behalf of a proposal MUST NOT persist unfiltered stdout; `missing-tool` / `missing-data` belong in the proposal schema.

## Temporal

The reference durable-execution engine: workflow-as-code whose state is rebuilt by replaying an event history; long-poll
workers claim tasks from task queues. Server MIT-licensed. Cloud bills per Action — every activity start, retry, heartbeat, timer and signal.

| Concern | Temporal | Flow |
| ------- | -------- | ---- |
| Unit of truth | Event history (hard cap 51,200 events / 50 MB; `ContinueAsNew` to roll) | The materialised `TaskRun` row set; no replay, no history cap |
| Claim | Long-poll `PollActivityTaskQueue`; sticky queue; per-worker slot limits | `findClaimable` oldest first on `creationDate`, filtered by registered type (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:75-94`); no slots |
| Activity retry | `RetryPolicy` 1 s × 2.0 up to 100×, attempts unbounded, `nonRetryableErrorTypes` | `Backoff` 10 s → 5 m, timeouts and lost leases only, ≤ 3 (`WorkflowWatcher.java:152-179`) |
| Timeouts | Four typed: `ScheduleToStart`, `StartToClose`, `ScheduleToClose`, `Heartbeat` | One `timeoutAt` baked at claim (`TaskRunService.java:251, 259-261`); an unclaimed TaskRun has no deadline of its own |
| Pause | Activity pause stops new retries; in-flight work finishes | `pauseRequestedAt` at the single admission gate — the same semantics |
| Schedules | Overlap policy `Skip` / `BufferOne` / `BufferAll` / `CancelOther` / `TerminateOther` / `AllowAll`; catch-up window; jitter | `ScheduleWatcher.fireDueSchedules` computes the next occurrence from now, collapsing any backlog (`service-core/src/main/java/io/boomerang/schedule/ScheduleWatcher.java:110-127`) — an implicit `Skip` |
| Priority / fairness | `priority_key` 1–5, `fairness_key` + weight; dispatch = tier → weighted round-robin → oldest first | None; one workspace's burst starves the rest |
| Idempotency | `WorkflowIdReusePolicy` + `WorkflowIdConflictPolicy` | Run-creation dedup deliberately not built |
| Payloads | 2 MB per payload; Search Attributes (indexed) vs Memo (not) | 16 KB params / 4 KB results; the typed-fields rule is the same idea as Search Attributes vs Memo |

**Adopt.** A queue-wait deadline distinct from the execution deadline (`ScheduleToStart`): `scheduleToStartAt` set at
queue time and reaped by the existing sweep with reason `NoDispatcher`, so a TaskRun whose type no dispatcher
registers no longer waits silently for the workflow timeout (needs the data-model review). Priority and a fairness
key (`workspaceId`) on the claim query, designed now and built on load evidence. A schedule overlap policy as a typed
enum (`overlap: skip | allow | cancelPrevious`, default `skip`) when schedules are next touched.

**Conflicts.** Replay determinism and history-as-truth would forbid the visual DAG. In-process activity heartbeats
do not transfer to container-per-task. Per-Action billing is the community's main complaint and a warning for any
future per-call metering.

## Trigger.dev

Apache-2.0, TypeScript-only durable compute: a Redis run queue that pull-based supervisors dequeue from (250 ms interval,
heartbeat 30 s), append-only run snapshots in Postgres, payloads over 512 KB offloaded to object storage, process checkpoints for long waits (Cloud only).

| Concern | Trigger.dev | Flow |
| ------- | ----------- | ---- |
| Unit | Run → attempts; one container process per run; `machine` presets from 0.25 vCPU to 8 vCPU / 16 GB | TaskRun → pod; no resources |
| Fairness | Score combining message age, queue size and available capacity; random-weighted dequeue | Oldest first |
| Concurrency | `queue.concurrencyLimit`, `concurrencyKey` = one queue copy per key value; `queues.pause()` | Workspace `maxConcurrentRuns` only |
| Retry | `maxAttempts 3`, 1–10 s, factor 2; `AbortTaskRunError` stops retries; `retry.outOfMemory.machine` retries an out-of-memory run on a bigger machine | No failure retry |
| Waits | `wait.createToken({ timeout, idempotencyKey })` → token with a completion `url`; `wait.completeToken` | `eventwait` + `/callback` (`WebhookEventControllerV2.java:136, 173`); `sleep` parks on `waitUntil` (`TaskExecutionService.java:641`, `WorkflowWatcher.java:232`) |
| Idempotency | `idempotencyKey` scope `run` / `attempt` / `global`, 30-day time-to-live, cleared on failure | Not built |
| Versioning | A run is locked to a version; `triggerAndWait` children lock to the parent, `trigger()` children float | `workflowRevisionRef` pinned; the `runworkflow` child version is left unpinned (`TaskExecutionService.java:813-814`) — the same float |
| Incident | 2025-09-26: 1.4 MB error payloads overloaded the run engine; the fix was a 16 KB error cap | Results capped at 4 KB (`TaskRunService.java:761-774`) — validated by their post-mortem |

**Adopt.** The fair-dequeue score is the concrete algorithm for the fairness half of the priority design. An opt-in
`idempotencyKey` on `WorkflowRunRequest` with a unique sparse index on `{workflowRef, idempotencyKey}`, a time-to-live
and clear-on-failure is the cheapest re-entry point for run-creation dedup (needs the data-model review). `eventwait`
could carry its own `timeout` instead of inheriting the task timeout. Retry-on-a-larger-size is the first retry class
once `resources` exist and `statusReason = OOMKilled`.

**Conflicts.** Code-as-workflow and checkpoint-the-process are both non-adoptable, and the TypeScript-only,
Cloud-preferred model is the reverse of Flow's polyglot-container, self-host-first stance.

## Vercel Workflows

Apache-2.0 TypeScript client library plus a managed platform (generally available 2026-04-16). `'use workflow'` compiles a
function into an orchestrating route re-executed from the top on every event; each `'use step'` is an isolated function
invoked from a queue. Persistence is a pluggable "World" (Vercel, Postgres, local; community Redis, Mongo).

| Concern | Vercel Workflows | Flow |
| ------- | ---------------- | ---- |
| Retry | Three step retries by default; `FatalError` skips; `RetryableError(msg, { retryAfter })` | Timeout-only |
| Timeout | No per-step timeout; bound by platform `maxDuration` | Per-task and per-run typed timeouts |
| Concurrency | "does not currently provide distributed locking or true exactly-once delivery"; no concurrency, throttle, debounce or priority | Workspace cap |
| Idempotency | Stable `stepId` across retries; a duplicate run "is created, billed, queued, and executed before discovering it's a duplicate" (#2376) | Not built |
| Versioning | Runs pinned to the creating deployment; renaming a step breaks in-flight runs | `workflowRevisionRef` |
| Payloads | 50 MB payload, 2 GB per run, 10,000 steps; per-run encryption key | 16 KB / 4 KB; at-rest encryption of `type=password` params not verified |
| Statuses | `pending, running, completed, failed, cancelled` for runs and steps | Closed `RunStatus` + `pauseRequestedAt` — the same minimalism |
| AI | `WorkflowAgent` tool loop inside a workflow; `needsApproval` is a tool property | Nothing built |

**Adopt.** `FLOW_TASKRUN_ID` in the task environment as a stable outbound idempotency key. A per-run derived
encryption key is the design to record if params are ever encrypted at rest. `needsApproval` is a third vendor
converging on "the model proposes, a gate approves, then it runs".

**Conflicts.** Replay-from-top and deployment-pinned runs are non-adoptable. What a code-first model cannot express
— per-step timeout, concurrency control, priority, a join beyond `Promise.all` — is a typed field on Flow's DAG, the
clearest evidence that DAG-first is a differentiator for the governed-execution buyer.

## Kestra

Declarative-YAML orchestrator, the closest product shape to Flow. Core Apache-2.0; RBAC, single sign-on, audit logs, tenants,
secrets managers, worker groups and the Kubernetes/cloud task runners are paid Enterprise Edition only ("paid" below). An
Executor walks the DAG and pushes ready tasks into a queue; Workers poll it; the open-source queue is a relational table polled
with `FOR UPDATE SKIP LOCKED`, with lock contention "12+ seconds" (#2600) and deadlocks (#9094) on record. Version 2.0 rebuilds around stateless gRPC workers.

| Dimension | Kestra | Flow |
| --------- | ------ | ---- |
| Licence / packaging | Apache-2.0 core; RBAC, audit, secrets, Kubernetes runner, tenants, worker groups paid | Apache-2.0 for everything: workspaces, RBAC via the relationship graph, `audit` module, `KubeJobsExecutor`/Tekton, tokens |
| Authoring | YAML flows; editor; Copilot generates YAML | Visual DAG (`@xyflow/react`), JSON export/import |
| Definition versioning | Every save = revision; the Executor reads the latest revision mid-run | Every apply = revision; runs pin `workflowRevisionRef` (`WorkflowRunEntity.java:46-48`); `taskVersion == null` floats to latest (`TaskService.java:869-871`) |
| Execution architecture | Executor pushes; workers poll a relational table; Kafka in the paid edition | Engine materialises all TaskRuns at admission; dispatcher pulls with `findAndModify` + `claim.seq` fencing; Mongo only, no broker |
| Task runtime | Plugins are Java classes in the worker process (1,200+); scripts in a process, Docker or a pod (paid) | Container per task, no in-engine code execution; 87 catalogue tasks / 130 revisions seeded |
| Crash recovery | Worker heartbeat + `worker-task-restart-strategy` (`AFTER_TERMINATION_GRACE_PERIOD` / `IMMEDIATELY` — "duplicate task executions may happen" / `NEVER`) | Batched lease heartbeat every 30 s, lease 90 s, `reapExpiredLeases`; gone-dispatcher sweep at 60 s; dispatcher reconcile loop every 30 s; the restart policy is fixed (requeue ≤ 3 for requeueable types, else time out) |
| Retry | Per task `retry: { type, maxAttempts, maxDuration }`; flow-level `RETRY_FAILED_TASK` | Timeout/lease requeue ≤ 3, `Backoff` 10 s → 5 m; reported failures terminal |
| Concurrency | Per flow `concurrency: { limit, behavior: QUEUE \| CANCEL \| FAIL }` | Per workspace `maxConcurrentRuns` only (`WorkflowService.java:909-915`) |
| Failure semantics | `allowFailure`, `errors` branch, `finally`, `afterExecution`; 13 states including `WARNING`, `RETRYING`, `PAUSED` | `executionCondition: always \| success \| failure` on edges (`ExecutionCondition.java:3-7`); `statusOverride`; no `finally`; closed 10-value `RunStatus` |
| Control flow | `ForEach`, `ForEachItem`, `Dag`, `LoopUntil`, `If`/`Switch`, `Subflow` (`wait`, `transmitFailed`) | `decision` (regex edges, `DAGUtility.java:348-368`), edge conditions, `runworkflow` without `wait` (`TaskExecutionService.java:795-835`), `eventwait`, `sleep`, `approval`/`manual`, locks; no loop |
| Pause / approval | `Pause` task: `pauseDuration`, `behavior: RESUME \| WARN \| CANCEL \| FAIL`; Human Tasks (paid) | `approval`/`manual` via `createActionTask` (`TaskExecutionService.java:952`) with group approval; run-level pause as an admission gate; no timeout-behaviour enum |
| Replay | Re-run from any task run, optionally on the latest revision | Retry = a new run (two-pointer); in-place partial re-run deliberately not built |
| Kubernetes runner (paid) | `resources`, `podSpec` overlays; `waitUntilRunning` `PT10M` / `waitUntilCompletion` `PT1H` split | `KubeJobsExecutor`: one deadline plus a watch grace |
| Payload limits | Context ~1 MB; oversized contexts crashed webserver and executor (#4631); large data by reference | 16 KB / 4 KB caps — the same lesson, tighter; artefact store deferred |
| Schedules | Timezone cron, `recoverMissedSchedules ALL \| LAST \| NONE`, `lateMaximumDelay`, `allowConcurrent`, backfill | Timezone cron/runOnce/advancedCron; leaderless claim-based firing; backlog collapsed to one fire; no overlap policy, no backfill |
| Secrets | Open-source: base64 env vars, "no encryption at rest, no audit trail"; paid: 13 backends | `type=password` params with redaction (`DataAdapterUtil`); no external store |
| AI | `AIAgent` task whose tools execute flows and tasks; Copilot | Propose/dispose planned — the agent appends, never executes |
| Scale claims | "~1500 executions/min" on the relational queue, "~2000 with Kafka" | Not measured |

| | Kestra | Flow |
| - | ------ | ---- |
| Pros | Richest control-flow vocabulary; per-flow concurrency; replay from any task; 1,200+ plugins; explicit restart and schedule policies; documented throughput; commercial momentum | Production tier in the open-source product; pinned-revision runs; pull-based fenced claims, no broker; container-per-task boundary; visual DAG; typed execution state; governed-agency direction |
| Cons | RBAC, audit, secrets and Kubernetes execution are paid; plugins run in the worker process; latest revision honoured mid-run; queue lock contention and duplicates under `IMMEDIATELY`; YAML limits dynamic branching | No loop; no per-workflow concurrency; failures never retried; single timeout (no queue-wait deadline); no run retention policy; no external secrets; no join on `runworkflow`; nothing built for AI; throughput unmeasured |

**Adopt.** A named lost-dispatcher policy per task template (`onLostDispatcher: requeue | fail`) making the
at-least-once trade-off visible (needs the data-model review). A `waitUntilRunning` scheduling deadline in
`KubeJobsExecutor`, distinct from the execution deadline — the pod-level twin of `scheduleToStartAt` and where
`ImagePull`/`AdmissionDenied` reasons come from. A pause behaviour enum for `approval`/`manual` (a timeout with a
declared outcome; touches `createActionTask` only). `allowConcurrent` and a missed-fire policy, merged with the
schedule overlap enum. `runworkflow` with `wait: true` that parks the parent task on the existing park path and maps
the child's terminal status (touches `TaskExecutionService`).

**Conflicts.** Push-to-queue and latest-revision-in-flight are the structural opposites, both with documented costs
on Kestra's side. The commercial lesson is sharper: everything an enterprise needs in production is behind the paid
line, and Flow ships all of it under Apache-2.0.

## n8n

The largest low-code automation tool, under the fair-code Sustainable Use License. A workflow execution is one Bull job on
one worker; nodes run in-process passing item arrays; only the Code node is offloaded to task runners (sidecar, auth token,
heartbeat 30 s, 300 s timeout). Version 2.0 was a hardening release after the expression-injection remote-code-execution
vulnerability CVE-2025-68613 (more than 14,000 exposed instances) and a sandbox escape.

| Concern | n8n | Flow |
| ------- | --- | ---- |
| Claim | Bull lease `QUEUE_WORKER_LOCK_DURATION=60000` renewed every 10 s; the leader marks dangling executions `crashed` | `findAndModify` claim; batched lease heartbeat every 30 s, lease 90 s, no leader |
| Concurrency | Per worker 10; no per-workflow limit (requested since 2024) | Workspace cap; no per-workflow limit either |
| Retry | Node `retryOnFail`, `maxTries` 3, `onError: stopWorkflow \| continueRegularOutput \| continueErrorOutput`; execution retry re-runs the whole execution with `retryOf` | Two-pointer `initiatedByRef` + `trigger=retry` (`WorkflowRunService.java:929-936`) |
| Retention | `EXECUTIONS_DATA_PRUNE=true`, `MAX_AGE=336` h; annotated executions are never pruned | No run retention — `pruneDeletedWorkflows` is a declared no-op (`WorkflowWatcher.java:270-275`); time-to-live only on outbox/inbox/locks (`_0018__EventAndLockIndexes.java:53, 71, 96`) |
| Schedule (2.36) | Durable scheduler: runs stored in the database, one instance claims each run, no leader, `N8N_SCHEDULER_MISFIRE_GRACE=60` s, three misfire policies | `ScheduleWatcher`: the same claim-based, leaderless design (`ScheduleWatcher.java:25-30, 120`); misfire policy implicit |
| Versioning | "Save as draft, Publish"; named versions; visual diff | Revisions with changelog; every save is live |
| Runtime | In-process JavaScript; external task runners with a 1 GiB payload cap | Container per task; 16 KB / 4 KB |
| AI | AI Agent root node; human-in-the-loop on tool calls; Guardrails node (jailbreak, personal data, secrets, URLs, topical, custom) | Nothing built |

**Adopt.** A misfire policy with grace on schedules (`misfire: skip | fireOnce | fireAll`, `misfireGraceSeconds`),
naming the backlog collapse at `ScheduleWatcher.java:113-119` as `fireOnce`. A `retain` flag on `WorkflowRunEntity`
exempting a run from pruning, when the retention policy is decided. Guardrail categories reused by name in the AI
task type. The task-runner broker contract (token, heartbeat, per-task timeout, payload cap) is now at parity with
the dispatcher protocol.

**Conflicts.** Whole-workflow-on-one-worker with in-memory item arrays is the opposite of materialise-all with
per-task claims. The security record is the positioning point: an in-process runtime with expression injection is
the attack surface; Flow's `decision` regex evaluation (`DAGUtility.java:406`) runs no user code.

## Langflow

MIT, IBM-owned via DataStax. A flow is components and typed edges executed in-process and synchronously
(`POST /api/v1/run/{flow_id}`). No durable execution, retries, scheduler or RBAC; four exploited vulnerabilities in 2025–26
share the user-supplied-code-to-`exec()` primitive (CVE-2025-3248, CVE-2026-33017, CVE-2026-5027, CVE-2026-55255). Do not
replicate the canvas, provider catalogue or in-process framework: run Langflow inside a task container and invoke
`/api/v1/run/{flow}`; Flow owns the claim, timeout, retry, pause and audit wrapper it lacks. Its per-invocation `tweaks`
map onto `PARAM_<NAME>`; its `Credential` global variables align with `type=password` params.

## Execution-tier references

Not competitors: the three best-documented answers to how a pull-based execution tier registers, routes, stays alive and scales.

**GitHub Actions self-hosted runners and actions-runner-controller.**

| Concern | GitHub | Flow |
| ------- | ------ | ---- |
| Identity | Registration token (1 h) or just-in-time config with `name`, `runner_group_id`, `labels` baked in, one job then removed | `POST /api/v1/dispatcher/register` with `name`, `host`, `taskTypes`; bearer dispatcher token |
| Routing | `runs-on` labels are cumulative and not validated — a typo waits forever | Typed `taskTypes` filter only; no labels, no groups |
| Assignment | An idle runner is picked; a job not picked up within 60 s is re-queued; no priority | Claim-then-compare-and-set per task; oldest first |
| Protocol | Runner long-polls up to 50 s, `acquirejob`, then renews the job lock every minute (lock ~10 min); a runner that stops renewing is abandoned | 5 s poll into a 30 s engine long-poll; batched lease heartbeat every 30 s, lease 90 s |
| Autoscaling | actions-runner-controller's listener long-polls for "Job Available" and scales an ephemeral runner set between `minRunners`/`maxRunners`; one pod per job | No autoscaling |
| Governance | Runner groups are the boundary; labels are routing only; never self-hosted runners on public repositories (PyTorch incident) | Workspace scoping on the engine side |

Transfer: registration carrying `labels`; a "not picked up in 60 s" re-queue as the `scheduleToStartAt` deadline.
Do not transfer label-only routing — keep `taskTypes` primary and validate labels at registration.

**Google Cloud Run Jobs, Cloud Batch, Cloud Tasks, Workflows.**

| Concern | Google | Flow |
| ------- | ------ | ---- |
| Cloud Run Jobs | Execution → up to 10,000 tasks; `CLOUD_RUN_TASK_INDEX/ATTEMPT/COUNT`; `--task-timeout` per attempt (max 7 days); `--max-retries` 3; `executionReason` enum | Attempt env not yet in the pod; `statusReason` typed; no run retention |
| Cloud Batch | `lifecyclePolicies[]{action: RETRY_TASK \| FAIL_TASK, actionCondition.exitCodes[]}` with reserved infrastructure codes (50001 preemption, 50005 `maxRunDuration`, …); `statusEvents[]{type, eventTime, exitCode, taskState}`; `priority` 0–99; 2 days max queued then auto-fail | Failures terminal; reasons typed; no events history |
| Cloud Tasks | `rateLimits` separate from `retryConfig` (`maxAttempts` 100, `maxBackoff` 3600 s); named-task dedup tombstone 24 h with "significantly increased latency" | `Backoff` 10 s → 5 m; dedup not built |
| Workflows | `retry` predicates, `try/except`, callbacks (12 h default), `parallel` with `concurrency_limit` | `eventwait` + `/callback`; DAG-level parallelism |

Transfer: exit-code → action on top of `statusReason` (retry only infrastructure classes); typed `statusEvents[]` as
an append-only attempt history. Do not transfer numeric priority with queue auto-fail, or task-name tombstone dedup.

**Tekton Pipelines.**

| Concern | Tekton | Flow |
| ------- | ------ | ---- |
| Liveness | No worker: the controller reconciles resources via informers; pod death read from `pod.Status.Phase == Failed`, `Terminated.ExitCode`, `isOOMKilled`, eviction | Dispatcher watch plus a reconcile loop (`kube.timeout.reconcileSeconds=30`) |
| Typed reasons | `TaskRunTimeout`, `TaskRunCancelled`, `TaskRunImagePullFailed`, `PodCreationFailed`, `PodEvicted`, `StepOOM`, `ToBeRetried`, … | `statusReason` — the same shape |
| Retry history | `retriesStatus []TaskRunStatus` keeps every attempt; a retry creates a fresh pod | `retry.count` only; the record is overwritten |
| Duplicate pods | Deterministic child names `<taskrun>-pod-retry<N>` plus an owner-reference check "to prevent duplicate resource creation … due to stale informer cache" | Adopt an existing Job by label — the same intent |
| Affinity | Affinity Assistant co-schedules TaskRuns sharing a single-node volume; "not recommended for clusters larger than several hundred nodes", autoscaler deadlock (#6543) | Run-scoped volume per cluster; no affinity |
| Retention / provenance | Pruner (`ttlSecondsAfterFinished`, history limits); Results (logs to blob); Chains signs the completed run and stores attestations | No retention; custody ledger proposed |

Transfer: `retriesStatus`-style archived attempts rather than overwriting; Pruner semantics for the retention policy; Chains'
"snapshot, sign, store elsewhere" for the custody ledger. Do not transfer the Affinity Assistant (pass-by-reference artefacts
remove the need) or resource-as-database reconciliation (`findAndModify` claims fence more strongly than an informer cache).

## Cross-product synthesis

Each row is a design three or more products converged on independently.

| Theme | Where | Flow | Flow could adopt |
| ----- | ----- | ---- | ---------------- |
| Typed failure reason / non-retryable classes | Code Engine `last_failure_reason`; Temporal `nonRetryableErrorTypes`; Trigger.dev `CRASHED`; Vercel `FatalError`; n8n `crashed` | `statusReason` typed on task end | `exitCode`; an opt-in `retryOn` per template |
| Queue-wait deadline separate from execution deadline | Temporal `ScheduleToStart`; Kestra `waitUntilRunning`; Code Engine `pending` | `timeoutAt` baked at claim only | `scheduleToStartAt` with reason `NoDispatcher`; the executor's scheduling deadline |
| Priority + fairness on the claim | Temporal; Trigger.dev score; Kestra worker groups | Oldest first by `creationDate` | Designed; built on load evidence |
| Schedule overlap / misfire policy as an enum | Temporal 6 policies; Kestra; n8n 3 policies + grace | Implicit backlog collapse | `overlap` and `misfire` enums with grace |
| Idempotency key with scope and time-to-live | Temporal id policies; Trigger.dev; Vercel hook tokens; GitHub `skip-if-match` | Not built | Opt-in `idempotencyKey` on submit |
| Attempt number + stable id visible to the task | Code Engine; Vercel `stepId`; Kestra attempts | Not in the environment | `FLOW_ATTEMPT`, `FLOW_MAX_ATTEMPTS`, `FLOW_TASKRUN_ID` |
| Small typed payloads, big data by reference | Code Engine 4 KB; Temporal 2 MB; Trigger.dev 512 KB offload; Kestra ~1 MB; incidents when exceeded | 16 KB / 4 KB | Parity — the caps are vindicated |
| Per-run budgets and rate limits | GitHub Agentic Workflows (5 scopes); Temporal Actions; Trigger.dev limits | Workspace quotas only | The budget list for the AI node |
| "Model proposes, a gate approves, then it runs" | GitHub safe outputs; Vercel `needsApproval`; n8n human-in-the-loop; Temporal signal approval | Propose/dispose proposed | The propose/dispose node |
| Retention policy with user pins | n8n annotated never pruned; Code Engine 7 d; Vercel 1/7/30 d | No run retention | A `retain` flag once the policy is decided |
| Explicit lost-worker policy | Kestra restart strategy; n8n `crashed`; Trigger.dev `CRASHED`; Temporal heartbeat timeout | The reaper hard-codes one policy | `onLostDispatcher` per template |

**Liveness.** Temporal, Trigger.dev, Kestra, n8n and Code Engine all detect a dead worker within seconds to a minute,
and Flow now matches them. The dispatcher runs a reconcile loop (`kube.timeout.reconcileSeconds=30`) that re-lists
the Job by label, re-opens a lost watch and adopts an existing Job for a re-claimed TaskRun instead of creating a
second one; `TaskWatcher` no longer exits the process on a closed watch. Each executor thread stamps a local
`LeaseRegistry` and one `LeaseHeartbeat` per dispatcher sends `PUT /api/v1/dispatcher/{id}/heartbeat {ids}` every
`flow.dispatcher.lease.beat-ms=30000`; the engine's `renewLeases` writes `claim.leaseExpiresAt` with
`flow.dispatcher.lease-ms=90000` in one multi-document update fenced on `claim.by`, and the `reapExpiredLeases`
sweep requeues or abandons with `statusReason=LeaseExpired`. A per-task heartbeat request was rejected: at 200
in-flight tasks it is 6.7 requests per second per dispatcher; the batch is one request per 30 s regardless of count.

| Failure | Detected by | Latency |
| ------- | ----------- | ------- |
| Dispatcher process dies | Lease expiry (`reapExpiredLeases`) or `lastConnected` staleness (`reapClaimsFromGoneDispatchers`) | 90 s or 60 s plus the sweep interval; the re-claim adopts the existing Job |
| Pod dies, watch open | `JobWatcher.eventReceived` → `end(failed)` with a typed reason | Seconds |
| Pod dies, watch closed | The reconcile loop re-lists the Job and re-opens the watch | One reconcile interval (30 s) |
| Pod hangs, no progress | `activeDeadlineSeconds` + `timeoutAt` | The task timeout — accepted; container-per-task has no generic progress signal |
| Dispatcher thread dies or an exception is swallowed | `QueueService` reports `failed` with `statusReason=DispatchError` | Immediate |

**Where Flow is differentiated.** A durable DAG with joins and typed per-task timeouts (against code-first replay: Temporal,
Trigger.dev, Vercel); Apache-2.0 with RBAC, audit, Kubernetes execution and workspaces in the open-source product (against
Kestra's paid edition and n8n's `.ee.` files); container per task with no in-engine code execution (against n8n's and
Langflow's exploited in-process runtimes); and the planned propose/dispose gate at task granularity (against agents that execute, such as Kestra's `AIAgent`).

## Dispatcher paradigm

Keep the dispatcher a separate deployable and make it a capability-registered runner. Every execution tier studied — GitHub
runners, actions-runner-controller, Trigger.dev supervisors, Kestra workers, Temporal workers, Code Engine fleets — is
pull-based and separate from its control plane; none pushes. What they have that Flow's dispatcher lacks is routing and affinity.

| Filter | State | Evidence |
| ------ | ----- | -------- |
| By task type | Present — registration carries `taskTypes`, the claim query filters on them | `DispatcherService.java:186-190, 206-207`; `TaskRunService.findClaimable:75-94` |
| By label / zone / cluster | Absent — every dispatcher of a type competes for every task | `findClaimable` has no label criterion |
| Run → dispatcher affinity | Absent, and a latent multi-cluster bug: the run-scoped volume is created in whichever cluster claimed `workflowrun/start`, but each TaskRun is claimed independently, so a dispatcher in another cluster can claim a task whose workspace it cannot mount | `WorkspaceService.java:44-102`; `findClaimable` |

Many replicas in one cluster are safe by design; dispatchers across clusters or zones are not. What Flow could
adopt next:

- Capability routing: registration carries `labels` (`cluster`, `zone`, `arch`, `gpu`, `runtimeClass`); a task template or workflow task declares `requires`; the claim query matches the subset. `taskTypes` stays the primary typed filter and labels are validated against a registered set. This is "zone queues" as one query criterion, not separate queues. Needs the data-model review (`labels` on `DispatcherEntity`, `requires` on `TaskRunEntity`, a compound index with `type`/`creationDate`).
- Run affinity as an automatic requirement: at `workflowrun/start` the engine stamps `requires.cluster` with the claimant's cluster label onto that run's TaskRuns that mount a run-scoped workspace. Preferred over Tekton's Affinity Assistant. Rides on capability routing and is the multi-cluster fix.
- One poll, not two: collapse `/{id}/workflows` and `/{id}/tasks` into `/{id}/work`; the heartbeat stays a separate batched `PUT`. Protocol only.
- Embedded dispatcher mode: `flow.dispatcher.embedded=true` runs the same `TaskExecutor` interface inside `service-core`, calling `DispatcherService` as Java. The compose stack has no dispatcher, so template tasks cannot execute locally today. The separate deployable stays the production shape; take this up when the local runtime work resumes.
- Autoscaling from queue depth: with `maxInFlight`, the engine exposes a claimable-depth gauge per label set and an autoscaler scales dispatcher replicas; "one task then exit" becomes a dispatcher flag.
- Attempt history: `attempts[]{seq, claimBy, startedAt, endedAt, statusReason, exitCode}` on `TaskRunEntity` instead of overwriting `statusMessage` on each requeue. Needs the data-model review.
- Exit-code → action: opt-in per template, `retryOn: [OOMKilled, ImagePull, DispatcherGone, LeaseExpired]`; everything else stays terminal. Touches the reaper's requeue rule and needs the data-model review; only on evidence of infrastructure-class failures in practice.

Not proposed: push delivery, a broker, process checkpointing, numeric priority queues.

## Cross-cutting candidates

In order of value against cost; the product tables above hold the rationale.

- `exitCode` beside `statusReason` on task end, and `FLOW_ATTEMPT`, `FLOW_MAX_ATTEMPTS`, `FLOW_TASKRUN_ID` in the task environment.
- `dispatcher.tasks.maxInFlight` as a poll gate.
- Close the `taskVersion == null` float at revision save.
- A queue-wait deadline (`scheduleToStartAt`, reason `NoDispatcher`) and the executor's `waitUntilRunning` split.
- Schedule `overlap` and `misfire` enums with grace.
- `resources` on `TaskSpec` with defaults and a clamp; retry-on-a-larger-size as the first retry class.
- A pause/approval timeout behaviour and an `eventwait` timeout of its own.
- `runworkflow` with `wait` / `transmitFailed` as a join.
- The safe-output menu, `origin` provenance, budget scopes, outcomes, guardrail names and `needsApproval` as the design inputs for the propose/dispose node — design, not build.
- Opt-in `idempotencyKey` on submit, a `retain` pin once retention is decided, `onLostDispatcher` per template, and the deferred designs (priority and fairness, `network.allowed`, justified opt-outs, per-run encryption, warm workers).

Not adopted: default retry of failed instances, indexed jobs, Markdown-as-workflow authoring, model-based threat detection as
the primary gate, event-sourced replay, process checkpointing, push-to-queue, latest-revision-in-flight, whole-workflow-per-worker with Redis leases, in-process AI frameworks.

## Sources

Code Engine: [job runs](https://github.com/ibm-cloud-docs/codeengine/blob/master/job-run.md) · [limits](https://github.com/ibm-cloud-docs/codeengine/blob/master/limits.md) · [parallel job runs](https://github.com/ibm-cloud-docs/codeengine/blob/master/job-run-parallel.md) · [auto-injected env vars](https://github.com/ibm-cloud-docs/codeengine/blob/master/envvar-autoinject.md) · [app scaling](https://github.com/ibm-cloud-docs/codeengine/blob/master/app-scale.md) · [fleets](https://github.com/ibm-cloud-docs/codeengine/blob/master/fleets-workloads.md) · [trusted profiles](https://github.com/ibm-cloud-docs/codeengine/blob/master/trusted-profiles-authenticate-file.md) · [Go client `JobRunStatus`](https://github.com/IBM/code-engine-go-sdk/blob/main/codeenginev2/code_engine_v2.go) · [Docling Serve + watsonx Orchestrate tutorial](https://developer.ibm.com/tutorials/document-processing-docling-serve-watsonx-orchestrate/).

GitHub Agentic Workflows: [architecture](https://github.github.com/gh-aw/introduction/architecture/) · [frontmatter](https://github.github.com/gh-aw/reference/frontmatter/) · [safe outputs](https://github.github.com/gh-aw/reference/safe-outputs/) · [integrity](https://github.github.com/gh-aw/reference/integrity/) · [threat detection](https://github.github.com/gh-aw/reference/threat-detection/) · [network](https://github.github.com/gh-aw/reference/network/) · [sandbox](https://github.github.com/gh-aw/reference/sandbox/) · [concurrency](https://github.github.com/gh-aw/reference/concurrency/) · [cost management](https://github.github.com/gh-aw/reference/cost-management/) · [outcomes](https://github.github.com/gh-aw/reference/outcomes/) · [OrchestratorOps](https://github.github.com/gh-aw/patterns/orchestrator-ops/) · [repo](https://github.com/github/gh-aw) · [GHSA-8h78-hpm7-29gg](https://github.com/github/gh-aw/security/advisories/GHSA-8h78-hpm7-29gg) · [arXiv 2605.07135](https://arxiv.org/html/2605.07135v1) · [review-gate critique](https://tenki.cloud/blog/github-agentic-workflows-review-gate).

Temporal: [retry policies](https://docs.temporal.io/encyclopedia/retry-policies) · [activity failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures) · [limits](https://docs.temporal.io/workflow-execution/limits) · [Actions](https://docs.temporal.io/cloud/actions) · [schedules](https://docs.temporal.io/schedule) · [priority & fairness](https://docs.temporal.io/develop/task-queue-priority-fairness) · [worker tuning](https://docs.temporal.io/develop/worker-tuning-reference) · [activity operations (pause)](https://docs.temporal.io/activity-operations) · [AI](https://docs.temporal.io/ai) · [repo](https://github.com/temporalio/temporal).

Trigger.dev: [how it works](https://trigger.dev/docs/how-it-works) · [machines](https://trigger.dev/docs/machines) · [queue & concurrency](https://trigger.dev/docs/queue-concurrency) · [errors & retrying](https://trigger.dev/docs/errors-retrying) · [wait for token](https://trigger.dev/docs/wait-for-token) · [idempotency](https://trigger.dev/docs/idempotency) · [versioning](https://trigger.dev/docs/versioning) · [supervisor env](https://trigger.dev/docs/self-hosting/env/supervisor) · [incident 2025-09-26](https://trigger.dev/blog/incident-report-sep-26-2025).

Vercel Workflows: [docs](https://vercel.com/docs/workflows) · [event sourcing](https://workflow-sdk.dev/docs/how-it-works/event-sourcing) · [errors & retries](https://workflow-sdk.dev/docs/foundations/errors-and-retries) · [hooks](https://workflow-sdk.dev/docs/foundations/hooks) · [idempotency](https://workflow-sdk.dev/docs/foundations/idempotency) · [versioning](https://workflow-sdk.dev/docs/foundations/versioning) · [vs Inngest](https://workflow-sdk.dev/docs/comparisons/workflow-sdk-vs-inngest) · [encryption changelog](https://vercel.com/changelog/workflow-encryption) · [issue #2376](https://github.com/vercel/workflow/issues/2376).

Kestra: [pricing / edition matrix](https://kestra.io/pricing) · [architecture](https://kestra.io/docs/architecture) · [performance tuning](https://kestra.io/docs/performance/performance-tuning) · [server lifecycle / restart strategy](https://kestra.io/docs/administrator-guide/server-lifecycle) · [concurrency](https://kestra.io/docs/workflow-components/concurrency) · [retries](https://kestra.io/docs/workflow-components/retries) · [replay](https://kestra.io/docs/concepts/replay) · [Pause](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.pause) · [Subflow](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.subflow) · [Schedule](https://kestra.io/plugins/core/trigger/io.kestra.plugin.core.trigger.schedule) · [Kubernetes runner](https://kestra.io/docs/task-runners/types/kubernetes-task-runner) · [AIAgent](https://kestra.io/plugins/plugin-ai/agent/io.kestra.plugin.ai.agent.aiagent) · issues [#2600](https://github.com/kestra-io/kestra/issues/2600) · [#4631](https://github.com/kestra-io/kestra/issues/4631) · [#9094](https://github.com/kestra-io/kestra/issues/9094).

n8n: [queue mode](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/enable-queue-mode.md) · [concurrency](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/control-concurrency.md) · [task runners](https://docs.n8n.io/deploy/host-n8n/configure-n8n/set-up-task-runners.md) · [durable scheduler](https://docs.n8n.io/deploy/host-n8n/configure-n8n/durable-scheduler) · [execution data](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/manage-execution-data) · [Guardrails](https://docs.n8n.io/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.guardrails.md) · [2.0 breaking changes](https://docs.n8n.io/changelog/v20-breaking-changes) · [Rapid7 on Ni8mare](https://www.rapid7.com/blog/post/etr-ni8mare-n8scape-flaws-multiple-critical-vulnerabilities-affecting-n8n/).

Langflow: [run route](https://docs.langflow.org/api-flows-run) · [env vars](https://docs.langflow.org/environment-variables) · [RBAC request #1864](https://github.com/langflow-ai/langflow/issues/1864) · [CVE-2025-3248](https://www.offsec.com/blog/cve-2025-3248/) · [CVE-2026-33017](https://research.jfrog.com/post/langflow-latest-version-was-not-fixed/).

Execution tiers: [self-hosted runners](https://docs.github.com/en/actions/reference/runners/self-hosted-runners) · [runner communication](https://docs.github.com/en/enterprise-server@3.13/actions/concepts/runners/communicating-with-self-hosted-runners) · [just-in-time config](https://docs.github.com/en/rest/actions/self-hosted-runners) · [actions-runner-controller scale sets](https://github.com/actions/actions-runner-controller/blob/master/docs/gha-runner-scale-set-controller/README.md) · [Cloud Run Jobs](https://docs.cloud.google.com/run/docs/create-jobs) · [Batch lifecycle policies](https://docs.cloud.google.com/batch/docs/automate-task-retries) · [Batch StatusEvent](https://docs.cloud.google.com/batch/docs/reference/rest/v1/StatusEvent) · [Cloud Tasks pitfalls](https://docs.cloud.google.com/tasks/docs/common-pitfalls) · [Tekton TaskRuns](https://tekton.dev/docs/pipelines/taskruns/) · [`taskrun_types.go` reasons](https://raw.githubusercontent.com/tektoncd/pipeline/main/pkg/apis/pipeline/v1/taskrun_types.go) · [affinity assistants](https://tekton.dev/docs/pipelines/affinityassistants/) · [deterministic pod names #4361](https://github.com/tektoncd/pipeline/pull/4361) · [Pruner](https://tekton.dev/blog/2026/02/05/introducing-tekton-pruner/) · [Chains](https://tekton.dev/docs/chains/).

# Competitive Analysis — Execution Substrates and Agentic Orchestrators

**Status:** 📎 **Research record (2026-09-01)** — findings and recommendations, not rulings.
Every "Recommend" cell below is a proposal; anything that adds a field, index, or collection
goes through the G2 data-model gate, and anything touching `DAGUtility` or
`TaskExecutionService` goes through G1, before it is built.

**Purpose.** Compare Boomerang Flow's engine and dispatcher against products that solve an
overlapping problem, and extract (a) what to adopt, (b) what conflicts with our approach and
deserves a ruling, and (c) where Flow is differentiated. Each section is self-contained; the
cross-cutting synthesis is §15 and the action list is §16.

| § | Subject | Category | Relationship to Flow |
| - | ------- | -------- | -------------------- |
| 1–4 | **IBM Cloud Code Engine** (apps, jobs, functions, fleets) | Managed execution substrate | Alternative *target* for the dispatcher (a `TaskExecutor` could create Code Engine job runs), and a source of job-run semantics. |
| 5–8 | **GitHub Agentic Workflows** (`gh-aw`) | Agentic orchestrator, GitHub-Actions-native | Competitor for the "run AI agents under governance" use case; direct evidence for the propose/dispose design in `repo-insights-engagement-inputs.md` §5. |
| 9 | **Temporal** | Durable execution (workflow-as-code, event-sourced replay) | The reference design the two below descend from; source of timeout, priority/fairness and schedule-policy vocabulary. |
| 10 | **Trigger.dev** | Durable tasks on a pull-based run queue, container per run | Closest to our dispatcher/queue concerns (machine presets, concurrency keys, fair dequeue, waitpoints). |
| 11 | **Vercel Workflows** | Durable execution as code, serverless | The "code, not DAG" competitor; tests whether DAG-first is a differentiator. |
| 12 | **Kestra** | Declarative YAML DAG orchestrator with a plugin catalogue | Closest *product shape* to Flow; open-core gating is the commercial comparison. |
| 13 | **n8n** | Low-code automation, item-stream execution | Largest low-code competitor; its 2.36 scheduler converged on our `ScheduleWatcher` design. |
| 14 | **Langflow** (short) | Visual LLM flow builder | Positioning for the thin LLM task type; the "BYO framework inside a task" boundary. |
| 15 | **Cross-product synthesis** | — | Themes seen in ≥ 3 products, and the one place Flow is the outlier. |
| 17 | **Execution-tier references**: GitHub Actions runners + ARC, Google Cloud Run Jobs / Batch / Tasks, Tekton | Runner protocols and controllers | The three systems that answer "how should a pull-based dispatcher register, route, lease and scale". |
| 18 | **Dispatcher paradigm proposal** | — | Keep the dispatcher separate; capability routing, run affinity, one poll, embedded mode, queue-depth autoscaling. |

Flow facts are cited as `file:line` on `feat-v5` at the date above; competitor facts carry a
source in §10.

---

## 1. IBM Cloud Code Engine — what it is (Stable)

A managed serverless platform on Kubernetes. Apps are Knative Services behind Istio; jobs use
IBM's own `batch-job-controller` and a `JobRun` resource (not raw `batch/v1` Jobs); fleets
(2025) run task queues on VPC virtual servers, including GPU profiles.

| Primitive | Model | Facts that matter for Flow |
| --------- | ----- | -------------------------- |
| **Job / JobRun** | `JobRun` CRD, one run = N array instances | Per-instance typed status: `status_details.indices_details[i] = {status, retries, started_at, finished_at, last_failure_reason}` where `last_failure_reason` ∈ `OOMKilled`, `ContainerExitedCode1`, `ExceededEphemeralStorage`, …. Defaults 1 vCPU / 4 G / 400 M ephemeral; `scale_max_execution_time` 7200 s (max 86400); `scale_retry_limit` 3 (max 5) — **failed instances are retried by default**; array up to 1000 parallel instances (indices to 9,999,999); injected `JOB_INDEX`, `JOB_ARRAY_SIZE`, `JOB_INDEX_RETRY_COUNT`, `JOB_RETRY_LIMIT`, `JOB_MODE`, `CE_JOB`, `CE_JOBRUN`. `--array-size-variable-override` keeps index arithmetic stable when re-running only the failed indices. Completed runs deleted after 7 days; subscription-created runs after 10 minutes. "Submitted batch jobs are automatically queued and are in a pending status until dispatched" against project quota (250 active instances, 128 vCPU, 512 GB). Daemon mode: no timeout, restart on non-zero exit, exit 0 = intentional stop. |
| **Fleet** | Pull-based task queue: tasks are objects in a COS bucket (`--tasks-state-store`); VPC workers pull the next task, run one container per task, scale to zero | JSONL task file (`{"cmds":[…],"args":[…],"idx":"…"}`); task states `Pending → Running → Succeeded/Failed`; a failed task "returns to the pending status until the maximum number of retries has been met" (3). I/O via COS mounts (`--mount-data-store /input=…`). Docling on fleets = one PDF per task at `--cpu 12 --memory 120G`, `--max-scale 8`. |
| **App** | Knative Service | `--concurrency` is a hard per-instance limit, `--concurrency-target` the soft scale trigger; `--request-timeout` default 300 s / max 600 s; `--scale-down-delay`; SIGTERM then SIGKILL after the request timeout. |
| **Events** | Knative sources (cron / Kafka / COS) | For jobs: **one job run per event**; the event arrives as `CE_ID/CE_TYPE/CE_SOURCE/CE_SUBJECT/CE_DATA` env vars; cron data capped at 4096 bytes. No documented retry or dead-letter. |
| **Identity** | Compute resource token | Mounted at `/var/run/secrets/codeengine.cloud.ibm.com/compute-resource-token/token`, exchanged for an IAM token; trusted profile matched by project or workload name; SDK `ContainerAuthenticator` refreshes. |

The linked tutorial (*Build a document processing workflow using Docling Serve and watsonx
Orchestrate*, IBM Developer, 2026-04-13) deploys `quay.io/docling-project/docling-serve-cpu`
as a Code Engine **app** (4 vCPU / 8 GB / 4 GB ephemeral, min 1 / max 2 instances, port 5001,
`DOCLING_SERVE_ENABLE_UI=1`) and a watsonx Orchestrate Python tool that streams the upload as
a **synchronous multipart POST**. It leaves `--concurrency` at the default 100 on a CPU-bound
4-vCPU service and relies on the 300 s default request timeout, while Docling Serve ships
`POST /v1/convert/source/async` + task polling for exactly this case. It teaches nothing about
engine internals; the pattern it uses is the one Flow already avoids — a task submits the
async job and an `eventwait` task resolves on `/callback`
(`service-core/src/main/java/io/boomerang/event/WebhookEventControllerV2.java:136, 173`),
rather than a pod blocking inside `kube.task.timeout=60` minutes.

## 2. Code Engine — adopt (Evolving)

| # | Code Engine does | Flow today | Recommend |
| - | ---------------- | ---------- | --------- |
| CE-1 | **Typed failure reason per instance** (`last_failure_reason`, `retries`) | Failure returns as free text: `TaskRunEndRequest` carries `status`, `statusMessage`, `results` (`lib-common/src/main/java/io/boomerang/common/model/TaskRunEndRequest.java:11-21`); the reason is stringified as `"<reason> - <message>"` at `service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:369-375`. `JobWatcher` already sees the typed condition reason (`service-dispatcher/src/main/java/io/boomerang/executor/JobWatcher.java:46-65`); the pod's `OOMKilled`/exit code is one container-status read away. | **YES — cheapest win, and DD-08 already mandates it.** Add `statusReason` (closed set: `OOMKilled`, `ExitCode`, `ImagePull`, `DeadlineExceeded`, `AdmissionDenied`, `ResultsTooLarge`, `Deleted`, `DispatchError`) + `exitCode` to `TaskRunEndRequest`/`TaskRun`. G2-gated. Does **not** build the retry classes ruled out 2026-08-18 — it records the fact those classes would need. |
| CE-2 | **Attempt number visible to the task** (`JOB_INDEX_RETRY_COUNT`, `JOB_RETRY_LIMIT`) | Engine tracks `retry.count` (`lib-common/src/main/java/io/boomerang/common/model/RunRetry.java:15-19`, written at `service-core/src/main/java/io/boomerang/engine/TaskRunService.java:548-585`) but the pod env has none of it (`service-dispatcher/src/main/java/io/boomerang/kube/KubeHelperService.java:111-150`: proxy, `DEBUG`, `CI`, `FLOW_VERSION`, `PARAM_*`). A timeout-requeued task (`service-core/src/main/java/io/boomerang/engine/WorkflowWatcher.java:151-178`, ≤ 3 attempts) re-runs blind. | **YES.** `FLOW_ATTEMPT` (0-based) and `FLOW_MAX_ATTEMPTS` beside `FLOW_VERSION`. No engine change; lets tasks checkpoint and idempotency-key side effects. Pairs with the `@boomerang-io/task-core` env PR (boomerang-io/tasks#13). |
| CE-3 | **Resources are mandatory, defaulted, and clamped** (1 vCPU / 4 G / 400 M; only listed CPU:memory combos; ephemeral ≤ memory) | No `resources` on `TaskSpec`/`TaskRunSpec` (`lib-common/src/main/java/io/boomerang/common/model/TaskSpec.java:15-23`, `TaskRunSpec.java:15-23`); `KubeJobsExecutor` sets **no** `ResourceRequirements`; Tekton's are commented out (`service-dispatcher/src/main/java/io/boomerang/kube/TektonServiceImpl.java:205-228, 410`). Every task pod is `BestEffort` QoS — first evicted under node pressure, invisible to scheduler bin-packing, and one runaway task can starve a node. | **YES, G2-gated.** `resources {cpu, memory, ephemeralStorage}` on `TaskSpec` (template-declared, overridable per workflow task), dispatcher-side defaults + per-dispatcher max clamp. Reuse the quota family — `WorkflowService.java:614-619` already clamps `maxWorkflowDuration` (`max.workflow.*`, `service-core/src/main/resources/application.properties:111-116`) — rather than invent a combos table. This is an operational hazard today, so it clears "proven need". |
| CE-4 | **Admission is quota-bounded**: runs sit `pending` until capacity | Dispatcher fan-out is unbounded: `QueueService.processTaskRun` is `@Async` on the default executor (`service-dispatcher/src/main/java/io/boomerang/dispatcher/QueueService.java:41, 68`; no custom executor bean); the only bound is `PAGE_SIZE=20` per 5 s poll (`service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:36`, `service-dispatcher/src/main/java/io/boomerang/client/EngineClient.java:159-169`) — up to 240 new pods/min per dispatcher with no back-pressure. The TODO exists at `EngineClient.java:178-179`. | **YES, minimal form.** One `dispatcher.tasks.maxInFlight` semaphore that *skips the poll* when full — claims stay in Mongo, so other dispatchers pick them up (the multi-instance model already supports this). Not the per-type caps CLAUDE.md defers; a single ceiling. |

Parity already achieved (nothing to do): 7-day cleanup (`kube.task.ttlDays`,
`service-dispatcher/src/main/resources/application.properties:39`); one run per inbound event with a
small typed payload (their 4 KB cron cap ≈ our 16 KB `flow.engine.task.params.max-bytes`,
`service-core/src/main/resources/application.properties:154`); big data by reference (COS mounts ≈
the deferred artefact store, boomerang-io/flow#319). The fleet design — no broker, durable
queue in cheap storage, workers pull, scale to zero — is the same shape as our Mongo claim
queue and validates `multi-instance-model.md`.

## 3. Code Engine — conflicts worth a ruling (Stable)

| Code Engine | Flow | Position |
| ----------- | ---- | -------- |
| **Retries failed instances by default** (3, per index, new pod, backoff) | **Never retries a dispatcher-reported failure** — `TaskRunService.end` persists `failed` as terminal (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:754-757`); only timeouts and gone-dispatcher reaps requeue. `kube.task.backOffLimit=0`, `restartPolicy=Never`. | Keep ours as the default — `ContainerExitedCode1` is usually deterministic. Code Engine's reason list names the two classes that *are* worth retrying: `OOMKilled` (if resources can grow) and image-pull/admission failures. CE-1 makes an opt-in `retryOn: [ImagePull, Deleted]` possible later without re-opening the retry-class ruling. |
| **Indexed job**: one run, N instances, per-index status, "rerun failed indexes" with a stable array size | Fan-out is DAG-level only (one TaskRun per node). No index/matrix primitive. | Do not add now. If a matrix/for-each task becomes a requirement, copy Code Engine's semantics (per-index typed status + rerun-failed-indices + stable array size on rerun) rather than K8s `completions`/`backoffLimitPerIndex`, which lose the per-index reason. Recorded as a trigger, not work. |
| **Warm workers** (fleets): the VM keeps the image and runs tasks back-to-back | Pod per task (`KubeJobsExecutor` / Tekton). | The strongest argument for the deferred long-lived-worker executor (ACA-sandbox / local Docker): a `TaskExecutor` whose `create` is "enqueue to a warm worker". Still deferred (2026-08-27); the fleet design is the reference to read when it re-opens. |
| **Per-workload identity** via a projected token file, never an env var | Tasks have no identity; only the dispatcher holds a token (`service-dispatcher/src/main/java/io/boomerang/config/RestConfig.java:51-54`). | Nothing today. When the artefact store lands (tasks calling back into Flow), mint a per-TaskRun short-lived token and mount it as a file — never in `PARAM_*`. |

## 4. Code Engine — incidental findings (Evolving)

- `agent.tasks.runtimeClassName` (CLAUDE.md, `runtime-contract.md`, `task-contract-research.md`)
  **does not exist** — the shipped property is `dispatcher.tasks.runtimeClassName`
  (`service-dispatcher/src/main/resources/application.properties:47-48`, `KubeJobsExecutor.java:111-112`).
  Docs need the rename.
- `QueueService.java:107-109`: a non-`BoomerangException` during dispatch is logged and **no
  `end` call is made**, so the TaskRun sits `queued` until the timeout reaper — a silent
  60-minute stall by default. One-line fix: report `failed` with reason `DispatchError` (CE-1).

---

## 5. GitHub Agentic Workflows (`gh-aw`) — what it is (Stable)

A GitHub CLI extension (`gh extension install github/gh-aw`; Go, MIT, GitHub Next) that
compiles a Markdown file with YAML frontmatter into a hardened `.lock.yml` GitHub Actions
workflow which runs a coding agent (Copilot default; Claude Code, Codex, Gemini, Pi; custom
engines by import) with repository context. Technical preview 2026-02-13, public preview
2026-06-11; v0.87.10 on 2026-08-31; 5,076 stars / 530 forks / 393 open issues on 2026-09-01.
GitHub's own README: "requires careful attention to security considerations and careful human
supervision, and even then things can still go wrong."

**Architecture (per compiled lock file).** Five job classes, communicating by artifacts:

| Job | Token | Does |
| --- | ----- | ---- |
| pre-activation | none | Role check (`roles:` default `[admin, maintainer, write]`), `skip-bots`, `forks`, `cooldown`, `stop-after` deadline, `skip-if-match` search dedup, budget check (`max-daily-ai-credits`). |
| activation | read | Sanitises event text, validates the lock file, bundles context (`prompt.txt`). |
| **agent** | **read-only** | Runs the engine inside the Agent Workflow Firewall (AWF: container + Squid egress proxy; runtime profiles `docker` rootless, `gvisor`, `docker-sbx` KVM microVM, `cloud-hypervisor`; Docker socket hidden). Tools via an MCP gateway that spawns MCP servers in isolated containers and exposes only `allowed:` tools. Writes `agent_output.json` (requested actions) and `aw.patch` (diff) as artifacts. Default `timeout-minutes` 20; `max-turns` 500; `max-ai-credits` 1000 (1 AIC = $0.01). |
| **threat detection** | read | Same engine re-reads `agent_output.json` + `aw.patch` + intent, classifies `prompt injection / secret leak / malicious patch`; on detection the job fails and safe-output jobs are skipped. |
| **safe outputs** | scoped write (`issues: write`, …) | Validates each requested action against a schema, `max:` counts, `allowed-fields`, blocked patterns; applies it. ~45 handlers (`create-issue`, `create-pull-request`, `push-to-pull-request-branch`, `add-labels`, `dispatch-workflow`, `call-workflow`, `create-check-run`, …) plus `noop` / `missing-tool` / `missing-data`. `staged: true` = dry run (step summary only). Every created object carries a hidden `gh-aw-workflow-id` marker. |

**Governance vocabulary worth borrowing verbatim.**

| Term | Meaning |
| ---- | ------- |
| Safe output | A write the agent may *request*; a separate privileged job *performs* it after validation. |
| Staged mode | The request is recorded, nothing is applied. |
| Integrity filtering | The MCP gateway drops content whose author trust is below `min-integrity` (`merged` > `approved` > `unapproved` > `none`); `trusted-users`, `blocked-users`, `approval-labels`, `refusal-labels` adjust it. |
| Outcome | The repository state observed *after* a safe output lands: `accepted` (PR merged / issue closed) · `rejected` · `pending` · `ignored` · `lifecycle`; `gh aw outcomes <run>` reports AIC per accepted outcome. |
| Ecosystem allowlist | `network.allowed: [defaults, node, python, containers, github, playwright, …]` — named domain bundles; engine API domains are added automatically and never include package registries. |

**Known failure.** GHSA-8h78-hpm7-29gg (critical, 2026-08-27, versions 0.83.3–0.85.3): the
**safe-output job** captured raw stdout/stderr into the `safe-outputs-items` artifact "before
GitHub Actions runner masking occurred", so a `create-pull-request` / `push-to-pull-request-branch`
run with `GH_AW_CI_TRIGGER_TOKEN` wrote a Base64 git authorization header into a downloadable
artifact. Remediation required regenerating every lock file — "upgrading the locally installed
CLI without regenerating the workflows does not replace their SHA-pinned action references."

**Outside evidence.** The AWI study (arXiv 2605.07135, 2026) over 13,392 workflows / 10,792
repositories found 496 exploitable agentic-workflow-injection cases: issue title/body is the
source in 86.5%, GitHub write/API operations are the sink in 78.8%, shell/tool execution in
61.9%, and only 6.4% of vulnerable workflows had an effective workflow-level guard. The
"review gate" critique (Tenki, 2026) makes the structural point: threat detection by the same
model that produced the output checks for *malice*, not *correctness*.

## 6. `gh-aw` — adopt (Evolving)

`repo-insights-engagement-inputs.md` §5.3 proposes the propose/dispose node type ("an LLM task
cannot execute anything — it may only **append** tasks drawn from a **whitelisted menu**").
`gh-aw` is the shipped, attacked, and patched version of that idea. Its vocabulary and its
scars are the input to that design.

| # | `gh-aw` does | Flow today | Recommend |
| - | ------------ | ---------- | --------- |
| AW-1 | **Safe outputs = a schema-validated, count-limited menu**, applied by a separate privileged job; `staged` dry run; `noop` required when nothing is proposed | Nothing built; §5.3 is a proposal. The engine's admission chokepoint (`TaskExecutionService.queue`) and materialise-all already exist to hang it on. | **Adopt the shape when §5.3 is designed**: the LLM node emits `proposals[]` validated against the workflow's declared menu (`allowedTasks`, per-entry `max`, field allowlists), the *engine* appends the TaskRuns, and `staged: true` records proposals into the run without appending. `noop` as a mandatory explicit outcome avoids "silent no-op vs failed" ambiguity. G1 (touches `DAGUtility`/`TaskExecutionService`) + G2. |
| AW-2 | **Input provenance decides what the agent may see** (`min-integrity` by author role, at the gateway) | Trigger payloads (`POST /api/v2/webhook`, `/event`, `WebhookEventControllerV2.java:69-75, 207-241`) become params with no origin marker; `@AuthCriteria` gates *who may call*, not *what the content is trusted for*. | **Adopt as a typed field**: `origin` on run params (`trigger`, `user`, `system`, `task-result`) so an LLM node can be restricted to `system`/`user` origins, and so the ledger records what the model was shown. G2. Consistent with the sensitive-upward/plain-downward trust model already ruled for params. |
| AW-3 | **Budgets as policy at every scope**: per run (`max-ai-credits`, `max-turns`, `timeout-minutes`), per workflow per 24 h (`max-daily-ai-credits`), per user (`user-rate-limit`), per subject (`cooldown`), absolute deadline (`stop-after`); breach → issue created, agent job skipped | Workspace quotas exist (`Quotas.java:5-10`: `maxWorkflowCount`, `maxWorkflowRunMonthly`, `maxWorkflowRunDuration`, `maxConcurrentRuns`; concurrency enforced at `WorkflowService.java:909-915` from setting `max.workflowrun.concurrent`, `WorkspaceService.java:83`) but nothing per model call or per trigger actor. §5.2 says "budgets as policy" without a list. | **Adopt the list as the §5.2 checklist** — per-run cost/turn ceiling on the LLM node, per-workflow rolling-window ceiling, per-actor trigger rate, per-subject cooldown. Store spend as typed fields on the TaskRun result (ties to the custody ledger, §2). G2. |
| AW-4 | **Outcomes**: post-hoc acceptance state and cost-per-accepted-outcome | `ActionService` computes an approval rate (`service-core/src/main/java/io/boomerang/workflow/ActionService.java:310-322`) — the only after-the-fact measure. | Cheap once AW-1 exists: each proposal records `disposition` (`appended`, `staged`, `rejected`, `approved`) and, later, the appended TaskRun's terminal status — "proposals that led to a succeeded task per unit spend" is Flow's outcome metric. No new collection; fields on the proposal record. G2. |
| AW-5 | **Egress allowlists by named ecosystem**, enforced by a per-agent proxy, every domain request logged with allow/deny in `gh aw audit` | No NetworkPolicy or egress proxy is applied by either executor; §3 of the engagement inputs proposes "NetworkPolicy from the zone spec" only. | Record `network.allowed: [ecosystems…, domains…]` as the task-template field shape for the executor-portfolio phase; the executor maps it to a NetworkPolicy (Jobs) or proxy allowlist (VM/microVM). The ecosystem bundle is the UX to copy — nobody wants to list `registry.npmjs.org` and `*.yarnpkg.com` by hand. Deferred with §3. |
| AW-6 | **Opt-outs require a written justification** (`sandbox.agent: false` needs `features.dangerously-disable-sandbox-agent: "<≥20 chars why>"`; `strict: false` workflows cannot run on public repos) | Opt-outs are bare properties (`flow.security.enabled=false`, `bash`-equivalent unrestricted tasks). | Copy the pattern for the two dangerous switches Flow will grow: LLM node with an unrestricted menu, and a task with `resources`/isolation below the dispatcher floor. A required `reason` string that lands in the audit log costs nothing. |
| AW-7 | **Lock-file drift is a security property**: a CLI upgrade without recompiling leaves stale SHA-pinned actions (the GHSA remediation) | Runs pin `workflowRevisionRef` (`WorkflowRunEntity.java:46-48`, set `WorkflowService.java:1741-1743`) and revisions pin each task's template version at creation (`WorkflowService.java:1554-1560`) — the equivalent of the lock file. **One drift class remains**: a task with `taskVersion == null` resolves "latest" at run time (`TaskService.java:869-871`). | Near parity. State the invariant in `runtime-contract.md` ("a WorkflowRun executes the task-template versions it was materialised with") and close the null-version hole by resolving and writing the version at revision save, which `:1554-1560` already does for pinned refs. |

## 7. `gh-aw` — conflicts and where Flow is differentiated (Stable)

| `gh-aw` | Flow | Position |
| ------- | ---- | -------- |
| **The agent job is the unit of work**: 20-minute default, 6-hour Actions ceiling, no durable state between jobs beyond artifacts / cache (7 days, 10 GB LRU) / a git branch (`repo-memory`); no pause, no resume, no retry of a failed agent run, no queue semantics beyond Actions concurrency groups | Durable WorkflowRun/TaskRun records, claims + `claim.seq` fencing, timeout reaping, pause as an admission gate, workflow-scoped PVC workspaces (`service-dispatcher/src/main/java/io/boomerang/dispatcher/WorkspaceService.java:44-102`) | **Differentiator.** Flow is the durable engine `gh-aw` deliberately does not have. Do not chase its authoring ergonomics (Markdown-as-workflow) at the cost of this. |
| **Fan-out without join**: `dispatch-workflow` is fire-and-forget (`max: 10`, workers "outlive the parent run", correlation ids are "optional and application-managed"); `call-workflow` is synchronous per worker | DAG with joins, `runworkflow` task type (`lib-common/src/main/java/io/boomerang/common/enums/TaskType.java:28`), lineage on `initiatedByRef` | **Differentiator.** Their OrchestratorOps pattern is what a DAG engine gives for free. Multi-repo rollouts and phased upgrades are the marketing use cases; Flow can run them as one run with visible joins. |
| **Threat detection uses the same model as the agent** | The propose/dispose split makes the *engine* the referee: proposals are validated against a declared menu, not judged by a model | **Keep ours.** Add a model-based scan only as an optional gate *in addition to* schema validation, never instead of it. The AWI numbers (78.8% of exploitable cases reach a write sink) argue for the deterministic gate. |
| **Governance at compile time** (`gh aw compile` pins, validates expressions, rejects misconfiguration before deploy) | Governance at save and admission (`PARAM_INVALID_NAME`, `PARAM_NAME_COLLISION` at workflow/template save; payload caps at admission via `tryInvalidate`, `TaskExecutionService.java:155-175`) | Equivalent placement. The one gap is AW-6 (justified opt-outs). |
| **Concurrency: one agent job per engine across all workflows** (`gh-aw-{engine}` group) plus per-subject groups (`issue.number`, `pr.number`) | `maxConcurrentRuns` per workspace (`WorkflowService.java:909-915`); no per-workflow limit and no per-subject key | Their per-engine serialisation is a crude global cap on AI spend — the per-type concurrency cap CLAUDE.md defers to load testing. Their per-subject group (one run per issue) is a dedup key at trigger time — the run-creation idempotency key the `idempotency-audit.md` residue deliberately dropped. Neither re-opens on this evidence alone; both are now named. |
| **Everything is GitHub**: identity = GitHub roles, audit = Actions logs, state = issues/PRs, triggers = repository events | Workspace-scoped relationship graph, `audit` module, first-class tokens (`bfd`), CloudEvents in/out | **Differentiator for non-GitHub estates**; a weakness for the GitHub-native buyer, who gets integrity filtering and safe outputs against GitHub objects for free. Flow's equivalent would be a GitHub integration exposing the same safe-output menu as tasks — a catalogue item, not engine work. |

## 8. `gh-aw` — incidental lessons (Evolving)

- **The privileged side is the crown jewel.** Their one critical CVE was in the *dispose* job,
  not the agent: raw stdout of a credential-bearing command persisted before masking. Flow's
  analogue is the log stream — `FilterValuesOutputStream` masks *declared sensitive params* in
  `WorkflowRunService.streamTaskRunLog`; when the engine ever runs a privileged step on behalf
  of a proposal, its stdout MUST NOT be persisted unfiltered.
- **Fuzzy schedules** (`daily around 14:00`) are jittered on purpose to avoid thundering herds
  across an organisation; Flow's `ScheduleWatcher` fires exact cron. Note only.
- **`missing-tool` / `missing-data` as first-class outputs** give the operator a structured
  signal that the agent lacked a capability rather than a vague failure — worth copying into the
  proposal schema (AW-1).

---

---

## 9. Temporal (Stable)

**What it is.** The reference durable-execution engine: workflow-as-code whose state is rebuilt
by deterministically replaying an event history; long-poll workers claim workflow and activity
tasks from task queues. Server MIT (SDK-java Apache-2.0), 22,740 stars, v1.31.2 (2026-07-08).
Cloud bills per *Action* ($50/M sliding to $25/M; base price doubled Nov 2024) — every activity
start, retry, heartbeat, timer, signal and Update is an Action. Self-host = four services over
Cassandra/PostgreSQL/MySQL with an immutable history-shard count.

| Concern | Temporal | Flow |
| ------- | -------- | ---- |
| Unit of truth | Event history (warn 10,240 events / 10 MB; hard 51,200 / 50 MB; `ContinueAsNew` to roll) | The materialised `TaskRun` row set — no replay, no history cap (`reconciler-analysis.md`) |
| Claim | Long-poll `PollActivityTaskQueue`; sticky queue + worker-local cache (5 s fallback); slot suppliers (`MaxConcurrentActivityTaskExecutionSize` Go 1,000 / Java 200 / Py 100) | `findClaimable` FIFO on `creationDate`, filtered by registered type (`service-core/src/main/java/io/boomerang/engine/TaskRunService.java:75-94`); no slots |
| Activity retry | `RetryPolicy` initial 1 s, coefficient 2.0, max 100×, attempts ∞, `nonRetryableErrorTypes` | `Backoff` 10 s → 5 m, timeouts only, ≤ 3 (`WorkflowWatcher.java:151-178`) |
| Timeouts | Four typed: `ScheduleToStart`, `StartToClose` (one mandatory), `ScheduleToClose`, `Heartbeat` (throttled to 0.8×) | One `timeoutAt` baked **at claim** (`TaskRunService.java:251, 259-261`), so an unclaimed TaskRun has no deadline of its own; only the run-level `reapWorkflowTimeouts` (`WorkflowWatcher.java:181`) catches it |
| Pause | Activity pause (1.28, public preview): stops *new retries*, in-flight finishes — identical semantics to our admission gate; workflow pause experimental | `pauseRequestedAt` at the single admission gate |
| Cancel vs terminate | Cancel is cooperative (`WorkflowExecutionCancelRequested`, cleanup runs); terminate is not visible to code | `WorkflowExecutionService.cancel` (`:140`) cancels pending/running tasks; no distinct terminate |
| Schedules | Overlap policy `Skip` (default) / `BufferOne` / `BufferAll` / `CancelOther` / `TerminateOther` / `AllowAll`; catch-up window (default one year); jitter; pause with notes | `ScheduleWatcher.fireDueSchedules` computes the next occurrence **from now**, collapsing any backlog (`service-core/src/main/java/io/boomerang/schedule/ScheduleWatcher.java:110-127`) — an implicit, unnamed `Skip`; no overlap policy against a still-running previous fire |
| Priority / fairness | GA 2026-05-05: `priority_key` 1–5 (default 3), `fairness_key` + `fairness_weight`, dispatch = priority tier → weighted round-robin per key → FIFO | None — one workspace's burst starves the rest of the FIFO |
| Idempotency | `WorkflowIdReusePolicy` (4 values) + `WorkflowIdConflictPolicy` (3 values) | Run-creation dedup deliberately dropped (`idempotency-audit.md` residue) |
| Payloads | 2 MB per payload, 4 MB per gRPC request; codec server for client-side decrypt; Search Attributes (indexed) vs Memo (not) | 16 KB params / 4 KB results; DD-08 typed-vs-annotation split is the same idea as Search-Attribute-vs-Memo |
| Audit | History is the audit record and is exportable; Cloud audit logs are control-plane only ("do NOT capture … Workflow Start") | `audit` module; run records |
| AI | Agent loop *inside* the workflow, model/tool calls as activities; `activity_as_tool(fn, start_to_close_timeout=…)`; human-in-the-loop = signal / Update | None built; propose/dispose proposed |

**Adopt.**

| # | Item | Recommend |
| - | ---- | --------- |
| TM-1 | **A queue-wait deadline distinct from the execution deadline** (`ScheduleToStart`) | YES, small: `scheduleToStartAt` set at `queue` time and reaped by the existing sweep with a typed reason `NoDispatcher` (CE-1). Today a TaskRun whose type no dispatcher has registered sits `ready` silently until the *workflow* timeout. G2 (one sparse-indexed field). |
| TM-2 | **Priority + fairness key on the claim query** | Defer until load testing (CLAUDE.md), but record the design now: `priority` (int) and `fairnessKey` (= `workspaceId`) on `TaskRunEntity`, compound index with `creationDate`, weighted round-robin cursor in `findClaimable`. No broker needed; this is what Trigger.dev also does (§10). |
| TM-3 | **Schedule overlap policy as a typed enum** | YES when schedules are next touched: `overlap: skip \| allow \| cancelPrevious` on `WorkflowScheduleEntity`, default `skip` (the current behaviour, made explicit). Pairs with n8n's misfire policy (§13). G2. |

**Conflicts / position.** Replay determinism and history-as-truth would forbid the visual DAG —
not adoptable. In-process activities with heartbeats do not transfer to container-per-task; Flow's
deadline reaping is the right guard for pods, *but* see §15 on liveness. The Action-based billing
is a warning for any future per-call metering of our own: charging per retry and per heartbeat is
the community's main complaint.

## 10. Trigger.dev (Stable)

**What it is.** Apache-2.0, TypeScript-only "durable compute": a Redis run queue that pull-based
supervisors dequeue from (`TRIGGER_DEQUEUE_INTERVAL_MS=250`, `_MAX_RUN_COUNT=10`, heartbeat 30 s),
append-only run snapshots in Postgres, events in ClickHouse, payloads > 512 KB offloaded to S3,
CRIU checkpoints for long waits (Cloud only). v4 GA 2025-08-18; 16,180 stars; self-host = 9
components needing "6+ vCPU and 12+ GB RAM". Priced per machine-second by preset plus
$0.000025 per run.

| Concern | Trigger.dev | Flow |
| ------- | ----------- | ---- |
| Unit | run → attempts; one container process per run; `machine` preset `micro` 0.25/0.25 → `large-2x` 8/16, 10 GB disk | TaskRun → pod; no resources (CE-3) |
| Fairness | "a score … combining the age of messages in the queue, the size of the queue, and the queue's available capacity", random-weighted multi-queue dequeue | FIFO |
| Concurrency | `queue.concurrencyLimit` (default unbounded), `concurrencyKey` = one queue copy per key value, env limit 100 + burst; `queues.pause()/resume()` | Workspace `maxConcurrentRuns` only |
| Retry | `maxAttempts 3, min 1 s, max 10 s, factor 2, randomize`; `AbortTaskRunError` stops retries; **`retry.outOfMemory.machine: "large-1x"`** — retry OOM on a bigger machine | No failure retry; `Backoff` for timeouts |
| Timeout | `maxDuration` (min 5 s, no documented default), wait time excluded, no hooks on timeout | `timeout` minutes, grace 5 s |
| Waits | `wait.for/until` (< 60 s no checkpoint); `wait.createToken({ timeout, idempotencyKey, idempotencyKeyTTL })` → token has a `url` + `publicAccessToken`; `wait.completeToken` | `eventwait` + `/callback?ref=&topic=&status=&access_token=` (`WebhookEventControllerV2.java:136, 173`); `sleep` parks on `waitUntil` (`TaskExecutionService.java:641`, `WorkflowWatcher.java:232`) |
| Idempotency | `idempotencyKey` scope `run` (default) / `attempt` / `global`, TTL 30 d, 2048 chars, "Failed runs automatically clear keys"; `ttl`, `delay`, `debounce`, `priority` = time offset in seconds | Dropped |
| Versioning | `YYYYMMDD.N`; "a task run is locked to the latest version of the code"; `triggerAndWait` children lock to the parent's version, `trigger()` children float | `workflowRevisionRef` pinned; `runworkflow` child version is a TODO (`TaskExecutionService.java:813-814`) — the same float |
| Statuses | 13 incl. `DELAYED`, `DEQUEUED`, `WAITING`, `CRASHED`, `SYSTEM FAILURE`, `EXPIRED` | `RunStatus` 10 + `RunPhase` 5 |
| Incident | 2025-09-26: 1.4 MB error payloads overloaded the run engine and Postgres; etcd went read-only at 60–70k pod entries; queue state diverged from run state. Fix: 16 KB error cap | Results capped at 4 KB (`TaskRunService.java:761-774`) — validated by their post-mortem |

**Adopt.**

| # | Item | Recommend |
| - | ---- | --------- |
| TD-1 | **Fair dequeue score** (age × size × available capacity) | Same slot as TM-2; Trigger.dev's formula is the concrete algorithm for the fairness half. Record; build on load evidence. |
| TD-2 | **Idempotency key with scope + TTL + clear-on-failure** | The cheapest re-entry point for run-creation dedup: `idempotencyKey` on `WorkflowRunRequest`, unique sparse index `{workflowRef, idempotencyKey}` with a TTL field, cleared when the run fails. Does not re-open the audit's "dropped" ruling by itself — it is opt-in per caller. G2. |
| TD-3 | **Waitpoint token with a completion URL** | Our `/callback` already is this; the missing piece is the token's own `timeout` and `idempotencyKey`. Add `timeout` to `eventwait` (today it inherits the task timeout) — no model change. |
| TD-4 | **OOM → retry on a larger size** | Only meaningful after CE-3; note as the first retry-class candidate when `statusReason = OOMKilled`. |

**Conflicts / position.** Code-as-workflow (no DAG; `triggerAndWait` composition) and
checkpoint-the-process (CRIU, registry-sized snapshots, needs CRI-O/containerd support) are both
non-adoptable. Their TypeScript-only, Cloud-preferred model is the reverse of Flow's
polyglot-container, self-host-first stance; the HN complaint "I do prefer Inngest as the code is
hosted where our actual app is hosted" is our argument.

## 11. Vercel Workflows (Stable)

**What it is.** Apache-2.0 TypeScript SDK (`workflow`, 2,364 stars) plus a managed platform (GA
2026-04-16, "over 100 million runs … more than 1,500 customers"). `'use workflow'` compiles a
function into an orchestrating route re-executed from the top on every event; each `'use step'`
becomes an isolated function invoked from Vercel Queues. Persistence/queue is a pluggable
"World": Vercel (managed), Postgres (graphile-worker, "does not work on serverless
environments"), local; community Worlds for Redis, Mongo, Durable Objects.

| Concern | Vercel Workflows | Flow |
| ------- | ---------------- | ---- |
| Retry | `DEFAULT_STEP_MAX_RETRIES = 3`, `FatalError` skips, `RetryableError(msg, { retryAfter })`; infra redelivery 2ⁿ⁻¹ s capped 900 s, `MAX_QUEUE_DELIVERIES = 48` | Timeout-only |
| Timeout | **No per-step timeout API** — bound by platform `maxDuration` (Pro 800 s); replay budget 240 s; run duration and `sleep` "No limit" | Per-task and per-run typed timeouts |
| Concurrency | "does not currently provide distributed locking or true exactly-once delivery"; Vercel's own Inngest comparison: "No direct function-level analog" for concurrency/throttle/debounce/rate-limit/priority | Workspace cap |
| Idempotency | Stable `stepId` "across retries"; deterministic hook tokens + `hook_conflict`; maintainer (#2376): "The duplicate run is created, billed, queued, and executed before discovering it's a duplicate" | Dropped |
| Versioning | Runs pinned to the creating deployment ("Skew Protection"); `deploymentId: 'latest'` to float; renaming a step breaks in-flight runs | `workflowRevisionRef` |
| Hooks | `createHook`, `createWebhook` at `/.well-known/workflow/v1/webhook/:token`, `defineHook().resume()`, token ≤ 255 B | `/callback` |
| Payloads | 50 MB payload, 2 GB/run, 10,000 steps, 25,000 events; "Runs that exceed 2,000 events or 1 GB … have slower replay"; AES-256-GCM per run via `getEncryptionKeyForRun()` | 16 KB / 4 KB; at-rest encryption of `type=password` params not verified |
| Statuses | `pending, running, completed, failed, cancelled` for runs *and* steps | Closed `RunStatus` + `pauseRequestedAt` flag — same minimalism |
| AI | `WorkflowAgent` runs the tool loop inside a workflow; tools marked `'use step'`; `needsApproval` is a first-class tool property | — |

**Adopt.**

| # | Item | Recommend |
| - | ---- | --------- |
| VW-1 | **Stable step id exposed to the task as its outbound idempotency key** | YES, trivial: `FLOW_TASKRUN_ID` env beside `FLOW_ATTEMPT` (CE-2). Stripe-style dedup for side-effecting tasks costs one env var. |
| VW-2 | **Per-run payload encryption key** | Record as the design for the redaction gap if params are ever encrypted at rest: key derived per run (HKDF), so a leaked dump cannot be bulk-decrypted. Not now. |
| VW-3 | **`needsApproval` on a tool = approval on a proposal** | Evidence for AW-1: three vendors (gh-aw, Vercel, n8n §13) converged on "the model proposes, a gate approves, then it runs". Propose/dispose is that gate at task granularity. |

**Conflicts / position.** Replay-from-top orchestration and deployment-pinned runs are both
non-adoptable. The instructive part is what a code-first model *cannot* express: no per-step
timeout, no concurrency control, no priority, no join beyond `Promise.all` — every one of these is
a typed field on Flow's DAG. This is the clearest evidence that DAG-first is a differentiator, not
a liability, for the governed-execution buyer.

## 12. Kestra (Stable)

**What it is.** Declarative-YAML orchestrator, closest product shape to Flow. Core Apache-2.0
(28.0k stars, 1.3 LTS 2026-03-03, $36M raised); **EE-only**: RBAC/SSO/SCIM, audit logs, tenants,
secrets managers (13 backends), worker groups, the Kubernetes/AWS/Azure/GCP task runners,
Kafka+Elasticsearch backend, plugin versioning. Architecture: Executor walks the DAG and pushes
ready tasks into a queue; Workers poll it; Scheduler evaluates triggers every second. OSS queue =
a JDBC `queues` table polled with `FOR UPDATE SKIP LOCKED` (`poll-size` 100, 25–500 ms); "~1500
executions/min with JDBC". 2.0 (RC7, not GA at 2026-08-25) rebuilds around stateless gRPC workers.

| Concern | Kestra | Flow |
| ------- | ------ | ---- |
| Claim | Executor **pushes** into the queue; workers poll a shared table; lock contention reported at "12+ seconds" (#2600), deadlocks (#9094) | Dispatcher **pulls** via `findAndModify` + `claim.seq`; no push |
| Liveness | Worker heartbeat; dead worker's jobs resubmitted per `worker-task-restart-strategy` = `AFTER_TERMINATION_GRACE_PERIOD` (default) / `IMMEDIATELY` ("duplicate task executions may happen") / `NEVER` | Deadline reaping off `timeoutAt`; leases deferred (AM-3) |
| Revision in flight | "the Executor checks for the latest flow revision rather than for the revision of the Execution" | Materialise-all from the pinned revision (Q-117) |
| Concurrency | Flow-level `concurrency: { limit, behavior: QUEUE \| CANCEL \| FAIL }`; paused executions keep their slot | Workspace-level only |
| Retry | `retry: { type: constant \| exponential \| random, maxAttempts, maxDuration, warningOnRetry }` per task; flow-level `behavior: CREATE_NEW_EXECUTION \| RETRY_FAILED_TASK` | Timeout-only, engine-wide |
| Failure semantics | `allowFailure` → `WARNING`, `allowWarning` → `SUCCESS`, `errors` branch, `finally` (while RUNNING), `afterExecution` (after terminal); 13 execution states incl. `WARNING`, `RETRYING`, `PAUSED`, `KILLING` | `executionCondition: always \| success \| failure` on edges (`ExecutionCondition.java:3-7`); `statusOverride` + `setwfstatus`; no `finally`; closed 10-value `RunStatus` |
| Pause | `Pause` task: `pauseDuration` (absent = forever), `behavior: RESUME \| WARN \| CANCEL \| FAIL`, `onResume` inputs; output `resumed.{by,on,to}` | `approval`/`manual` via `createActionTask` (`TaskExecutionService.java:952`); no timeout-behaviour enum |
| Control flow | `ForEach` (`concurrencyLimit` 0 = all), `ForEachItem` (batches → subflows), `Dag` (`dependsOn`), `LoopUntil`, `Subflow` (`wait`, `transmitFailed`, `revision`) | `decision` (regex edges, `DAGUtility.java:348-368`), `runworkflow` (submits, records `workflowRunRef`, no `wait`/`transmitFailed` option, `TaskExecutionService.java:795-835`); **no loop / for-each** |
| Replay | "re-run a workflow execution from any chosen task run", optionally on the latest revision | Retry = whole new run (two-pointer); in-place partial re-run ruled out (`reconciler-analysis.md`) |
| K8s runner (EE) | `resources.request/limit`, `podSpec`/`containerSpec` overlays; init container `kubectl cp`s `inputFiles`, sidecar collects `outputFiles` over a shared `emptyDir`; `waitUntilRunning` PT10M / `waitUntilCompletion` PT1H split | `KubeJobsExecutor`: termination-message results, `/data` emptyDir, single `latch.await(timeout + grace)` |
| Context size | "limit is around 1MB"; #4631 oversized contexts crashed webserver + executor | 16 KB / 4 KB caps — same lesson, tighter |
| Schedule | `recoverMissedSchedules ALL \| LAST \| NONE` (default ALL), `lateMaximumDelay`, `allowConcurrent false`, backfill UI; "Each trigger ID is limited to a single active execution" | Backlog collapsed to one fire; no `allowConcurrent`, no backfill |
| Secrets (OSS) | base64 `SECRET_*` env vars, "no encryption at rest, no audit trail"; EE = Vault/AWS/Azure/GCP/CyberArk… | `type=password` params, `DataAdapterUtil`; no external store |
| AI | `AIAgent` task (OSS) with `KestraFlow`/`KestraTask` tools — the agent **executes** flows; Copilot generates flows | Propose/dispose — the agent appends, never executes |

**Adopt.**

| # | Item | Recommend |
| - | ---- | --------- |
| KS-1 | **Restart-strategy enum on crash recovery** (`NEVER` / `IMMEDIATELY` / `AFTER_TERMINATION_GRACE_PERIOD`) | Our reaper already encodes one policy (requeue ≤ 3 for requeueable types, else time out — `WorkflowWatcher.java:55-58, 151-178`). Naming it as a per-task-template field (`onLostDispatcher: requeue \| fail`) makes the at-least-once trade-off visible to template authors. Small, G2. |
| KS-2 | **`waitUntilRunning` / `waitUntilCompletion` split** | YES for `KubeJobsExecutor`: a short scheduling deadline (image pull, quota, node) distinct from the execution deadline — the pod-level twin of TM-1, and it is where `ImagePull`/`AdmissionDenied` reasons (CE-1) come from. Dispatcher-only. |
| KS-3 | **Pause behaviour enum** (`pauseDuration` + `RESUME \| WARN \| CANCEL \| FAIL`) | For `approval`/`manual`: a timeout with a declared outcome instead of inheriting the task timeout and failing. Small; touches `createActionTask` only. |
| KS-4 | **Schedule `allowConcurrent` + explicit missed-fire policy** | Merge with TM-3 / n8n misfire (§13). |
| KS-5 | **Sub-workflow `wait` / `transmitFailed`** | `runworkflow` today returns the child id and moves on; a `wait: true` variant that parks the parent task (the `sleep`/`eventwait` park path already exists) and maps the child's terminal status is the join most users expect. G1 (touches `TaskExecutionService`). |

**Side by side (maintainer request, 2026-09-01).** "EE-only" = available only under Kestra's paid
Enterprise Edition (or Kestra Cloud), not in the Apache-2.0 core.

| Dimension | Kestra | Flow (v5, `feat-v5`) |
| --------- | ------ | -------------------- |
| Licence / packaging | Apache-2.0 core; RBAC, audit, secrets managers, K8s runner, tenants, worker groups EE-only | Apache-2.0 for everything: workspaces, RBAC via the relationship graph, `audit` module, `KubeJobsExecutor`/Tekton, tokens |
| Authoring | YAML flows; UI editor; Copilot generates YAML | Visual DAG (`@xyflow/react`), JSON export/import, Tekton YAML for task templates only |
| Definition versioning | Every save = revision; **executor reads the latest revision mid-run** | Every apply = revision; runs pin `workflowRevisionRef` (`WorkflowRunEntity.java:46-48`); one hole — `taskVersion == null` floats to latest at run time (`TaskService.java:869-871`) |
| Execution architecture | Executor pushes ready tasks into a queue; workers poll a JDBC table (`FOR UPDATE SKIP LOCKED`); Kafka+ES in EE; 2.0 moves to gRPC stateless workers | Engine materialises all TaskRuns at admission; dispatcher pulls via `findAndModify` + `claim.seq` fencing; Mongo only, no broker |
| Task runtime | Plugins are Java classes running **in the worker JVM** (1,200+); scripts in Process/Docker (OSS) or a K8s pod (EE) | Container per task, no in-engine code execution; 87 catalogue tasks / 130 revisions seeded |
| Crash recovery | Worker heartbeat + `worker-task-restart-strategy` (`AFTER_TERMINATION_GRACE_PERIOD` / `IMMEDIATELY` / `NEVER`) | Deadline reaping off `timeoutAt`; gone-dispatcher sweep at 60 s; a dead pod under a live dispatcher is caught by `JobWatcher` only while its watch is open (§15) |
| Retry | Per task: constant/exponential/random, `maxAttempts`, `maxDuration`; flow-level `RETRY_FAILED_TASK` | Timeout-only requeue, ≤ 3, `Backoff` 10 s → 5 m; reported failures are terminal |
| Concurrency | Per flow: `concurrency.limit` + `QUEUE \| CANCEL \| FAIL` | Per workspace `maxConcurrentRuns` only (`WorkflowService.java:909-915`) |
| Control flow | `ForEach`, `ForEachItem`, `Dag`, `LoopUntil`, `If`/`Switch`, `Subflow` with `wait`/`transmitFailed`, `errors`/`finally`/`afterExecution` | `decision` (regex edges), `executionCondition` on edges, `runworkflow` (no wait/join), `eventwait`, `sleep`, `approval`/`manual`, locks; **no loop** |
| Pause / approval | `Pause` task with `pauseDuration` + `RESUME \| WARN \| CANCEL \| FAIL`; Human Tasks (EE) | `approval`/`manual` Actions with group-membership approval; run-level pause as an admission gate |
| Replay | Restart from any task run, optionally on the latest revision | Retry = new run (two-pointer); in-place partial re-run ruled out |
| Payload limits | Execution context ~1 MB; large data to internal storage by URI | 16 KB params / 4 KB results; artefact store deferred |
| Schedules | Cron with timezone, `recoverMissedSchedules`, `lateMaximumDelay`, `allowConcurrent`, backfill UI | Cron/runOnce/advancedCron with timezone; leaderless claim-based firing; backlog collapsed to one fire; no overlap policy, no backfill |
| Secrets | OSS: base64 env vars, "no encryption at rest, no audit trail"; EE: 13 backends | `type=password` params with redaction (`DataAdapterUtil`); no external store |
| AI | `AIAgent` task that can **execute** flows/tasks as tools; Copilot | Nothing built; propose/dispose (agent appends, never executes) is the planned differentiator |
| Scale claims | "~1500 executions/min with JDBC, ~2000 with Kafka" | Not measured (load testing is the trigger for caps) |
| Maturity / traction | 1.3 LTS, 28k stars, $36M raised, weekly patches | v5 unreleased; v4 line in production |

| | Kestra | Flow |
| - | ------ | ---- |
| **Pros** | Richest control-flow vocabulary (loops, `Dag`, `finally`, typed retry per task); per-flow concurrency; replay from any task; 1,200+ plugins; explicit restart-strategy and schedule policies; documented throughput; commercial momentum. | Everything an enterprise needs is in the OSS product; pinned-revision runs (no shape change mid-run); pull-based claims with fencing, no broker; container-per-task security boundary; visual DAG; typed execution state (DD-08); governed-agency direction. |
| **Cons** | Production essentials are paid (RBAC, audit, secrets, K8s execution); plugins run inside the worker JVM; executor honours the latest revision mid-run; JDBC queue lock contention and at-least-once duplicates under `IMMEDIATELY`; JVM footprint; YAML limits dynamic branching. | No loop/for-each; no per-workflow concurrency; failures never retried and no typed failure reason; single timeout (no queue-wait deadline); crash detection bounded by the task timeout; no run retention policy; no external secrets; no join on `runworkflow`; nothing shipped for AI yet; throughput unmeasured. |

Kestra sells control-flow and catalogue breadth and charges for the production tier; Flow gives
away the production tier and is behind on control-flow breadth — §16 items 5–9 are the specific
gaps.

**Conflicts / position.** Push-to-queue and latest-revision-in-flight are the two structural
opposites, and both have documented costs on their side (lock contention; a running execution
changing shape). Kestra's commercial lesson is the sharper one: everything an enterprise needs to
run it in production — RBAC, audit, secrets, Kubernetes execution — is behind the EE line. Flow
ships all four in the Apache-2.0 product; that is the positioning sentence.

## 13. n8n (Stable)

**What it is.** The largest low-code automation tool (203,001 stars, $2.5B Series C Oct 2025),
under the fair-code Sustainable Use License (not OSI; `.ee.` files are proprietary). A workflow
execution is **one Bull job on one worker**; nodes run in-process passing item arrays; only the
Code node is offloaded to task runners (`n8nio/runners` sidecar, auth token, heartbeat 30 s, task
timeout 300 s). 2.0 (Dec 2025) was a hardening release after CVE-2025-68613 (expression-injection
RCE, CISA KEV, >14,000 exposed instances), "Ni8mare" CVSS 10.0, and a Pyodide sandbox escape; 161
advisories published, 147 of them in 2026.

| Concern | n8n | Flow |
| ------- | --- | ---- |
| Claim | Bull (`maxStalledCount: 0`), lease `QUEUE_WORKER_LOCK_DURATION=60000` renewed every 10 s; leader marks dangling executions `crashed` | `findAndModify` + deadline reaping |
| Concurrency | Per-worker `--concurrency` 10 / `N8N_CONCURRENCY_PRODUCTION_LIMIT`; **no per-workflow limit** (requested since 2024-04); "You can't retry queued executions" | Workspace cap; no per-workflow limit either |
| Retry | Node `retryOnFail`, `maxTries` 3 (2–5), `waitBetweenTries` 1000 ms, `onError: stopWorkflow \| continueRegularOutput \| continueErrorOutput`; execution retry re-runs the whole execution, `retryOf` / `retrySuccessId` | Two-pointer `initiatedByRef` + `trigger=retry` (`WorkflowRunService.java:929-936`) — the DD-08 anchor |
| Timeout | `EXECUTIONS_TIMEOUT=-1`, max 3600 s; per-workflow "Timeout Workflow" | Typed per task/run |
| Retention | `EXECUTIONS_DATA_PRUNE=true`, `MAX_AGE=336` h, `PRUNE_MAX_COUNT=10000`; **annotated executions and named versions are never pruned** | No run retention at all — `pruneDeletedWorkflows` is a declared no-op (`WorkflowWatcher.java:269-274`); TTLs only on outbox/inbox/locks (`_0018__EventAndLockIndexes.java:53, 71, 96`) |
| Schedule (2.36) | Durable scheduler: runs stored in DB, "only one instance claims each run", no leader, `N8N_SCHEDULER_MISFIRE_GRACE=60` s, three misfire policies (don't run / most recent / per rule) | `ScheduleWatcher`: identical claim-based, leaderless design (`ScheduleWatcher.java:25-30, 120`); misfire policy implicit |
| Versioning | 2.0 "Save as draft, Publish"; history with named versions; visual diff | Revisions with changelog; every save is live |
| Runtime | In-process JS; external task runners with `MAX_PAYLOAD` 1 GiB, `N8N_BLOCK_ENV_ACCESS_IN_NODE=true`; binary data to S3 | Container per task; 16 KB / 4 KB |
| Governance (EE) | Projects + roles, Git-branch environments, external secrets (6 stores), log streaming (`n8n.audit.*`, `n8n.workflow.*`, `n8n.runner.*`), SSO | Workspaces, relationship graph, `audit` module, no external secrets |
| AI | AI Agent root node; MCP client + server trigger (single-replica pinned); **human-in-the-loop on tool calls** (2.6); **Guardrails node** (jailbreak, NSFW, PII, secrets, URLs, topical, custom); Evaluations with metrics | — |

**Adopt.**

| # | Item | Recommend |
| - | ---- | --------- |
| N8-1 | **Misfire policy + grace** on schedules | Merge into TM-3: `misfire: skip \| fireOnce \| fireAll`, `misfireGraceSeconds`. The backlog-collapse in `ScheduleWatcher.java:113-119` becomes `fireOnce`, named. G2. |
| N8-2 | **Retention with user pins** ("annotated … never pruned") | When the retention policy is ruled (the no-op at `WorkflowWatcher.java:269-274` is waiting for it), include a `retain` flag or label on `WorkflowRunEntity` that exempts a run. Cheap, and it is the feature users ask for first. G2. |
| N8-3 | **Guardrail categories as a named list** | Not an engine feature. When the LLM task type is designed, its `guardrails:` field should reuse n8n's category names (`jailbreak`, `pii`, `secrets`, `urls`, `topical`) so the catalogue vocabulary is familiar. |
| N8-4 | **Task-runner broker contract** (auth token, heartbeat, per-task timeout, payload cap, external-sidecar mode) | Parity check only — our dispatcher protocol already has token, per-task timeout and caps; it lacks the heartbeat (see §15). |

**Conflicts / position.** Whole-workflow-on-one-worker with in-memory item arrays is the
opposite of materialise-all + per-task claims; a Redis lease as the crash detector re-opens AM-3.
The security record is the positioning point: an in-process JS runtime with expression injection
is the attack surface; Flow's container-per-task with no in-engine code execution does not have
that class of bug (the residual surface is `decision` regex evaluation, `DAGUtility.java:406`,
which runs no user code).

## 14. Langflow (short) (Stable)

MIT, IBM-owned via DataStax, 154,016 stars, v1.11.6 (2026-09-01); DataStax-hosted Langflow was
removed 2026-04-09 ("use Langflow OSS"). A flow is components + typed edges executed **in-process,
synchronously** (`POST /api/v1/run/{flow_id}`; `/v1/webhook/{id}` only returns "Task started in
the background"). No durable execution, no retries, no checkpoints, no scheduler, no RBAC (superuser
or not; #1864 open); `LANGFLOW_WORKERS` default 1 with an in-memory `asyncio` job queue. Four
exploited CVEs 2025–26 (CVE-2025-3248 unauth `exec()` in `/validate/code`, CISA KEV; CVE-2026-33017
same primitive in `/build_public_tmp`; CVE-2026-5027 path traversal; CVE-2026-55255 IDOR on
`/api/v1/responses`) — the same user-supplied-code-to-`exec()` primitive re-exploited a year apart.

| Boundary | Ruling this supports |
| -------- | -------------------- |
| Canvas, provider/vector-store catalogue, in-process LangChain, chat-session memory, MCP hosting | **Do not replicate** — run *inside* a task container ("BYO framework", `repo-insights-engagement-inputs.md` §5), invoking `/api/v1/run/{flow}` or the OpenAI-compatible `/api/v1/responses`. Flow owns the claim / timeout / retry / pause / audit wrapper Langflow lacks. |
| `tweaks` — per-invocation overrides on a stored definition | Already `PARAM_<NAME>`; note the mapping in the catalogue task that wraps Langflow. |
| Typed `Credential` vs `Generic` global variables, sourced from env | Aligns with `type=password` + `DataAdapterUtil`; nothing to add. |

## 15. Cross-product synthesis (Evolving)

**Themes seen in three or more products.** Each row is a design the market has converged on
independently; the "Flow" column says whether we already have it, and the action id ties it to
the list in §16.

| Theme | Where | Flow | Action |
| ----- | ----- | ---- | ------ |
| Typed failure reason / non-retryable classes | Code Engine `last_failure_reason`; Temporal `nonRetryableErrorTypes`; Trigger.dev `CRASHED`/`AbortTaskRunError`; Vercel `FatalError`; n8n `crashed` | Free-text `statusMessage` | CE-1 |
| Queue-wait deadline separate from execution deadline | Temporal `ScheduleToStart`; Kestra `waitUntilRunning`; Code Engine `pending` queue | `timeoutAt` baked at claim only | TM-1, KS-2 |
| Priority + fairness on the claim | Temporal (GA 2026-05); Trigger.dev score; Kestra worker groups | FIFO by `creationDate` | TM-2 / TD-1 (deferred, designed) |
| Schedule overlap / misfire policy as an enum | Temporal 6 policies; Kestra `recoverMissedSchedules` + `allowConcurrent`; n8n 3 misfire policies + grace | Implicit backlog collapse | TM-3 / N8-1 / KS-4 |
| Idempotency key with scope and TTL | Temporal id policies; Trigger.dev `run\|attempt\|global` + TTL; Vercel hook tokens; gh-aw `skip-if-match` | Dropped | TD-2 (opt-in) |
| Attempt number + stable id visible to the task | Code Engine `JOB_INDEX_RETRY_COUNT`; Vercel `stepId`; Kestra attempts | Not in env | CE-2, VW-1 |
| Small typed payloads, big data by reference | Code Engine 4 KB / COS; Temporal 2 MB; Trigger.dev 512 KB offload; Kestra ~1 MB; incidents at Trigger.dev and Kestra when exceeded | 16 KB / 4 KB | Parity — the caps are vindicated |
| Per-run budgets and rate limits | gh-aw (5 scopes); Temporal Actions; Trigger.dev env limits | Workspace quotas only | AW-3 |
| "Model proposes, a gate approves, then it runs" | gh-aw safe outputs; Vercel `needsApproval`; n8n HITL on tool calls; Temporal signal/Update approval | Propose/dispose proposed | AW-1 |
| Retention policy with user pins | n8n annotated/named never pruned; Code Engine 7 d / 10 min; Vercel 1/7/30 d | No run retention at all | N8-2 |
| Explicit lost-worker policy | Kestra restart-strategy; n8n `crashed`; Trigger.dev `CRASHED`; Temporal heartbeat timeout | Reaper hard-codes one policy | KS-1 |

**The one place Flow is the outlier — liveness.** Temporal (heartbeat timeout), Trigger.dev
(heartbeat per snapshot, 30 s), Kestra (worker heartbeat), n8n (Bull lease 60 s renewed every 10 s)
and Code Engine (its controller) all detect a dead worker within seconds to a minute. Flow detects
it only when `timeoutAt` elapses, so crash-to-requeue latency equals the task's full timeout — 60
minutes by the dispatcher default (`kube.task.timeout=60`). AM-3 deferred leases with trigger
conditions and `leaseExpiresAt` is "declared and indexed but written nowhere" (CLAUDE.md). This
analysis does not re-open AM-3; it records that 5 of 5 peers chose the other side, so the maintainer
can weigh that against AM-3's triggers. A middle path exists that keeps the pod model: the
*dispatcher* heartbeats (`updateLastConnected` already runs every poll, `DispatcherService.java:116-120`),
and `reapClaimsFromGoneDispatchers` already requeues after `DISPATCHER_STALE_MILLIS = 60000`
(`WorkflowWatcher.java:326-361`) — so a dead *dispatcher* is caught in ~1–2 minutes today. What is
not caught is a dead *pod* under a live dispatcher, and that is exactly what `JobWatcher`
observes (`status.failed`, `DELETED`) and today reports only as free text. CE-1 closes most of the
gap without a lease.

**How to close the liveness gap (proposed 2026-09-01, not ruled).** The gap is narrower than
"no heartbeat" once the failure modes are separated:

| Mode | Detected today by | Latency | Gap |
| ---- | ----------------- | ------- | --- |
| 1. Dispatcher process dies | `reapClaimsFromGoneDispatchers` on `lastConnected` staleness (`WorkflowWatcher.java:69, 363-369`) | 60 s + sweep interval | **Duplicate execution**: the requeued TaskRun is re-claimed and `create` builds a new Job with `generateName` and no existing-Job check (`KubeJobsExecutor.java:188-196`), while the orphan pod may still run; `claim.seq` fences the result write, not the side effects |
| 2. Pod dies, watch open | `JobWatcher.eventReceived` → `TASK_EXECUTION_ERROR` → `end(failed)` | seconds | Reason is free text (CE-1) |
| 3. Pod dies, **watch closed** (API-server hiccup) | nothing — `JobWatcher.onClose` logs only (`JobWatcher.java:88-92`); `latch.await(timeout + 2 min)` | full task timeout (60 min default) | **The outlier case** |
| 4. Pod hangs, no progress | `activeDeadlineSeconds` + `timeoutAt` | task timeout | Correct — peers' heartbeat needs SDK code in the activity; container-per-task has no generic progress signal. Accept. |
| 5. Dispatcher thread dies / exception swallowed | nothing (`QueueService.java:107-109`) | task timeout | §4 fix |
| Tekton variant | `TaskWatcher.onClose` calls `System.exit(1)` (`TaskWatcher.java:110-114`) | — | Worse than 3: a lost watch kills the dispatcher, turning every other in-flight task into mode 1 |

| Option | Closes | Cost | Recommend |
| ------ | ------ | ---- | --------- |
| **A. Dispatcher-side reconcile** — replace the single `latch.await` with an informer/resync loop (`kube.timeout.reconcileSeconds`, default 30) that re-lists the Job by label and applies `JobWatcher`'s terminal logic; on `onClose` set a flag and re-open the watch; make `create` adopt an existing Job with the TaskRun label instead of creating a second one; same for `TektonServiceImpl` and remove the `System.exit(1)` | 3, the duplicate in 1, the Tekton variant | dispatcher only; no G1/G2; AM-3 untouched | **YES, first** — after it, pod death is observed within one reconcile interval, matching peers |
| B. Per-TaskRun lease heartbeat (AM-3) — `PUT /api/v1/dispatcher/taskrun/{id}/heartbeat` every N s writes the already-indexed `leaseExpiresAt`; a sweep requeues expired leases | 1 faster (N s instead of 60 s), 5 | new endpoint, N writes per task per interval, re-opens AM-3 | Only if A leaves mode 1's ~60–90 s as a measured problem — that measurement is AM-3's trigger |
| C. Engine reads Kubernetes directly | 3 | breaks the `TaskExecutor` SPI boundary | No |

Sequence: A + CE-1 (typed reasons) + the §4 `QueueService` fix in one dispatcher slice; measure
crash-to-requeue for mode 1; then decide B.

> **Shipped 2026-09-01 on `feat-v5-liveness`** (maintainer-ruled the same day): **A** as the explicit
> reconcile loop (`kube.timeout.reconcileSeconds=30`, watch re-open on loss, adopt-existing-Job,
> `TaskWatcher` no longer `System.exit(1)`), **B** in its *batched* form — each executor thread
> stamps a local `LeaseRegistry`, one `LeaseHeartbeat` per dispatcher sends
> `PUT /api/v1/dispatcher/{id}/heartbeat {ids}` every `flow.dispatcher.lease.beat-ms=30000`, the
> engine `renewLeases` (one `updateMulti`, fenced on `claim.by`) into the pre-existing
> `claim.leaseExpiresAt` with `flow.dispatcher.lease-ms=90000`, and the eleventh sweep
> `reapExpiredLeases` requeues or abandons with `statusReason=LeaseExpired` — plus the `QueueService`
> fix (`DispatchError`) and the typed `statusReason` (§16 items 1–2). The per-task single-id PUT was
> rejected: at 200 in-flight tasks it is 6.7 req/s per dispatcher; the batch is 1 request per 30 s
> regardless of N. Commits `9fae2cc54`, `f9243fb6b`, `24b6b9f1c`.

**Where Flow is differentiated, stated once.** Durable DAG with joins and typed per-task
timeouts (vs code-first replay: Temporal, Trigger.dev, Vercel); Apache-2.0 with RBAC, audit,
Kubernetes execution and workspaces in the OSS product (vs Kestra EE, n8n `.ee.`); container per
task with no in-engine code execution (vs n8n's and Langflow's exploited in-process runtimes); and
the planned propose/dispose gate at task granularity (vs agents that execute — Kestra `AIAgent`
with `KestraFlow` tools).

## 16. Cross-cutting action list (Evolving)

Ranked by value ÷ cost. Gates: G1 = touches `DAGUtility`/`TaskExecutionService`; G2 = data
model. Items from §2 (CE-*), §6 (AW-*) and §9–§13 are merged here; the per-product tables hold the
rationale.

| Rank | Item | Gate | Size |
| ---- | ---- | ---- | ---- |
| 1 | CE-1 typed `statusReason` + `exitCode` (named to pair with `status`/`statusMessage`; M4's ruled `failureClass` becomes a derivation of it, not a second wire field) on task end; fix `QueueService.java:107-109` (§4) | G2 | small dispatcher + lib-common PR |
| 2 | CE-2 + VW-1 `FLOW_ATTEMPT`, `FLOW_MAX_ATTEMPTS`, `FLOW_TASKRUN_ID` env | — | trivial, same PR as 1 |
| 3 | CE-4 `dispatcher.tasks.maxInFlight` poll gate | — | small |
| 4 | Doc fixes: `agent.tasks.runtimeClassName` → `dispatcher.tasks.runtimeClassName` (§4); AW-7 invariant + close the `taskVersion == null` float at revision save; quota comparison `>` → `>=` at `WorkflowService.java:913` | — | docs + one-liners |
| 5 | TM-1 + KS-2 queue-wait deadline (`scheduleToStartAt`, reason `NoDispatcher`) and the executor's `waitUntilRunning` split | G2 | small engine + dispatcher |
| 6 | TM-3 + N8-1 + KS-4 schedule `overlap` and `misfire` enums with grace | G2 | schedules slice |
| 7 | CE-3 `resources` on `TaskSpec` with defaults + clamp; TD-4 OOM-upsize as its first retry candidate | G2 | own slice |
| 8 | KS-3 pause/approval timeout behaviour; TD-3 `eventwait` own timeout | — | small |
| 9 | KS-5 `runworkflow` `wait` / `transmitFailed` join | G1 | engine slice |
| 10 | AW-1..4 + VW-3 + N8-3 as the design inputs for the propose/dispose node (`repo-insights-engagement-inputs.md` §5) — design, not build | G1 + G2 | design record |
| 11 | TD-2 opt-in `idempotencyKey` on submit with scope + TTL | G2 | small; needs a ruling that it does not re-open the dedup decision |
| 12 | N8-2 retention policy with `retain` pin — when retention is ruled | G2 | policy first |
| 13 | KS-1 `onLostDispatcher` policy per template — the lease reap now exists (§15, shipped), so this is the remaining half | G2 | ruling first |
| 14 | TM-2 / TD-1 priority + fairness key; AW-5 `network.allowed`; AW-6 justified opt-outs; VW-2 per-run encryption; CE fleets warm-worker reference | — | deferred, designed |

Not adopted, with the reason recorded: default retry of failed instances (§3), indexed jobs (§3),
Markdown-as-workflow authoring (§7), model-based threat detection as the primary gate (§7),
event-sourced replay (§9–§11), process checkpointing (§10), push-to-queue and
latest-revision-in-flight (§12), whole-workflow-per-worker and Redis leases (§13), in-process LLM
frameworks (§14).

---

## 17. Execution-tier references — GitHub runners + ARC, Google Cloud, Tekton (Stable)

Added 2026-09-01 for the dispatcher-paradigm question (§18). These are not competitors for the
product; they are the three best-documented answers to "how does a pull-based execution tier
register, route, stay alive and scale".

### 17.1 GitHub Actions self-hosted runners + actions-runner-controller

| Concern | GitHub | Flow |
| ------- | ------ | ---- |
| Identity | Registration token (1 h) or **JIT config** — `name`, `runner_group_id`, `labels` baked in, "perform at most one job before being automatically removed" | `POST /api/v1/dispatcher/register` with `name`, `host`, `taskTypes`; bearer `bfd` token |
| Routing | `runs-on` labels are cumulative ("must have all four labels"); `runs-on: { group, labels }`; labels are **not validated** — a typo waits forever ("Waiting for a runner") | Typed `taskTypes` filter only; no labels, no groups |
| Assignment | Service picks "an online and idle runner"; "If the runner doesn't pick up the assigned job within 60 seconds, the job is re-queued"; no priority | Claim-then-CAS per task; FIFO |
| Protocol | Runner-initiated long poll ("up to 50 seconds"), then `acquirejob`; **"must start a background task to renew the job lock. This runs every minute"**, lock valid ~10 min; a runner that stops renewing is abandoned ("lost communication with the server") | 5 s poll into a 30 s engine long-poll; batched lease heartbeat every 30 s, lease 90 s (§15, shipped) |
| Limits | `timeout-minutes` 360 default, 5 days max self-hosted; queue 24 h; no automatic retry | Per-task/run typed timeouts; timeout requeue ≤ 3 |
| ARC | `AutoscalingListener` long-polls for "Job Available", scales `EphemeralRunnerSet` between `minRunners`/`maxRunners`; one pod per job; JIT config so no PAT reaches pods; `containerMode: kubernetes` runs the job as a pod via hooks. Legacy `RunnerDeployment`+`HorizontalRunnerAutoscaler` (metrics or webhooks) is "maintained by the community only" | No autoscaling; `maxInFlight` proposed (CE-4) |
| Governance | Runner **groups** are the boundary (repo/workflow allowlists); labels are routing only; "Self-hosted runners should almost never be used for public repositories" (PyTorch runner-on-runner incident, 2023) | Workspace scoping on the engine side; dispatcher trusts the engine |
| Incidents | #4309 ephemeral runners flagged "lost communication" after success (5–10 %); #3446 listener acquires jobs even at `maxRunners: 0`; multi-label scale sets only since ARC 0.14.0 (2026-03) | — |

**Transfer**: JIT single-use registration (name + group + labels, 1 h token) → the `bfd` token
already exists; make registration carry `labels`. Lock renewal separate from job timeout → shipped
as the batched heartbeat. Ephemeral one-job worker + "not picked up in 60 s → re-queue" → the
`scheduleToStartAt` deadline (TM-1). **Do not transfer** label-only routing (unvalidated labels,
name-as-label) — keep `taskTypes` primary and validate labels at registration; nor a single listener
that acquires before checking capacity.

### 17.2 Google Cloud Run Jobs, Cloud Batch, Cloud Tasks, Workflows

| Concern | Google | Flow |
| ------- | ------ | ---- |
| Cloud Run Jobs | Job → execution → up to 10,000 tasks, `--parallelism`; `CLOUD_RUN_TASK_INDEX/ATTEMPT/COUNT`; `--task-timeout` default 10 min, max 7 days (GA 2025-11), applies per attempt; `--max-retries` default 3 (0–10), per task; non-zero exit = failure; `executionReason` enum incl. `NON_ZERO_EXIT_CODE`, `CANCELLED`, `DELAYED_START_PENDING`; 1,000 executions retained per job | Attempt env (CE-2) pending; `statusReason` shipped; no run retention |
| Cloud Batch | `taskCount`, `parallelism`, `maxRetryCount` 0–10; **`lifecyclePolicies[]{action: RETRY_TASK \| FAIL_TASK, actionCondition.exitCodes[]}`** with reserved infra codes 50001 (Spot preemption), 50002 (VM reporting timeout), 50003 (reboot), 50005 (`maxRunDuration`), 50006 (VM recreated); task states `PENDING, ASSIGNED, RUNNING, FAILED, SUCCEEDED, UNEXECUTED`; `statusEvents[]{type, description, eventTime, taskExecution.exitCode, taskState}`; `priority` 0–99; 2 days max `QUEUED` then auto-fail; jobs deleted 60 days after finish | Failures terminal; reasons typed since this slice; no events history |
| Cloud Tasks | `rateLimits` (`maxDispatchesPerSecond` 500, `maxConcurrentDispatches` 1000) **separate from** `retryConfig` (`maxAttempts` 100, `minBackoff` 0.1 s, `maxBackoff` 3600 s, `maxDoublings` 16); named-task dedup tombstone 24 h ("significantly increased latency"); no ordering; at-least-once; pull queues dropped | `Backoff` 10 s → 5 m; dedup dropped |
| Workflows | `retry` predicates (`http.default_retry` = 429/502/503/504, 5 attempts), `try/except`, callbacks (`events.await_callback`, 12 h default), `parallel` with `concurrency_limit`, 1-year executions | `eventwait` + `/callback`; DAG-level parallelism |

**Transfer**: exit-code → action policy on top of `statusReason` (retry only infra classes) — the
opt-in `retryOn` foreshadowed in §3; typed `statusEvents[]` as an append-only attempt history
(pairs with Tekton's `retriesStatus`, §17.3). **Do not transfer** numeric priority + queue
auto-fail (no priority column, starvation vector) or task-name tombstone dedup.

### 17.3 Tekton Pipelines (2025–2026 re-read for the paradigm question)

| Concern | Tekton | Flow |
| ------- | ------ | ---- |
| Liveness | **No worker.** The controller reconciles CRs via informers; pod death read from `pod.Status.Phase == Failed`, step `Terminated.ExitCode != 0`, `isOOMKilled`, `pod.Status.Reason == evicted`. Active/active replicas with `buckets` (max 10), `leaseDuration 15s` | Dispatcher watch + reconcile loop (shipped) |
| Typed reasons | `TaskRunTimeout`, `TaskRunCancelled`, `TaskRunImagePullFailed`, `PodCreationFailed`, `TaskRunResultLargerThanAllowedLimit`, `PodEvicted`, `StepOOM`, `StepFailed`, `SidecarOOM`, `InitContainerFailed`, `ToBeRetried`, `FailureIgnored`, … | `statusReason` closed set (shipped) — same shape |
| Retry history | `retriesStatus []TaskRunStatus` "contains the history of TaskRunStatus in case of a retry"; a retry unsets `StartTime`, `PodName`, `Results` and creates a fresh pod; `enable-wait-exponential-backoff` default false | `retry.count` only; the record is overwritten |
| Duplicate pods | Deterministic child names `<taskrun>-pod`, `<taskrun>-pod-retry<N>` (v0.30, "to prevent duplicate resource creation … due to stale informer cache") + owner-reference check | Adopt-existing-Job by label (shipped) — same intent, label instead of name |
| Affinity | **Affinity Assistant** co-schedules TaskRuns sharing an RWO PVC on one node (`coschedule: workspaces` default); "not recommended for clusters larger than several hundred nodes", incompatible with custom `affinity`, autoscaler deadlock risk (#6543) | Run-scoped PVC per cluster; no affinity (§18 item 2) |
| Timeouts | TaskRun `timeout` default 1 h, `0` = none; `timeouts.pipeline ≥ tasks + finally`; on timeout the pod is deleted ("logs … are not preserved"); `default-imagepullbackoff-timeout` | Typed per-task/run |
| Results | 4096-byte termination message; `results-from: sidecar-logs` (beta v0.61); compression (v1.13, "5.7× more results"); artifacts (TEP-0147, alpha) | Termination message, 4 KB cap |
| Retention / provenance | Tekton Pruner (`ttlSecondsAfterFinished`, `historyLimit`, `successfulHistoryLimit`, `failedHistoryLimit`); Tekton Results (retention agent, logs to blob); **Tekton Chains** snapshots the completed run, signs it (`x509`/`kms`), stores in-toto/SLSA attestations | No retention; custody ledger proposed (`repo-insights-engagement-inputs.md` §2) |
| Status | 1.0 on 2025-05-23; CNCF incubating 2026-03; LTS quarterly (v1.15 2026-07-31); defaults `threads-per-controller: 2`, `kube-api-qps: 5` ("relatively small-scale out-of-the-box") | Executor behind the SPI |

**Transfer**: the reason vocabulary (done); `retriesStatus`-style archived attempts rather than
overwriting (with Google's `statusEvents[]`, one design); Pruner semantics for the retention
ruling (N8-2); Chains' "snapshot, sign, store elsewhere" for the custody ledger. **Do not
transfer**: the Affinity Assistant (a workaround for CRD statelessness with documented scaling
costs — pass-by-reference artefacts remove the need), and CR-as-database reconciliation (Flow's
`findAndModify` claims fence more strongly than an informer cache).

## 18. Dispatcher paradigm — simplify and strengthen (Evolving; proposed 2026-09-01)

**Position: keep the dispatcher a separate deployable, and make it a capability-registered
runner.** Every execution tier studied — GitHub runners, ARC, Trigger.dev supervisors, Kestra
workers, Temporal workers, Code Engine fleets — is pull-based and separate from its control plane;
none pushes. What they all have that Flow's dispatcher lacks is *routing*, *affinity* and
*liveness*. Liveness shipped today (§15); the rest is below.

**What is validated and what is not**

| Filter | State | Evidence |
| ------ | ----- | -------- |
| By task type | Validated — registration carries `taskTypes`, the claim query filters on them | `DispatcherService.java:186-190, 206-207`; `TaskRunService.findClaimable:75-94` |
| By label / zone / cluster | **Absent** — every dispatcher of a type competes for every task | `findClaimable` has no label criterion |
| Run → dispatcher affinity | **Absent, and a latent multi-cluster bug**: the run-scoped `workflowrun` PVC is created in whichever cluster claimed `workflowrun/start`, but each TaskRun is claimed independently, so a dispatcher in another cluster can claim a task whose workspace it cannot mount | `WorkspaceService.java:44-102`; `findClaimable` |

Many replicas in **one** cluster: safe by design. Dispatchers across clusters or zones: **not safe
today**.

| # | Change | Grounded in | Gate |
| - | ------ | ----------- | ---- |
| 1 | **Capability routing** — registration carries `labels` (`cluster`, `zone`, `arch`, `gpu`, `runtimeClass`, …); a task template / workflow task declares `requires`; the claim query matches subset. `taskTypes` stays the primary, typed filter (GitHub's lesson: unvalidated label-only routing strands jobs); labels are validated against a registered set. This implements "zone queues" (`repo-insights-engagement-inputs.md` §1) as one query criterion, not separate queues. | GitHub `runs-on` + groups; Kestra worker groups; Trigger.dev `concurrencyKey` | G2: `labels` on `DispatcherEntity`, `requires` on `TaskRunEntity`, compound index with `type`/`creationDate` |
| 2 | **Run affinity as an automatic requirement** — at `workflowrun/start` the engine stamps `requires.cluster = <claimant's cluster label>` onto that run's TaskRuns that mount a run-scoped workspace. No new mechanism; item 1 applied by the engine. Preferred over Tekton's Affinity Assistant, whose costs are documented (§17.3). | Tekton affinity (as the anti-pattern); fleets' per-VM task locality | Rides on 1 |
| 3 | **One poll, not two** — collapse `/{id}/workflows` and `/{id}/tasks` into `/{id}/work` returning both; the heartbeat stays a separate batched `PUT`. | GitHub: one session, one message loop | Protocol only |
| 4 | **Embedded dispatcher mode** — `flow.dispatcher.embedded=true` runs the same `TaskExecutor` SPI inside `service-core`, calling `DispatcherService` as Java. The compose stack has no dispatcher, so template tasks cannot execute locally at all today. The separate deployable stays the production shape. | AM-8 already anticipates the fold-in | Design record; lib-common consequence per AM-8 |
| 5 | **Autoscaling from queue depth** — with `maxInFlight` (CE-4), the engine exposes a claimable-depth gauge per label set; an HPA (or an ARC-style listener) scales dispatcher replicas; ephemeral "one task then exit" is a dispatcher flag. | ARC listener + `minRunners`/`maxRunners` | Small, after 1 and CE-4 |
| 6 | **Attempt history** — `attempts[]{seq, claimBy, startedAt, endedAt, statusReason, exitCode}` on `TaskRunEntity` instead of overwriting `statusMessage` on each requeue. | Tekton `retriesStatus`; Batch `statusEvents[]` | G2 |
| 7 | **Exit-code → action** — opt-in per template: `retryOn: [OOMKilled, ImagePull, DispatcherGone, LeaseExpired]`; everything else stays terminal (the 2026-08-18 ruling unchanged). | Batch `lifecyclePolicies`; Temporal `nonRetryableErrorTypes` | G1 (touches the reaper's requeue rule), G2 |

Not proposed: push delivery (no peer does it; it re-introduces a thread-bound agent count), a
broker (ruled, `multi-instance-model.md`), process checkpointing (§10), numeric priority queues
(§17.2).

**Order**: 1 + 2 together (one G2 review; the multi-cluster bug is the driver), then 3, 5, 6; 4
when the local-runtime work in Phase 4 resumes; 7 only on evidence of infra-class failures in
practice.

## 19. Sources

Code Engine: [job runs](https://github.com/ibm-cloud-docs/codeengine/blob/master/job-run.md) ·
[daemon mode](https://github.com/ibm-cloud-docs/codeengine/blob/master/job-daemon.md) ·
[limits](https://github.com/ibm-cloud-docs/codeengine/blob/master/limits.md) ·
[parallel job runs](https://github.com/ibm-cloud-docs/codeengine/blob/master/job-run-parallel.md) ·
[auto-injected env vars](https://github.com/ibm-cloud-docs/codeengine/blob/master/envvar-autoinject.md) ·
[app scaling](https://github.com/ibm-cloud-docs/codeengine/blob/master/app-scale.md) ·
[workload planning](https://github.com/ibm-cloud-docs/codeengine/blob/master/plan-codeengine.md) ·
[fleets](https://github.com/ibm-cloud-docs/codeengine/blob/master/fleets-workloads.md) ·
[fleet status](https://github.com/ibm-cloud-docs/codeengine/blob/master/fleet-status.md) ·
[fleets Docling tutorial](https://github.com/IBM/CodeEngine/tree/main/serverless-fleets/tutorials/docling) ·
[Kafka subscriptions](https://github.com/ibm-cloud-docs/codeengine/blob/master/subscription-kafka.md) ·
[COS subscriptions](https://github.com/ibm-cloud-docs/codeengine/blob/master/subscription-cos.md) ·
[trusted profiles](https://github.com/ibm-cloud-docs/codeengine/blob/master/trusted-profiles-authenticate-file.md) ·
[Go SDK `JobRunStatus` / `IndexDetails`](https://github.com/IBM/code-engine-go-sdk/blob/main/codeenginev2/code_engine_v2.go) ·
[Terraform `ibm_code_engine_job`](https://github.com/IBM-Cloud/terraform-provider-ibm/blob/master/website/docs/r/code_engine_job.html.markdown) ·
[Docling Serve + watsonx Orchestrate tutorial](https://developer.ibm.com/tutorials/document-processing-docling-serve-watsonx-orchestrate/).

`gh-aw`: [site](https://github.github.com/gh-aw/) · [architecture](https://github.github.com/gh-aw/introduction/architecture/) ·
[frontmatter](https://github.github.com/gh-aw/reference/frontmatter/) · [safe outputs](https://github.github.com/gh-aw/reference/safe-outputs/) ·
[integrity](https://github.github.com/gh-aw/reference/integrity/) · [threat detection](https://github.github.com/gh-aw/reference/threat-detection/) ·
[network](https://github.github.com/gh-aw/reference/network/) · [sandbox](https://github.github.com/gh-aw/reference/sandbox/) ·
[engines](https://github.github.com/gh-aw/reference/engines/) · [tools](https://github.github.com/gh-aw/reference/tools/) ·
[MCP servers](https://github.github.com/gh-aw/guides/mcps/) · [triggers](https://github.github.com/gh-aw/reference/triggers/) ·
[concurrency](https://github.github.com/gh-aw/reference/concurrency/) · [cache memory](https://github.github.com/gh-aw/reference/cache-memory/) ·
[cost management](https://github.github.com/gh-aw/reference/cost-management/) · [outcomes](https://github.github.com/gh-aw/reference/outcomes/) ·
[using at scale](https://github.github.com/gh-aw/guides/using-at-scale/) · [OrchestratorOps](https://github.github.com/gh-aw/patterns/orchestrator-ops/) ·
[repo](https://github.com/github/gh-aw) · [GHSA-8h78-hpm7-29gg](https://github.com/github/gh-aw/security/advisories/GHSA-8h78-hpm7-29gg) ·
[technical preview changelog](https://github.blog/changelog/2026-02-13-github-agentic-workflows-are-now-in-technical-preview/) ·
[AWI study, arXiv 2605.07135](https://arxiv.org/html/2605.07135v1) · [review-gate critique](https://tenki.cloud/blog/github-agentic-workflows-review-gate).

Temporal: [retry policies](https://docs.temporal.io/encyclopedia/retry-policies) · [activity failures](https://docs.temporal.io/encyclopedia/detecting-activity-failures) · [workflow failures](https://docs.temporal.io/encyclopedia/detecting-workflow-failures) · [limits](https://docs.temporal.io/workflow-execution/limits) · [Cloud pricing](https://docs.temporal.io/cloud/pricing) · [Actions](https://docs.temporal.io/cloud/actions) · [pricing update](https://temporal.io/blog/temporal-cloud-pricing-update) · [schedules](https://docs.temporal.io/schedule) · [priority & fairness](https://docs.temporal.io/develop/task-queue-priority-fairness) · [worker tuning](https://docs.temporal.io/develop/worker-tuning-reference) · [sticky execution](https://docs.temporal.io/sticky-execution) · [activity operations (pause)](https://docs.temporal.io/activity-operations) · [worker versioning](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning) · [Nexus](https://docs.temporal.io/nexus) · [message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing) · [data encryption](https://docs.temporal.io/production-deployment/data-encryption) · [Cloud audit logging](https://docs.temporal.io/cloud/audit-logging) · [AI / OpenAI Agents SDK](https://docs.temporal.io/ai) · [repo](https://github.com/temporalio/temporal) · [HN 2020](https://news.ycombinator.com/item?id=24815640).

Trigger.dev: [repo](https://github.com/triggerdotdev/trigger.dev) · [how it works](https://trigger.dev/docs/how-it-works) · [Run Engine 2](https://trigger.dev/launchweek/0/run-engine-2-alpha) · [v4 GA](https://trigger.dev/launchweek/0/trigger-v4-ga) · [machines](https://trigger.dev/docs/machines) · [queue & concurrency](https://trigger.dev/docs/queue-concurrency) · [errors & retrying](https://trigger.dev/docs/errors-retrying) · [max duration](https://trigger.dev/docs/runs/max-duration) · [wait](https://trigger.dev/docs/wait) · [wait for token](https://trigger.dev/docs/wait-for-token) · [idempotency](https://trigger.dev/docs/idempotency) · [runs & statuses](https://trigger.dev/docs/runs) · [versioning](https://trigger.dev/docs/versioning) · [scheduled tasks](https://trigger.dev/docs/tasks/scheduled) · [self-hosting](https://trigger.dev/docs/self-hosting/overview) · [supervisor env](https://trigger.dev/docs/self-hosting/env/supervisor) · [pricing](https://trigger.dev/pricing) · [incident 2025-09-26](https://trigger.dev/blog/incident-report-sep-26-2025) · [CRIU checkpoints](https://indepth.dev/posts/1020/en/how-trigger-dev-checkpoints-containers) · [HN 2025](https://news.ycombinator.com/item?id=45250720).

Vercel Workflows: [repo](https://github.com/vercel/workflow) · [docs](https://vercel.com/docs/workflows) · [pricing](https://vercel.com/docs/workflows/pricing) · [introducing Workflow](https://vercel.com/blog/introducing-workflow) · [programming model](https://vercel.com/blog/a-new-programming-model-for-durable-execution) · [event sourcing](https://workflow-sdk.dev/docs/how-it-works/event-sourcing) · [errors & retries](https://workflow-sdk.dev/docs/foundations/errors-and-retries) · [hooks](https://workflow-sdk.dev/docs/foundations/hooks) · [idempotency](https://workflow-sdk.dev/docs/foundations/idempotency) · [versioning](https://workflow-sdk.dev/docs/foundations/versioning) · [Postgres World](https://workflow-sdk.dev/docs/deploying/world/postgres-world) · [vs Inngest](https://workflow-sdk.dev/docs/comparisons/workflow-sdk-vs-inngest) · [encryption changelog](https://vercel.com/changelog/workflow-encryption) · [AI](https://workflow-sdk.dev/docs/ai) · [issue #2376](https://github.com/vercel/workflow/issues/2376) · [Inngest critique](https://www.inngest.com/blog/explicit-apis-vs-magic-directives).

Kestra: [repo](https://github.com/kestra-io/kestra) · [pricing / EE matrix](https://kestra.io/pricing) · [architecture](https://kestra.io/docs/architecture) · [server components](https://kestra.io/docs/architecture/server-components) · [runtime & storage](https://kestra.io/docs/configuration/runtime-and-storage) · [performance tuning](https://kestra.io/docs/performance/performance-tuning) · [server lifecycle / restart strategy](https://kestra.io/docs/administrator-guide/server-lifecycle) · [concurrency](https://kestra.io/docs/workflow-components/concurrency) · [retries](https://kestra.io/docs/workflow-components/retries) · [errors](https://kestra.io/docs/workflow-components/errors) · [finally](https://kestra.io/docs/workflow-components/finally) · [states](https://kestra.io/docs/workflow-components/states) · [replay](https://kestra.io/docs/concepts/replay) · [Pause](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.pause) · [Subflow](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.subflow) · [Schedule](https://kestra.io/plugins/core/trigger/io.kestra.plugin.core.trigger.schedule) · [task runner types](https://kestra.io/docs/task-runners/types) · [Kubernetes runner](https://kestra.io/docs/task-runners/types/kubernetes-task-runner) · [AIAgent](https://kestra.io/plugins/plugin-ai/agent/io.kestra.plugin.ai.agent.aiagent) · [2.0 engineering](https://kestra.io/blogs/kestra-2-0-engineering) · issues [#2600](https://github.com/kestra-io/kestra/issues/2600) · [#4631](https://github.com/kestra-io/kestra/issues/4631) · [#9094](https://github.com/kestra-io/kestra/issues/9094).

n8n: [Sustainable Use License](https://docs.n8n.io/privacy-and-security/sustainable-use-license) · [community edition features](https://docs.n8n.io/deploy/host-n8n/community-edition-features.md) · [queue mode](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/enable-queue-mode.md) · [concurrency](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/control-concurrency.md) · [executions env](https://docs.n8n.io/deploy/host-n8n/configure-n8n/basic-configuration/use-environment-variables/executions.md) · [task runners](https://docs.n8n.io/deploy/host-n8n/configure-n8n/set-up-task-runners.md) · [harden task runners](https://docs.n8n.io/deploy/host-n8n/configure-n8n/security/harden-task-runners.md) · [durable scheduler](https://docs.n8n.io/deploy/host-n8n/configure-n8n/durable-scheduler) · [execution data](https://docs.n8n.io/deploy/host-n8n/configure-n8n/scaling/manage-execution-data) · [workflow history](https://docs.n8n.io/build/manage-workflows/view-change-history.md) · [Wait node](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.wait/) · [AI Agent](https://docs.n8n.io/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.agent) · [Guardrails](https://docs.n8n.io/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.guardrails.md) · [2.0 breaking changes](https://docs.n8n.io/changelog/v20-breaking-changes) · [scaling.service.ts](https://github.com/n8n-io/n8n/blob/master/packages/cli/src/scaling/scaling.service.ts) · [Rapid7 on Ni8mare/N8scape](https://www.rapid7.com/blog/post/etr-ni8mare-n8scape-flaws-multiple-critical-vulnerabilities-affecting-n8n/) · [VulnCheck](https://www.vulncheck.com/blog/n8n-needs-more-kev) · [per-workflow concurrency request](https://community.n8n.io/t/add-concurrency-in-workflow-settings/44103).

Langflow: [repo](https://github.com/langflow-ai/langflow) · [IBM–DataStax](https://newsroom.ibm.com/2025-02-25-ibm-to-acquire-datastax,-deepening-watsonx-capabilities-and-addressing-generative-ai-data-needs-for-the-enterprise) · [flows](https://docs.langflow.org/concepts-flows) · [run API](https://docs.langflow.org/api-flows-run) · [webhook](https://docs.langflow.org/webhook) · [MCP server](https://docs.langflow.org/mcp-server) · [env vars](https://docs.langflow.org/environment-variables) · [API keys & auth](https://docs.langflow.org/api-keys-and-authentication) · [RBAC request #1864](https://github.com/langflow-ai/langflow/issues/1864) · [CVE-2025-3248](https://www.offsec.com/blog/cve-2025-3248/) · [JFrog on CVE-2026-33017](https://research.jfrog.com/post/langflow-latest-version-was-not-fixed/) · [CVE-2026-5027](https://orca.security/resources/blog/cve-2026-5027-langflow-path-traversal-rce/) · [CVE-2026-55255](https://www.sysdig.com/blog/understanding-langflow-cve-2026-55255-and-why-higher-cvss-vulnerabilities-arent-always-the-most-exploited).

Execution-tier references (§17): [self-hosted runners](https://docs.github.com/en/actions/reference/runners/self-hosted-runners) · [runs-on / labels](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/use-in-a-workflow) · [runner groups](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/manage-access) · [runner communication](https://docs.github.com/en/enterprise-server@3.13/actions/concepts/runners/communicating-with-self-hosted-runners) · [JIT config API](https://docs.github.com/en/rest/actions/self-hosted-runners) · [security hardening](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions) · [ARC concepts](https://docs.github.com/en/actions/concepts/runners/actions-runner-controller) · [ARC scale sets README](https://github.com/actions/actions-runner-controller/blob/master/docs/gha-runner-scale-set-controller/README.md) · [ARC 0.14.0](https://github.blog/changelog/2026-03-19-actions-runner-controller-release-0-14-0/) · [runner listener teardown](https://depot.dev/blog/github-actions-runner-architecture-part-1-the-listener) · issues [#3446](https://github.com/actions/actions-runner-controller/issues/3446), [#4309](https://github.com/actions/runner/issues/4309), [runner #1546](https://github.com/actions/runner/issues/1546) · [PyTorch runner-on-runner](https://devclass.com/2024/01/15/spotlight-on-github-self-hosted-runners-again-as-researcher-demonstrates-attack-on-pytorch-code/) · [Cloud Run Jobs](https://docs.cloud.google.com/run/docs/create-jobs) · [task timeout](https://docs.cloud.google.com/run/docs/configuring/task-timeout) · [max retries](https://docs.cloud.google.com/run/docs/configuring/max-retries) · [Execution API](https://docs.cloud.google.com/run/docs/reference/rest/v2/projects.locations.jobs.executions) · [Batch jobs API](https://docs.cloud.google.com/batch/docs/reference/rest/v1/projects.locations.jobs) · [Batch retries / lifecycle policies](https://docs.cloud.google.com/batch/docs/automate-task-retries) · [Batch StatusEvent](https://docs.cloud.google.com/batch/docs/reference/rest/v1/StatusEvent) · [Batch quotas](https://docs.cloud.google.com/batch/quotas) · [Cloud Tasks queues](https://docs.cloud.google.com/tasks/docs/reference/rest/v2/projects.locations.queues) · [Cloud Tasks pitfalls](https://docs.cloud.google.com/tasks/docs/common-pitfalls) · [Workflows retry](https://docs.cloud.google.com/workflows/docs/reference/syntax/retrying) · [Workflows callbacks](https://docs.cloud.google.com/workflows/docs/creating-callback-endpoints) · [Tekton TaskRuns](https://tekton.dev/docs/pipelines/taskruns/) · [`taskrun_types.go` reasons](https://raw.githubusercontent.com/tektoncd/pipeline/main/pkg/apis/pipeline/v1/taskrun_types.go) · [`pkg/pod/status.go`](https://raw.githubusercontent.com/tektoncd/pipeline/main/pkg/pod/status.go) · [HA](https://tekton.dev/docs/pipelines/enabling-ha/) · [affinity assistants](https://tekton.dev/docs/pipelines/affinityassistants/) · [#6543](https://github.com/tektoncd/pipeline/issues/6543) · [deterministic pod names PR #4361](https://github.com/tektoncd/pipeline/pull/4361) · [Pruner](https://tekton.dev/blog/2026/02/05/introducing-tekton-pruner/) · [Results](https://tekton.dev/docs/results/) · [Chains](https://tekton.dev/docs/chains/) · [1.0](https://tekton.dev/blog/2025/05/23/tekton-pipelines-reaches-1.0-stability-today-innovation-tomorrow/) · [CNCF incubating](https://tekton.dev/blog/2026/03/25/tekton-joins-the-cncf-as-an-incubating-project/) · [Red Hat at scale](https://www.redhat.com/en/blog/operating-tekton-scale-10-lessons-learned).

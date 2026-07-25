# Runtime Contract — Q-401 Tekton Usage Inventory, Q-402 Task Contract, Q-403 Workspace Dependence

> **Status:** Phase 4 analysis (master spec §7 tasks 1–3; register Q-401/Q-402/Q-403 in
> `v5-enhancemnet.md`). Feeds the dispatcher SPI (DD-06: tier renamed agent→dispatcher at
> E7/E8; J8: artifacts `dispatcher-tekton` now, `dispatcher-docker` for standalone).
> Evidence base: post-PR4 `service-agent` source (fabric8 7.8, Tekton **v1** models —
> `service-agent/pom.xml:21` `tekton.version=7.8.0`), `lib-common` wire models,
> `queue-design.md` §1.7–1.8, `consolidation-proposal.md` J8/I6.
>
> All file paths relative to repo root. Line refs are against the current working tree.

---

## Part 1 — Q-401: Tekton features ACTUALLY used (by code, not docs)

The entire Tekton surface is consumed from **one class**:
`service-agent/src/main/java/io/boomerang/kube/TektonServiceImpl.java` (4 public methods:
`createTaskRun`, `watchTaskRun`, `deleteTaskRun`, `cancelTaskRun`), with side objects
(ConfigMap, PVC, pod logs) via plain Kubernetes APIs in `KubeServiceImpl` /
`KubeLogService`. There is **no Task/Pipeline CRD usage anywhere** — every execution is a
standalone `TaskRun` with an **embedded (inline) `taskSpec`**.

### 1.1 TaskRun spec construction (`TektonServiceImpl.createTaskRun`, L114–480)

| Feature | Used how | Evidence |
|---|---|---|
| **metadata.generateName** | `{flow.product}-task-{taskRunId}-` — cluster appends a **random suffix**; external name is NOT deterministic (see Q-406, §2.4) | `TektonServiceImpl.java:441`, prefix `KubeHelperService.java:48-50` |
| **metadata.labels** | Correlation is 100% label-based: `app.kubernetes.io/*` + `boomerang.io/product`, `tier=task`, `workflow-ref`, `workflowrun-ref`, `taskrun-ref` + custom labels from the TaskRun | `TektonServiceImpl.java:442-444`; label builder `KubeHelperService.java:238-270` |
| **metadata.annotations** | `boomerang.io/workflow-ref` + a `boomerang.io/selector` string (self-describing label selector) | `TektonServiceImpl.java:445-447`; `KubeHelperService.java:293-300` |
| **spec.podTemplate.nodeSelector** | From config `agent.tasks.nodeselector` (SpEL map) — deploy-time, not per-task | `TektonServiceImpl.java:102-103,295-302,452` |
| **spec.podTemplate.tolerations** | From config `agent.tasks.tolerations` (Gson-parsed JSON) — deploy-time | `TektonServiceImpl.java:105-106,304-316,453` |
| **spec.podTemplate.imagePullSecrets** | Single secret ref from config `kube.image.pullSecret` (default `boomerang.registrykey`) | `TektonServiceImpl.java:69-70,330-333,454` |
| **spec.podTemplate.hostAliases** | From config `agent.tasks.hostaliases` (Gson-parsed JSON) — deploy-time | `TektonServiceImpl.java:99-100,321-325,455` |
| **spec.params + taskSpec.params** | Every `RunParam` becomes a TaskRun `Param` AND a `ParamSpec` on the inline taskSpec — **all typed `"string"`**, value cast `(String) p.getValue()` (object/array params would ClassCastException) | `TektonServiceImpl.java:361-375,457,463` |
| **taskSpec.steps** | Exactly **ONE step named `task`**: image, `script` XOR `command` (script wins if non-empty), `args`, `imagePullPolicy` (config), `env`, `volumeMounts`, `workingDir` | `TektonServiceImpl.java:382-398` |
| **step.env** | Proxy env vars (if `proxy.enable`) + `DEBUG` + `CI=true` + task-defined `TaskEnvVar` list. The BMRG_* system vars are commented out (deprecated) | `TektonServiceImpl.java:342-354`; `KubeHelperService.java:68-102`; deprecated block `TektonServiceImpl.java:344-346` |
| **taskSpec.workspaces + spec.workspaces** | Types `workflow`/`workflowrun` only → `WorkspaceDeclaration` (mountPath default `/workspace/{type}`, `optional` honoured, description set) + `WorkspaceBinding` → **PVC** resolved by label lookup (`getPVCName`). Any other type is **skipped with a warning** ("we don't support custom workspaces yet") | `TektonServiceImpl.java:181-229` (skip: 222-227); PVC name lookup `KubeServiceImpl.java:110-140` |
| **taskSpec.volumes + step.volumeMounts** | Two extra volumes: `/data` **emptyDir** scratch (medium=`Memory` iff config `kube.task.storage.data.memory` AND param `worker.storage.data.memory`) and `/params` **projected ConfigMap** volume (the per-task ConfigMap, resolved by label) | `/data`: `TektonServiceImpl.java:244-264`; `/params`: `TektonServiceImpl.java:269-288` |
| **taskSpec.results** | Declared from the TaskRun's `RunResult` list (name + description). Read back as strings (`getValue().getStringVal()`) | declare `TektonServiceImpl.java:416-425,464`; read `TektonServiceImpl.java:545-552` |
| **spec.timeout** | fabric8 `Duration.parse(timeout + "mins")` — minute granularity, from `TaskRun.timeout` else config `kube.task.timeout=60` | `TektonServiceImpl.java:427,459`; fallback `TaskService.java:50-52` |
| **create** | `client.v1().taskRuns().resource(taskRun).create()` — Tekton **v1** API group | `TektonServiceImpl.java:473` |

### 1.2 Watch / latch lifecycle (`watchTaskRun` L482–555 + `TaskWatcher`)

- **Blocking label-selector watch** per TaskRun: `client.v1().taskRuns().withLabels(taskLabels).watch(new TaskWatcher(latch))`; the calling `@Async` thread blocks on `CountDownLatch.await(timeout + 10, MINUTES)` (10-min grace over the Tekton timeout, for provisioning delays) — `TektonServiceImpl.java:490-517`. **One thread + one API-server watch held per in-flight task** (the saturation issue queue-design §1.8 long-poll v2 fixes on the protocol side; the SPI must fix it on the runtime side — §2.5).
- `TaskWatcher` (`service-agent/src/main/java/io/boomerang/kube/TaskWatcher.java`) inspects `status.conditions[0]` only:
  - condition `True`/`False` → capture condition + `status.results`, count down (`TaskWatcher.java:82-100`; note the missing `break`s — `DELETED`/`MODIFIED` cases fall through, `TaskWatcher.java:59-81`).
  - `DELETED` while `Unknown` → synthesizes `TaskRunCancelled` (external `kubectl delete` treated as cancellation) — `TaskWatcher.java:60-68`.
  - `MODIFIED` with `"rpc error"` in the message → forced fail (image-pull class errors) — `TaskWatcher.java:69-80`.
  - `"exited with code 1"` message → rewritten to a user-readable message — `TaskWatcher.java:85-95`.
- **Watch connection error → `System.exit(1)`** (kills the whole agent to force restart) — `TaskWatcher.java:104-109`. The SPI must not inherit this.
- Latch expiry → `BoomerangException(TASK_EXECUTION_ERROR, "TaskRunTimeout…")` — `TektonServiceImpl.java:512-517`.
- **Result size limit is detected reactively**: on failure, tail 1 line of the pod log for `"Termination message is above max allowed size 4096"` → distinct error "TaskRunResultTooLarge … 4096 byte size for Result Parameters" — `KubeServiceImpl.java:431-455`, `TektonServiceImpl.java:527-532`. This is Tekton's termination-message channel cap (~4KB per step for ALL results combined), and it is the **contractual result-size ceiling today**.

### 1.3 Log retrieval

- `KubeLogService.getPodLog` (L32–60): list pods by task labels, take **first**, `getLog()` (whole log, pretty).
- `KubeLogService.streamPodLog` (L62–95): `watchLog().getOutput()` → `StreamingResponseBody` copy loop (L97–120). Streaming works only while the pod exists (deletion policy interacts here).
- Alternative backend: **Loki** query_range pagination in `LogService.streamLogsFromLoki` (`service-agent/src/main/java/io/boomerang/agent/LogService.java:68-153`), filter `{bmrg_task_activity, bmrg_workflow, bmrg_container="step-task"}` (L155–161) — note the label `step-task` hard-codes Tekton's step-container naming. `elastic` type is deprecated (L57–58). Exposed via `LogV1Controller` `/api/v1/logs[/stream]`.

### 1.4 Cancellation mechanics (`cancelTaskRun` L585–622)

List TaskRuns by labels → take first → **overwrite `status.conditions`** with a single
`Succeeded=False / TaskRunCancelled` condition → `updateStatus()` (`TektonServiceImpl.java:597-612`).
This is the **pre-0.9 legacy hack** (comment cites a 0.8-era reconciler, L576–584) — it
does **NOT** use Tekton's supported `spec.status: "TaskRunCancelled"` field. Fragile;
listed as a divergence the SPI hides. Trigger path: engine marks the run
cancelled/timedout → agent polls it in `completed` phase → `TaskService.terminate` →
`cancelTaskRun` (`QueueService.java:86-93`, `TaskService.java:54-62`).

### 1.5 Deletion / propagation

- `deleteTaskRun` L557–574: label-selector delete with `DeletionPropagation.BACKGROUND`.
- Policy-driven via `TaskDeletion` (`lib-common/.../enums/TaskDeletion.java`: `Never|OnSuccess|Always`, config default `kube.task.deletion=Never`): `OnSuccess` deletes after successful watch, `Always` deletes in `finally` — `TaskService.java:105-136`; async fire-and-forget with a 1s sleep (`TaskService.java:143-155`).
- The per-task **ConfigMap is always deleted** in `finally` — `TaskService.java:131-132`.

### 1.6 Side objects (plain Kubernetes, not Tekton)

| Object | Lifecycle | Evidence |
|---|---|---|
| **ConfigMap per TaskRun** (param file delivery) | Created before the TaskRun (`TaskService.java:73-79`) with data = raw `paramName → value.toString()` map; generateName `{product}-cfg-`; task labels; mounted at `/params` via projected volume; resolved by label at build time (**first match wins**); always deleted after | create `KubeServiceImpl.java:310-347`; data `KubeHelperService.java:126-138`; name lookup `KubeServiceImpl.java:398-429` |
| **PVC per workspace** | Created at **WorkflowRun start** (`WorkflowService.execute` → `WorkspaceService.create`), generateName `{product}-pvc-`, workspace labels (`workspace-ref` = workflowId or workflowRunId by type), size/class/accessMode from `WorkflowWorkspaceSpec` with config defaults (`1Gi`/`ReadWriteMany`); `waitUntilCondition(Bound|Pending, 30s)`. Deleted (workflowRun-type only) at finalize | create `KubeServiceImpl.java:142-204`; delete `KubeServiceImpl.java:235-247`; orchestration `WorkflowService.java:36-112`, `WorkspaceService.java:46-141` |
| **Pod (read-only)** | Log retrieval + result-too-large tail | `KubeLogService.java`, `KubeServiceImpl.java:431-455` |

### 1.7 Prominent Tekton/Kube features NOT used (the SPI abstracts only what IS used)

| Not used | Evidence |
|---|---|
| **Pipelines / PipelineRuns** | Zero references; DAG orchestration is engine-side. Boomerang uses Tekton purely as a *single-pod task executor* |
| **Task/ClusterTask CRDs, taskRef** | Only inline `taskSpec` (`TektonServiceImpl.java:461-467`) |
| **Triggers / EventListeners** | Absent; dispatch is agent-poll (`EngineClient.java:156-210`, 5s heartbeat) |
| **Sidecars, multi-step tasks, stepTemplate** | Single step only (`TektonServiceImpl.java:382-398`) |
| **Retries** | TaskRun has no retry concept in v1 anyway; `kube.task.backOffLimit`/`restartPolicy`/`ttlDays` are **dead config** (declared `TektonServiceImpl.java:72-79`, `KubeServiceImpl.java:40-47`, never applied). Retry is an engine/queue concern (queue-design D1) |
| **Remote resolution (hub/bundles/git resolvers)** | Absent — image ref is the entire task packaging story |
| **Param types `array`/`object`** | Everything forced `"string"` (`TektonServiceImpl.java:367,371`) |
| **Workspace bindings other than PVC** | No emptyDir/ConfigMap/Secret/projected workspace bindings; custom workspaces explicitly skipped (`TektonServiceImpl.java:222-227`) |
| **Secrets** (beyond the imagePullSecret reference) | Sole `Secret` mention is the pull secret (`TektonServiceImpl.java:328-333`) |
| **`spec.serviceAccountName`** | Config `agent.tasks.serviceaccount` exists but the setter is **commented out** (`TektonServiceImpl.java:460`) — tasks run as namespace default SA |
| **Resource requests/limits** | Entire block commented out (`TektonServiceImpl.java:146-172,397`); `kube.resource.*` config is dead |
| **`spec.status: TaskRunCancelled`** (supported cancellation) | Replaced by the status-condition overwrite hack (§1.4) |
| **Affinity** | `KubeHelperService.getPodAffinity` (L213–229) exists but is never called from the Tekton path |
| **Results-from-sidecar-logs / larger-results feature, OCI artifact results** | Termination-message channel only (4096B, §1.2) |

**Count: 14 Tekton spec/lifecycle features used** (generateName, labels, annotations, 4×
podTemplate fields, params, single-step incl. script/command/args/env/workingDir/
imagePullPolicy, workspaces→PVC, volumes/volumeMounts, results, timeout, watch,
status-overwrite cancel, propagated delete) **+ 3 Kubernetes side-object usages**
(ConfigMap, PVC, pod logs). Everything else in Tekton is unused.

---

## Part 2 — Q-402: The task contract (what ANY runtime must provide)

Wire model the runtime consumes (`lib-common`): `TaskRun extends TaskRunEntity`
(`entity/TaskRunEntity.java:33-60` — id, type, labels, timeout(Long, minutes), params,
results, workspaces, spec, status/phase, workflowRef/workflowRunRef, `agentRef`);
`TaskRunSpec` (`model/TaskRunSpec.java:15-23` — arguments, command, envs, image, script,
workingDir, debug, timeout, deletion); `RunParam{name, Object value}`;
`RunResult{name, description, Object value}`; `TaskEnvVar{name, value}`;
`TaskWorkspace{name, type, optional, mountPath}`. Runtime-executed task types:
`template|custom|script` (`application.properties` `flow.agent.task-types`;
`QueueService.java:70-74`).

### 2.1 Contract table

| # | Contract point | Today (Tekton, as actually consumed) | Any-runtime obligation |
|---|---|---|---|
| C1 | **Execution payload** | `image` (mandatory — `TaskService.java:68-70` fails NO_TASK_IMAGE) + `script` XOR `command` (script precedence, `TektonServiceImpl.java:386-390`) + `args` + `workingDir` + `debug` flag | Run an OCI image with either an injected script (shebang-interpreted file, Tekton semantics) or command override, plus args and working dir. Script injection is contractual — the `script` task type depends on it |
| C2 | **Parameter delivery** | THREE concurrent channels: (a) Tekton params — enables `$(params.x)` interpolation inside script/args/command; (b) **files**: every param as a key in the `/params` projected ConfigMap (raw `name → value.toString()`); (c) env — ONLY explicit `TaskEnvVar`s + `DEBUG`/`CI` (+ proxy). Params are NOT auto-exported as env (the `PARAM_` prefix idea is commented out, `TektonServiceImpl.java:373-374`) | Deliver params as (a) interpolation into script/args and (b) a file per param under `/params`. Env delivery only for declared `TaskEnvVar`s. String values only (today's hard cast). **Docker divergence: interpolation must move agent-side (pre-substitute `$(params.x)` before submit)** |
| C3 | **Secrets** | **None.** Params land in a plaintext ConfigMap; only registry auth exists (imagePullSecret, deploy-scoped). No per-task secret mounts, no masked env | Contract today = registry credentials only. GAP: any future secret-param facility is net-new design (flag for Phase 4 design; the SPI should reserve a `secrets` slot on the spec but implement nothing yet) |
| C4 | **Working storage** | `/data` per-task emptyDir scratch (optionally RAM-backed); `/workspace/workflow` (cross-run, PVC keyed by workflowRef) and `/workspace/workflowrun` (per-run, PVC keyed by workflowRunId) when declared; custom mountPath honoured | Per-task scratch is unconditional. Shared workspaces are a **declared, optional capability** (`TaskWorkspace.optional` exists in the model): runtime advertises `supportsWorkspace(type)`; submit fails fast (or degrades if optional) when unsupported. See Part 3 |
| C5 | **Result reporting** | Declared result names → written by the task to Tekton's result path → returned via TaskRun status as **strings**; **hard ~4096-byte cap** (termination-message channel) detected reactively by log-tailing (`KubeServiceImpl.java:431-455`). Results ride `TaskRunEndRequest.results` to the engine (`QueueService.java:81-85`) | Small named string results (contract cap: **4KB total** — keep Tekton's floor as THE cap so tasks stay portable; large artifacts belong in workspaces/object storage). Runtime must surface "results too large" as a distinct failure class, not a generic error |
| C6 | **Log streaming** | Pull: whole-log get + live stream from the pod by labels; alt Loki backend. Served through the agent's own `/api/v1/logs` endpoint | `logs(handle, follow)` → byte stream. Log persistence after task deletion is NOT contractual today (Never-delete default masks this); flag: with `OnSuccess/Always` deletion, default-mode logs are already lossy |
| C7 | **Exit-status → RunStatus mapping** | Binary: condition `Succeeded=True` → agent reports `RunStatus.succeeded`; ANY failure/exception → `failed` with the condition reason/message as `statusMessage` (`QueueService.java:79-104`). Exit codes are NOT mapped (only string-sniffed for "exited with code 1" message cleanup); `cancelled`/`timedout` originate engine-side, never from the runtime | Runtime returns `{terminal state: SUCCEEDED\|FAILED\|CANCELLED, reason, message, results}`. Protocol v2 (queue-design D1) requires the dispatcher to map runtime knowledge (exit codes, Tekton reasons, HTTP status) to a **`failureClass`** on `TaskRunEndRequest` — the SPI status object must carry the raw evidence (exit code, reason string) to enable this |
| C8 | **Resources** | **Not applied** — requests/limits fully commented out; `kube.resource.*` dead config (§1.7) | Optional hints (`cpu/memory/ephemeral` request+limit) in the spec, best-effort per runtime. Nothing today depends on them |
| C9 | **Timeout** | Minute-granularity budget enforced **twice**: by Tekton (`spec.timeout`) and by the agent latch (budget + 10min grace). Timeout inside Tekton yields a failed condition | Runtime must enforce the budget (kill the execution) AND the dispatcher supervises with grace. Aligns with queue-design §1.7: **lease and timeout are independent guards** — `timeoutAt = budget + grace` composes downward; runtime-level timeout must be ≤ engine `timeoutAt` or healthy tasks get reaped |
| C10 | **Cancellation** | Engine flags run → agent poll → `terminate()` → status-condition overwrite hack (§1.4); external deletion detected by watch and normalized to cancelled | `cancel(handle)`: stop execution, terminal state CANCELLED, idempotent, tolerate already-gone. The Tekton hack gets replaced by `spec.status=TaskRunCancelled` behind the SPI |
| C11 | **External identity / adopt-or-supersede (L-12/Q-406)** | `generateName` + random suffix ⇒ **non-deterministic name**; correlation only via labels (`taskrun-ref` label allows *listing* predecessors but not distinguishing claim attempts) | **Deterministic external name: `{prefix}-{taskRunId}-e{claimEpoch}`** (labels additionally carry `taskrun-ref` + `claim-epoch`). A re-claiming dispatcher can then: `find(externalId(taskRunId, epoch))` — adopt its own prior submit (crash-after-submit idempotency); list by `taskrun-ref` with `claim-epoch < mine` — supersede (cancel+delete) ghosts. This is the runtime half of the Q-129 fencing design |
| C12 | **Fencing-abandon on lease rejection** | Nothing — no lease exists (Q-128: bare `agentRef`, no epoch/lease) | Per queue-design §1.7: renewal CAS rejection ⇒ *"agent halts and abandons external actions — fencing propagated to the runtime"*. SPI needs **`abandon(handle)`** distinct from `cancel`: detach supervision (stop watch/renewal, report nothing to the engine) but **leave the external object untouched** — the successor claimant decides adopt-or-supersede via C11. Abandon must never destroy work it no longer owns |
| C13 | **Run-scoped environment provisioning** | WorkflowRun-level hooks: `execute` (create workspace PVCs before first task) / `terminate` (delete workflowRun-scoped PVCs) — `WorkflowService.java:36-112`, driven off the workflow queue (`QueueService.java:42-57`) | Optional runtime capability pair `provisionRunResources` / `teardownRunResources`, invoked from the run lifecycle. No-op where workspaces are unsupported |

### 2.2 SPI sketch (dispatcher runtime interface)

```java
public interface DispatcherRuntime {
  RuntimeCapabilities capabilities();                       // workspace types, script support, log streaming, …
  RuntimeHandle submit(RuntimeTaskSpec spec);               // spec.externalId = name(taskRunId, claimEpoch)  [C1-C4, C8, C9, C11]
  Optional<RuntimeHandle> find(String externalId);          // adopt-or-supersede probe                        [C11]
  RuntimeStatus status(RuntimeHandle h);                    // non-blocking poll: RUNNING | terminal{state, reason, exitCode, message, results} [C5, C7]
  CompletionStage<RuntimeStatus> awaitCompletion(RuntimeHandle h);  // replaces the thread-per-task blocking watch [§1.2]
  InputStream logs(RuntimeHandle h, boolean follow);        // [C6]
  void cancel(RuntimeHandle h);                             // idempotent kill → CANCELLED                     [C10]
  void abandon(RuntimeHandle h);                            // fencing: detach, leave external object          [C12]
  void delete(RuntimeHandle h);                             // per TaskDeletion policy                         [§1.5]
  void provisionRunResources(RunContext ctx);               // optional (workspaces)                           [C13]
  void teardownRunResources(RunContext ctx);
}
```

`RuntimeTaskSpec` ≈ today's `createTaskRun` argument list flattened: externalId, labels,
image, command/script/args/workingDir, params (pre-resolved strings), envs, declared
results, workspaces, scratch config, timeout, debug — i.e., exactly the `TaskRunSpec` +
`TaskRun` fields already on the wire, no Tekton types leaking through.

### 2.3 Operation mapping: Tekton vs Docker

| SPI op | Tekton (`dispatcher-tekton`) | Docker (`dispatcher-docker`) | Divergence |
|---|---|---|---|
| `submit` | Build inline-taskSpec TaskRun (as §1.1) but with **deterministic `metadata.name`** (drop generateName) | `docker create/start` with `--name {externalId}`; script → write temp file, mount, set entrypoint `sh /path` (or image shebang); params → agent-side `$(params.x)` substitution + write files into a mounted temp dir at `/params`; env via `--env` | **No podTemplate**: nodeSelector/tolerations meaningless (single host); hostAliases → `--add-host` (supported); registry auth → dispatcher-level `docker login`, not per-submit secret ref. **No projected ConfigMap** → bind-mounted temp dir. **No `$(params)` interpolation** → mandatory agent-side substitution (should be done for BOTH runtimes for parity) |
| `find` | `get taskrun {externalId}` (deterministic name makes this exact) | `docker inspect {externalId}` | Docker container names are natively unique+deterministic — C11 is *easier* on Docker |
| `status` / `awaitCompletion` | Informer/watch on the single named TaskRun → condition mapping (§1.2), minus `System.exit(1)`; results from status | `docker wait` / events; **results from a file convention** (mount a results dir, task writes `/tekton/results/{name}`-style files — keep the same in-container path for task-image portability); exit code native | **Results channel differs**: Tekton = termination message (4KB cap enforced by the platform); Docker = files (cap must be **enforced by the dispatcher** to keep the contract uniform). Exit codes are first-class on Docker, string-sniffed on Tekton |
| `logs` | Pod log get/stream by name (no more first-of-labels ambiguity); Loki optional | `docker logs [-f]` | Docker is simpler; Loki filter's hard-coded `step-task` container label (§1.3) must become runtime-provided metadata |
| `cancel` | `spec.status = "TaskRunCancelled"` (replacing the status-overwrite hack §1.4) | `docker kill` (SIGTERM→SIGKILL grace) | Semantics align; Docker has real signal-grace control, Tekton does not expose it |
| `abandon` | Close watch, stop renewals; TaskRun left running (successor supersedes) | Detach from `docker wait`; container left running | Same shape both sides — this op is runtime-trivial, the discipline is in the dispatcher harness |
| `delete` | Label/name delete, `BACKGROUND` propagation | `docker rm -f` + temp-dir cleanup | Docker delete also destroys logs — with `OnSuccess/Always` deletion, C6 forces log capture BEFORE delete (already latent on Tekton, §2.1 C6) |
| `provision/teardownRunResources` | PVC create/delete (§1.6) | Named volume `docker volume create {ws-ref}` / bind mount under an agent data dir | **Biggest divergence — see Part 3.** Docker volumes are single-host: a `workflow`-scoped workspace shared across *multiple dispatcher instances* cannot exist; fine for standalone (one host by definition, J8 docker-compose quickstart) |
| timeout | `spec.timeout` + supervisor grace | No engine-side equivalent → **dispatcher-armed timer** → `cancel` at budget | Docker has no declarative timeout; enforcement is wholly dispatcher-side |

### 2.4 Q-406 hook — deterministic naming + fencing (normative for the SPI)

1. `externalId = sanitize("{prefix}-{taskRunId}-e{claimEpoch}")` — stable across dispatcher restarts within one claim, distinct across claims. (Tekton names ≤63 chars DNS-1123: prefix+25-char Mongo id+epoch fits.)
2. On claim (or re-claim after crash): `find(externalId)` → **adopt** (resume `awaitCompletion`, no duplicate side effect — fixes today's crash-after-create duplicate-execution window, since `createTaskRun`+`watchTaskRun` are not re-entrant).
3. List by `taskrun-ref` label where `claim-epoch < myEpoch` → **supersede**: `cancel` + `delete` ghosts before submitting.
4. On lease-renewal rejection (v2 `POST /claims/renew` per-claim CAS, queue-design §1.7): `abandon(handle)` for every rejected id; report **nothing** to the engine (a fenced dispatcher's `endTask` would anyway be rejected by the epoch check at the result-write, Q-129).

---

## Part 3 — Q-403 bonus: workspace/PVC dependence (scoping read)

**What the code actually supports:** exactly two workspace types — `workflow`
(cross-execution, keyed by workflowRef) and `workflowrun` (per-execution, keyed by
workflowRunId) — mounted at `/workspace/{type}`; anything else is skipped
(`TektonServiceImpl.java:193-227`; ref resolution `WorkspaceService.java:105-107`).

**Evidence that real dependence is LOW and partially broken:**

- **The `workflowRun` workspace path has a live typo-bug**: creation and teardown filter on `"workfowRun"` (missing *l*) — `WorkflowService.java:45` and `:96`, `WorkspaceService.java:51` — while the TaskRun binder matches `"workflowrun"` ignoring case (`TektonServiceImpl.java:194`). A workspace typed `workflowRun` is therefore **never provisioned and never cleaned up** by the run lifecycle; the TaskRun would bind to a non-existent PVC (empty `getPVCName` → claimName `""`). That this hasn't been a reported production fire strongly suggests per-run shared workspaces are effectively unused in the wild.
- The default scratch story is **not** PVC-based: every task unconditionally gets `/data` emptyDir (per-task, per-pod — NOT shared) and `/params` files (`TektonServiceImpl.java:241-288`). Catalogue-style tasks that just read params, do work in `/data`, and emit small results have **zero** workspace dependence.
- Result passing between tasks is via the engine (RunResults → param resolution), not via shared disk.
- Workspaces are opt-in per workflow (`WorkflowWorkspace` on `WorkflowRunEntity:52`), default RWX (`ReadWriteMany`, `application.properties`) — already an operational burden on clusters without RWX storage classes.

**Verdict:** shared-workspace semantics are a **peripheral, optional capability**, not a
contract core. The contract core is C1–C12; C4's shared tier + C13 stay a
capability-gated extension (`TaskWorkspace.optional` already models degradation).

**Implication for Docker/serverless targets:**

- **Docker (standalone)**: named volumes cover both types on a single host — adequate for the J8 docker-compose quickstart; document "workspaces are single-host in standalone".
- **Serverless (Q-404/405)**: no mountable shared FS ⇒ (a) **result-only tasks** (params in, ≤4KB results out) run unchanged — the majority per the evidence above; (b) workspace-declared tasks need **object-storage staging** (dispatcher stages `/workspace/*` down before exec, syncs up after) — a Phase-4 design item, NOT part of the v5 SPI; (c) `capabilities()` lets the engine/queue route workspace-requiring tasks only to workspace-capable dispatchers (the `taskTypes` registration in `EngineClient.registerAgent` L129–154 is the existing precedent for capability-based routing).

---

## Cross-references

- **Q-128/Q-129** (`v5-enhancemnet.md:524-535`): claim ownership fields; this doc supplies the runtime half (C11/C12).
- **queue-design.md §1.6–1.8**: failureClass on `TaskRunEndRequest` (C7), lease renew/abandon (C12), long-poll v2 (kills the poll thread; §1.2 + C-await kill the watch thread).
- **consolidation-proposal.md J8/I6 + DD-06**: packaging (`dispatcher-tekton`/`dispatcher-docker` as separate processes behind one SPI; in-process embedding kept open — the SPI above has no HTTP assumption) and naming (dispatcher tier at E7/E8).
- **Q-404/Q-405/Q-407**: consume Part 2's table as the compatibility-matrix row set.

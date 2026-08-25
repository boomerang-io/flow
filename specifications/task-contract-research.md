# Task Contract Research — params in, results out, across runtimes

**Status:** 📎 **Research record (2026-08-22)** — inputs to the C2/C3 rows of
`runtime-contract.md`. Nothing here is a ruling; the ruled contract lives in
`runtime-contract.md` once decided. Kept so the decision can be re-opened with its evidence.

**Question:** how should a Flow task container receive its params and hand back its results, in
a way that is the same whether the executor is Tekton, Kubernetes Jobs, Docker, or a non-Kubernetes
sandbox — and that a *non-Flow* image (a plain `curl`/`alpine`/vendor image) can use as well?

---

## 1. What the agent does today (2026-08-22, `feat-v5-track8`)

Facts, cited:

- The engine resolves `$(params.x)` references (with the global → team → workflow → context →
  task-results layering) **only inside the TaskRun's own param values** —
  `ParameterManager.resolveParamLayers` iterates `runParams` (`ParameterManager.java:73-102`).
  `spec.script` / `command` / `arguments` / `envs` are copied verbatim from the definition by
  `DAGUtility.java:220-231` and never substituted by Flow. Under Tekton, Tekton substitutes them;
  under Kubernetes Jobs, nothing does (parity gap — engine-side substitution agreed 2026-08-21).
- A TaskRun's `params` list is **the task template's declared params merged with the values
  authored on the workflow node** (`DAGUtility.java:173-191`, `ParameterUtil.addUniqueParams`) —
  not the whole global/team/workflow param set. Inherited layers are used for resolution only.
- Delivery today (commit `b24b39ece`): env only — `PARAMS` (whole `{name: value}` map as one JSON
  string) plus one `PARAM_<NAME>` per param; the `/params` projected-ConfigMap channel was removed
  in `e2e4a93b`. Both executors export `RESULTS_PATH`.
- The task catalogue (`boomerang-io/tasks`, `@boomerang-io/task-core`) reads `/params/<name>`
  files at module load and destructures by original name (`packages/core/src/params.js`), and
  writes results as one file per result into the hard-coded `/tekton/results`
  (`packages/core/src/results.js`).

## 2. Terminology (so the options are compared on the same axis)

- **ConfigMap** — a Kubernetes object holding `key: value` string data. It is *not* a volume.
- **Projected volume** — the pod mechanism that exposes one or more sources (ConfigMap, Secret,
  downward API, service-account token) as files in one directory: each ConfigMap key becomes a
  file named after the key. This is how `/params/<name>` files were produced.
- **Tekton params** — `$(params.x)` string substitution by the Tekton controller into step
  fields. Tekton itself injects **no** env vars and **no** files for params; `/params` was a Flow
  addition layered on top of Tekton.
- **Workspaces / storage** — PVC (or emptyDir) mounts for artifacts and shared state. Structured
  or large inputs belong here, not in params.

## 3. How the competing orchestrators do it

| Product | Params → container | Structured / large inputs | Results ← container | Large outputs | Secrets |
|---|---|---|---|---|---|
| Tekton | `$(params.x)` substituted by the controller into script/args/env; nothing injected otherwise | Workspaces (PVC/emptyDir/ConfigMap/Secret bindings) | Task writes `/tekton/results/<name>`; the injected entrypoint ships it via the termination message (4 KiB) | `results-from: sidecar-logs` raises the cap; otherwise workspaces | Secret-bound workspaces or env `valueFrom` |
| Argo Workflows | `{{inputs.parameters.x}}` template substitution into args/command/env | Input artifacts fetched by the `argoexec` init container to a declared path | Output parameters read **from a file** (`valueFrom.path`) by the `argoexec` wait sidecar after exit | Output artifacts to S3/GCS; parameters capped (~256 KB) | Env `valueFrom` / secret volumes |
| GitHub Actions | `${{ inputs.x }}` expressions; container actions also get env `INPUT_<NAME>` | Event payload as a JSON file at `$GITHUB_EVENT_PATH` | Step appends `name=value` to the file at `$GITHUB_OUTPUT` | Artifacts API | Env, masked in logs |
| GitLab CI | Variables as env | "File" variables: value written to a temp file, env holds the path | `dotenv` report artifact → downstream variables | Job artifacts | Masked/protected variables |
| Airflow `KubernetesPodOperator` | `env_vars` / `arguments` with Jinja | Mounted ConfigMaps/volumes | Task writes `/airflow/xcom/return.json`; an XCom sidecar on a shared emptyDir reads it | XCom backends | K8s Secret env/volumes |
| Kubeflow Pipelines v2 | Launcher passes an `executor_input` JSON file; component reads `InputPath` files | Artifacts materialised to paths | Component writes `OutputPath` files; launcher uploads | Object-store artifacts | K8s Secret mounts |
| Conductor OSS | Worker long-polls HTTP; `inputData` JSON in the poll response | External payload storage: above a threshold the JSON goes to S3 and a reference is passed | Worker `POST`s `outputData` JSON | Same externalisation, automatic | Out of band |
| Dagger | Typed function args; `withEnvVariable`, `withNewFile`/`withMountedFile` | Directories/files as first-class values | Return values (`stdout`, `File.contents`) | Directories as values | `withSecretVariable`, never cached |

Convergence:

1. **Substitution into args/script** is universal and is the author-facing ergonomics.
2. **Env vars with a prefix (`INPUT_`)** are the channel for *scalars* and the only channel a
   non-platform-aware image can consume without help. Nobody puts a large JSON blob into one env var.
3. **Files are the structured/large channel** — artifacts, `executor_input`, `GITHUB_EVENT_PATH`,
   GitLab file variables, the XCom file. In Flow's model that is the workspace/storage mount.
4. **Results are files the task writes and a helper reads** (Tekton entrypoint, Argo wait sidecar,
   Airflow sidecar, `GITHUB_OUTPUT`), or an HTTP response where the worker is a process.
5. **Large payloads are externalised by the platform** (Conductor threshold, Argo/GitHub
   artifacts) — pass-by-reference above a cap is mainstream.

## 4. Candidate executors, side by side

Legend: ✅ built on `feat-v5-track8`, 📐 specified in `runtime-contract.md`, 💭 candidate only.

| Concern | Tekton ✅ | Kube Jobs ✅ | Docker 📐 | ACA sandbox 💭 | AWS Lambda 💭 |
|---|---|---|---|---|---|
| Control plane | fabric8 → TaskRun CR | fabric8 → `batch/v1` Job | Docker Engine API | REST to the session-pool endpoint, Entra token, `identifier` = taskRunId | AWS SDK `Invoke` / Step Functions |
| Execution shape | image to completion | image to completion | image to completion | HTTP request/response to a warm sandbox (custom pool) or `code/execute` (interpreter pool) | event → handler; container-image Lambdas must implement the Runtime API |
| Params: files | projected ConfigMap (Flow addition) | same | bind-mounted temp dir | none — request body only | `/tmp` only, written by the handler |
| Params: env | yes | yes | `--env` | per pool, not per task | ≤ 4 KB total |
| Params: substitution | Tekton today; engine-side planned | engine-side only | engine-side only | engine-side only | engine-side only |
| Script tasks | `step.script` | ConfigMap at `/scripts/script` | temp file, entrypoint = file | interpreter pool runs Python/JS source | not natural |
| Results | `/tekton/results/*` → termination message (4 KiB) | `/dev/termination-log` JSON (4 KiB) | results file read after exit | HTTP response body | return value (≤ 6 MB sync) |
| Logs | pod logs | pod logs | `docker logs` | in the response / your server | CloudWatch |
| Timeout | TaskRun `timeout` + grace | `activeDeadlineSeconds` | dispatcher timer → `docker stop` | request timeout + cooldown | hard 15 min |
| Cancel | status overwrite | delete Job | `docker kill` | drop request | none |
| Workspaces | PVC bindings | PVC volumes | named volumes | none shared | none |
| Isolation | `podTemplate.runtimeClassName` | `agent.tasks.runtimeClassName` | `--runtime=runsc\|kata` | Hyper-V per session, default | Firecracker microVM, default |
| Secrets | Secret projected / env `valueFrom` | same | file / env | pool config, Key Vault | Secrets Manager from the handler |
| Hard size limits | termination 4 KiB; pod ~1.5 MiB; env string 128 KiB | same | none meaningful | request/response (MB range) | 6 MB sync, 256 KB async, 4 KB env |
| Fit with `TaskExecutor` SPI | native | native | native | `create` no-op, `watch` = one call | `create` no-op, `watch` = one call |
| What it cannot run | — | — | — | arbitrary run-to-completion images | arbitrary images; anything > 15 min |

Conclusions: Kubernetes Jobs and Docker are the same shape and should define the contract; ACA
and Lambda run a different task *kind* (HTTP server / Runtime API) and need a catalogue manifest
"execution shape" flag before they can be routed to — they must not drive the Kubernetes/Docker
contract.

## 5. The channel options debated (2026-08-21/22)

| Option | Universal for non-Flow images? | Size ceiling | Secrets story | Verdict |
|---|---|---|---|---|
| A — `/params` files via projected ConfigMap, owned by `TaskService` (v4) | No — a plain image does not read `/params` | 1 MiB | Secret projected into the same directory, task unchanged | Replaced |
| B — `PARAMS` env (whole map as one JSON string) | No — a plain image does not parse JSON from env | 128 KiB per env string | Cannot splice a Secret into JSON; needs a second channel | Rejected |
| C — `PARAM_<NAME>` env, one per param, plus `PARAM_NAMES` | **Yes** — the `INPUT_` pattern | 128 KiB per var; ~2 MiB total | `valueFrom.secretKeyRef` per var, same name | **Ruled: keep** |
| D — `/params/<name>` files, one file per param, delivered by the executor | Partially — `cat /params/x` works anywhere, but nobody expects it | 1 MiB | Secret projected into the same directory | **Ruled: dropped** — files are storage, not params; `task-core` moves to env with `/params` as its v4 fallback |
| E — substitution of `$(params.x)` into script/args (engine-side) | **Yes** — the most universal of all | n/a (becomes pod-spec bytes) | none (substituted text is visible) | **Ruled: keep**, engine-side (G1 item) |

**Ruling (2026-08-22, maintainer):** C + E are the contract. The decisive argument was universality
— a non-Flow image understands only its command line and its environment. `PARAM_NAMES` closes the
name round-trip gap for the catalogue; one-file-per-param vs one JSON file is moot.

Also ruled: a TaskRun's params are already only the template-declared set merged with the node's
values (§1), but the node merge accepts undeclared keys — validating "node params ⊆ declared
params" is a definition-side follow-up alongside the substitution work.

## 6. Isolation — RULED (2026-08-25, maintainer)

**One setting per agent/dispatcher deployment** (`agent.tasks.runtimeClassName`); NO per-task
`isolation` field on the task spec. A deployment that needs a different tier is a separate agent
deployment (each registers with its own name/task-types, so the engine already routes between
them). Re-open only if a real deployment needs mixed trust tiers behind one agent.

## 7. Sensitive params — direction under exploration (2026-08-25)

**Trust model (maintainer):** sensitive means sensitive *upward* — from the engine to the UI/API
consumer. *Downward* — engine to task execution — plain delivery is acceptable because only Ops
has access to the execution substrate.

**What exists (verified):** `DataAdapterUtil` (lib-common) redacts params whose spec
`type == "password"` (`FieldType.PASSWORD`) by nulling the value and setting `hiddenValue=true`,
which `client-web` honours. Applied at the definition/config surfaces: workspace/team params
(`WorkspaceService:867`), global params (`ParameterService:112`), workflow param specs
(`WorkspaceWorkflowService:192/259/359/472`). So **no new `sensitive` field is needed — the
`type=password` param spec type IS the marker.**

**The gap (verified):** `DataAdapterUtil.filterRunParamValueByFieldType` — the variant that
redacts **resolved RunParam values on run payloads** by joining the run's params against the
workflow's param spec — has **zero callers**. A WorkflowRun/TaskRun response therefore returns
resolved password values in plain text, which breaks the upward half of the trust model. `RunParam`
itself carries no type marker on the wire (`ParamType` is `@JsonIgnore`), so run-payload redaction
must join against the definition, exactly as that orphaned method does.

**Gap CLOSED (2026-08-25)** for payloads: `WorkflowRunService.redactForDisplay` runs on the
workspace-scoped v2 `get`/`query` only (never the unscoped engine/dispatcher reads) — password
params blanked by name against the run's revision spec, and their resolved values scrubbed from
task params, spec fields and results (`DataAdapterUtil.redactWorkflowRun`/`redactTaskRun`; values
under 4 characters are name-blanked but not value-scrubbed). The utilities live in the existing
`DataAdapterUtil` `filter*` family (`filterWorkflowRunValueByFieldType` reuses
`filterRunParamValueByFieldType` for the name-join; `filterTaskRunValues` is the value scrub).
**The TaskRun log stream is ALSO closed (2026-08-25)**: `WorkspaceTaskRunService.streamLog` wraps
the stream in `FilterValuesOutputStream` (line-buffered UTF-8 scrub; a secret straddling a
chunk boundary of a single >64KB line is the accepted edge), realising the v4 commented-out
masking intent at the streaming function itself. Covered by `ParamRedactionTest`,
`RunRedactionTest`, `FilterValuesOutputStreamTest`.

## 8. Future: workspace storage sources beyond PVC

Recorded as a future Workflow Storage option (not built): in Tekton, a workspace may be bound to a
**ConfigMap or Secret** (read-only — Kubernetes mounts them read-only, a task cannot write back),
an **emptyDir** (writable, per-pod), or a PVC. For clusters without a usable PVC class, the options
are therefore: ConfigMap/Secret as a read-only *input* source (seed files, certificates), emptyDir
for per-task scratch (already `/data`), and object-storage staging for anything that must outlive
the pod (Q-403's deferred item). Add `configMap` / `secret` / `emptyDir` as `WorkflowWorkspace`
source types when Workflow Storage is next worked; `StorageType` (the workflow/workflowRun scope)
is orthogonal to the source.

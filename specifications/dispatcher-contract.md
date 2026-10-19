# Dispatcher contract

A dispatcher is any process that takes task runs from the engine, runs them, and reports how they ended:
Flow's own Kubernetes dispatcher (`service-dispatcher`), an in-process dispatcher inside a host product, or a
future serverless one. They all speak one protocol under `/dispatcher/v1`. Its routes and wire models are
generated from the engine as an OpenAPI document — served at `/api/docs/spec/dispatcher-v1` and checked in at
`contracts/dispatcher-v1.yaml` (`dispatcher/DispatcherContractConfiguration.java`); `DispatcherContractTest`
fails when the two differ. This document gives the semantics the document cannot, and what a task is to any
dispatcher: which types reach one, the spec and params it receives, the caps on what it may return, the catalogue
the tasks come from. Paths below are under `service-core/src/main/java/io/boomerang/` unless they start with a
module name.

## Topologies

The contract is the boundary; the process is not. A dispatcher is its own deployable only when a boundary
exists between it and core: credentials core must not hold, a different network zone, isolation of untrusted
content, or placement on infrastructure core cannot reach (decision 0105). Where none exists, the poll loop
runs inside another process through `dispatcher-sdk`, which carries the client, the poll loop, slots, leases
and the `TaskHandler` and `WorkflowHandler` interfaces (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/`)
and nothing Kubernetes-specific.

| Shape | Boundary | Where the loop runs |
| --- | --- | --- |
| Kubernetes Jobs or Tekton (`service-dispatcher`) | Cluster credentials, `runtimeClassName` placement, a network zone of its own | Its own image |
| Docker, for local development | None in development; the Docker socket is root, so it is not a production executor | A small SDK-based process in the compose stack |
| Serverless containers | Outside the cluster, scale to zero, its own credentials | Its own deployable; its autoscaler needs a queue-depth read the contract does not yet have |
| Warm pool of microVMs | Isolation per task, short life | Each VM is a dispatcher that registers, polls with `limit=1`, ends and exits; registration expiry is not yet built |
| In-process (ARCHIE) | None | The host embeds the SDK and implements `TaskHandler` |

## Where the contract ends

The contract covers registration, the two queues, start, heartbeat, end, termination orders, storage release
and the two optional log pieces ("Logs"), and nothing else. How a task is run — image, command, volumes,
resources, placement — is the dispatcher's own, described for Flow's Kubernetes dispatcher in
`dispatcher-kubernetes.md`; so is what sits behind a dispatcher's live log route. One thing a complete dispatcher
needs sits outside it today: **dispatcher identity** is a long-lived record upserted on name and host
(`dispatcher/DispatcherService.java:98-130`) with no expiry or deregistration route.

## Versioning and compatibility

The protocol carries its own version, `1`, apart from the product version.

- An additive change — a new optional query parameter, a new response field, a new end reason, a new route —
  keeps the protocol at `1` and is listed under "Changes" below.
- A dispatcher MUST ignore response fields it does not know, and MUST NOT rely on the order of fields or of
  list entries unless this document states one.
- The engine MUST accept a request that omits a field added later, behaving as it did before that field
  existed, and MUST treat an end reason it does not know as an ordinary failure.
- A change that removes or redefines a field, a route or a status meaning MUST ship as `/dispatcher/v2`,
  with `/dispatcher/v1` served beside it for at least one minor product release.

## Authentication

Every call carries `Authorization: Bearer <token>`: a global Flow token with an actor kind — a machine token
(`dispatcher/DispatcherAuthFilter.java:54-80`, `core/TokenService.java:433-438`). Anything else is a bare
401. `flow.dispatcher.auth.enabled=false` makes the filter a pass-through for local development; it is
independent of `flow.security.enabled` (`authorization.md`, "Dispatcher endpoints").

## The lifecycle

| Step | Route | Answer |
| --- | --- | --- |
| Register once at startup | `POST /register` | the dispatcher id, used in every later path and request |
| Ask for storage work | `GET /{id}/workflows` (long poll) | workflow runs to provision |
| Report storage ready | `PUT /workflowrun/{id}/start` | — |
| Ask for task work | `GET /{id}/tasks` (long poll) | task runs to run or to terminate |
| Say a task is starting | `PUT /taskrun/{id}/start` | the task run, which says whether to go on |
| Keep long tasks alive | `PUT /{id}/heartbeat` | — |
| Store the finished log (optional) | `PUT /taskrun/{id}/log` | — |
| Report the outcome | `PUT /taskrun/{id}/end` | the task run |
| Release storage | `POST /workspaces/releasable` | which held volumes may go |

(`dispatcher/DispatcherControllerV1.java:69-248`)

## Registration

`POST /register` takes `name`, `host`, `version` and `taskTypes`, and optionally `logsUrl` and `logsToken`
("Logs"). The record is upserted on name and host, so a restarting dispatcher keeps its id, and the log
source is replaced whole on every registration (`dispatcher/DispatcherService.java:98-130`). A dispatcher
registered for no task types is answered 204 on every task poll (`:279`).

## Polling

Both queue routes are long polls. The engine holds the request for up to 30 s, looks for claimable work every
second, and answers as soon as it has claimed something — at most a page of 20 — or 204 when the window passes
(`DispatcherService.java:48-50`, `:291-337`). A dispatcher's client read timeout MUST exceed 30 s.

A dispatcher SHOULD poll again as soon as a poll returns runs or was held for its window, and SHOULD wait about
5 s after a poll that failed or was answered at once with nothing — claiming switched off
(`flow.queue.enabled=false`) and a filter that matches nothing both answer that way. The SDK's poller does
exactly this (`dispatcher-sdk/.../dispatcher/sdk/QueuePoller.java:68-87`).

The task poll takes optional query parameters:

| Parameter | Meaning |
| --- | --- |
| `limit` | Most new task runs to claim for execution, held to a page. `0` claims none. Termination orders are never limited. A dispatcher SHOULD send its free capacity: a claimed task is committed to it. |
| `type` | Task types to claim, a subset of those registered, else `400 QUERY_INVALID_FILTERS` |
| `task` | Task slugs |
| `workflowLabel` | `key=value[,value]`, one key, matched on the workflow definition's labels |

`type`, `task` and the `workflowLabel` values are comma-separated lists in which `*` is the only wildcard,
anchored at both ends. Filters are ANDed and values ORed. Slugs and labels resolve to ids cached for 30 s, so a
newly labelled workflow is claimable within 30 s (`dispatcher/ClaimFilterService.java`). A poll without filters
still claims work a filtered poll would; reserving it is not built (flow#499).

## What a poll hands out

Each claim is a compare-and-set on one document, so two dispatchers never receive the same run
(`DispatcherService.java:300-325`). A task run in the answer is one of two orders:

| Phase and status | Order | The dispatcher |
| --- | --- | --- |
| `queued`, `ready` | Run it | MUST call start before running, and end when done |
| `completed`, `cancelled` or `timedout` | Terminate it | MUST stop any runtime work it holds for that id, and MUST NOT call end |

A run order carries the task's spec with its references filled in by the engine at claim, so it arrives
resolved as before (`dispatcher/DispatcherService.java:338`); the engine stores the spec with its references and
fills in a copy for each claim. It carries the task's params already resolved, each with its `type`: absent means `string`, and
`secret` marks a string whose value Flow never shows a consumer. The value is in plain text on the claim; a
dispatcher MUST keep it out of its own logs and MAY deliver it exactly as a string (`parameters.md`,
"Sensitive parameters"). The SDK reads an unknown type as `unknown`
(`dispatcher-sdk/.../dispatcher/sdk/model/ParamType.java`).

The engine fills in `$(params.x)`, `$(tasks.x.results.y)` and the other references in two places, at two times
(`parameters.md`):

| What | When | Stored |
| --- | --- | --- |
| The TaskRun's own parameter values | At admission (`engine/ParameterManager.java:152`, `TaskExecutionService.java:209`) | Yes - frozen by the admission compare-and-set (`TaskRunService.java:336`), secret values encrypted |
| `spec.script`, `spec.command`, `spec.arguments`, `spec.envs` | When a dispatcher claims the task (`ParameterManager.fillSpec` `:294`, called from `dispatcher/DispatcherService.java:338,381`) | No - the stored spec keeps its references; only the copy on the claim carries values |

So no secret value is ever written into a stored spec. Admission has checked every reference, so the fill-in
fails only for a workspace or global parameter deleted since; the task then ends `failed` with reason
`SpecFillFailed` (`DispatcherService.java:396`). The termination order, the start and end answers and every
consumer read carry the stored spec. The TaskRun's params are the task's declared params merged with the values
authored on the workflow node (`engine/DAGUtility.java:183`). A `custom` task takes its runtime from its own
params - `image`, `command` and `arguments` (newline-split) and `shellScript` - not from the catalogue entry, which
declares no image (`DAGUtility.java:338`). A secret-typed one is copied as its reference `$(params.<name>)`, filled
in at claim, so a secret `command` or `arguments` arrives as one element (`:370`).

An item of a for-each task is an ordinary TaskRun to the dispatcher: it is claimed by `id` and run like any
other of its type. It carries the parent's params plus `item` (the element; an object or array arrives
JSON-encoded) and `index` (its position from 0, as text), so `$(params.item)` resolves in the spec. Its params
resolve when the item is admitted and its spec when it is claimed, not when the parent fans out
(`engine/TaskExecutionService.java:663-701`). Every item mounts the same workspace as the parent, so all items of a
run share the run's workspace.

A terminate order also arrives for a task the engine has requeued while a dispatcher may still hold its work
(`engine/TaskRunService.java:245-269`); it releases the claim, so the next attempt can be claimed. A dispatcher
with nothing left to stop treats the order as done.

## Starting

`PUT /taskrun/{id}/start` carries `dispatcherRef` (the registered id) and answers with the task run
(`engine/TaskRunService.java:891`). The dispatcher MUST NOT run the task when the answer's phase is `completed` —
it was cancelled while being handed over — or when the engine answers 4xx. When the engine cannot be reached it
MAY run the task: the end is still fenced on the claim. The engine moves the task to running asynchronously, so
`queued` in the answer is normal.

## Ending

`PUT /taskrun/{id}/end` carries `dispatcherRef`, `status`, `statusReason`, `statusMessage` and `results`
(`engine/TaskRunService.java:927`).

- `status` is `succeeded`, `failed` or `invalid`; anything else is `400 TASKRUN_INVALID_END_STATUS` (`:967`).
- A request naming a dispatcher that no longer holds the claim is `409 TASKRUN_CLAIM_SUPERSEDED` and changes
  nothing (`:1054-1066`).
- `results` carries only the result names the task's definition declares; the caps below bound its size.
- `statusReason` is a closed set (`service-core/.../model/TaskRunEndRequest.java`). Two of them mean the task
  never started, and the engine requeues it instead of failing it — with backoff, on the same budget of three
  more attempts as a timed-out claimant — and fails it with that reason only once the budget is spent
  (`engine/TaskRunService.java:71`, `:1059`):

| Reason | Send when |
| --- | --- |
| `ExceededQuota` | The runtime refused to create the task's work for lack of capacity |
| `StartTimeout` | The task's work was created but never began within the dispatcher's start deadline |
| `RateLimited` | The runtime or a gateway behind it refused the task for rate, so it should be tried again later |

**Caps.** The params cap is the engine's, identical on every dispatcher. The results limit belongs to the
dispatcher that runs the task (on Kubernetes it is the 4096-byte container termination message,
`dispatcher-kubernetes.md`, "Results on Kubernetes"). The engine's results setting is only a storage guard, so a
faulty dispatcher cannot grow a task run toward MongoDB's 16 MB document limit.

| Cap | Property (`service-core/.../application.properties:176-177`) | Where checked | Effect |
| --- | --- | --- | --- |
| Params | `flow.engine.task.params.max-bytes=16384` | Before admission (`TaskExecutionService.java:205-219`) | The task is invalidated with `PARAMS_TOO_LARGE` and never becomes claimable |
| Results (storage guard) | `flow.engine.task.results.max-bytes=1048576` (1 MB) | In `TaskRunService.end` (`TaskRunService.java:909-919`) | Status becomes `failed` with `RESULTS_TOO_LARGE` and `statusReason=ResultsTooLarge`; the oversize results are not persisted |

A for-each task's results are not checked again. Each item is capped where it returns its results, like any task;
the parent's results are the per-item values collected into arrays by the engine
(`TaskExecutionService.completeParentIfItemsTerminal` `:716`), with no cap of their own. When MongoDB refuses the
combined arrays as over its 16 MB document limit, the parent fails with `statusReason = ResultsTooLarge` and no
results, and the run moves on (`TaskExecutionService.java:750-778`). A task that consumes the array still meets
the params cap at its own admission.

## Leases and heartbeat

A claim has no lease until the dispatcher's first heartbeat for it. `PUT /{id}/heartbeat` lists the task run
ids the dispatcher is still working on; the engine sets each owned one's lease 90 s ahead
(`flow.dispatcher.lease-ms`, `DispatcherService.java:62`, `:138-151`). A dispatcher SHOULD beat every 30 s for any
task that can outlive a minute. A lease that lapses, or a dispatcher with no poll or heartbeat for 60 s
(`engine/WorkflowWatcher.java:76`), gets the task requeued or abandoned (`execution-model.md`).

## Logs

A task's log is read through core alone — `GET /api/v2/taskrun/{id}/log`, which authorizes the reader and
scrubs the run's secrets (`workflow/WorkflowRunService.java:433-455`) — and core serves it from the first of
these (`engine/TaskRunService.java` `streamLog`, `engine/LogClient.java` `resolve`):

| Source | When |
| --- | --- |
| The stored log | The dispatcher uploaded one: `PUT /taskrun/{id}/log?dispatcherRef=<id>`, the log as `text/plain`, fenced on the claim like start and end. Core stores it as a log-scoped artifact of the run for the task-log retention setting, 7 days by default (`workflow/ArtifactService.java` `storeTaskLog`; `data-model.md`, "Artifacts") and answers 204. 409 `TASKRUN_CLAIM_SUPERSEDED`: another dispatcher holds the claim. 413 `TASKRUN_LOG_TOO_LARGE`: over `flow.engine.task.log.max-bytes` (10 MiB), refused whole, never truncated. 503 `ARTIFACTS_NOT_CONFIGURED`: core has no artifact store, which a dispatcher SHOULD take as final for its life. 503 `TASKRUN_LOGS_DISABLED`: the "Store Task Logs" setting is off, which MAY be switched back on, so a dispatcher SHOULD keep offering. A later upload for the same task run replaces the earlier one. |
| The dispatcher's live route | The claim's dispatcher registered `logsUrl`: core calls it with `workflowRef`, `workflowRunRef` and `taskRunRef` as query parameters and `Authorization: Bearer <logsToken>`, and relays the stream. What answers there — the pod, Loki — is the dispatcher's own. |
| A message | Neither: a 200 whose body says no log is available. Nothing on core names a dispatcher; a dispatcher that wants its running tasks followed registers a `logsUrl`. |

A dispatcher SHOULD upload before it reports the end, so the log is in place when the task shows as finished,
and MAY both serve live and upload. A dispatcher with no HTTP surface registers no `logsUrl` and uploads; one
whose logs live only in a pod registers a `logsUrl` so a running task can be followed. Flow's Kubernetes
dispatcher does both (`dispatcher-kubernetes.md`, "Flow's dispatcher").

## Storage

`GET /{id}/workflows` hands out workflow runs that declare a workspace, claimed for provisioning; the dispatcher
provisions the run's one volume and calls `PUT /workflowrun/{id}/start`. A provisioning failure is reported by not
starting: the engine releases the claim after a grace and fails the run after three attempts.
`POST /workspaces/releasable` takes the run ids the dispatcher holds volumes for (500 at most, else 400) and
answers with those whose run is finished (`DispatcherService.java:52`, `:365`). A dispatcher that runs nothing on
shared storage MAY skip both.

Shared storage is one workspace per run: a workflow declares a single `workspace` (`size`, `mountPath`,
`className`, `accessMode`; `service-core/.../model/WorkflowWorkspace.java`), presence is the switch, and the run
copies it at submit, the request's own `workspace` winning when it carries one
(`workflow/WorkflowService.java:1966`). Which tasks mount it is decided once, when `DAGUtility` materialises the
TaskRun: every task mounts the run's workspace unless its node says `workspace: false`
(`engine/DAGUtility.java:229-240`); the canvas offers no way to set that, so the ordinary case is that every task
mounts it. The TaskRun carries its own copy with `mountPath` settled to the declared path or `/workspace`, so
nothing later in the run changes it.

## Task types

Only `template`, `custom`, `script`, `generic` and `ai` tasks go to a dispatcher
(`engine/TaskExecutionService.java:208-212,340`), plus the two artifact types; every other type runs inside the
engine. `TaskType` (`service-core/.../enums/TaskType.java:15-32`) is dispatched in `TaskExecutionService.java:320-373`.

| Type | Behaviour |
| --- | --- |
| `start`, `end` | Structural nodes of the graph; never executed |
| `template`, `custom`, `script`, `generic`, `ai` | Wait for a dispatcher |
| `decision` | Evaluates the branch and ends `succeeded` |
| `acquirelock`, `releaselock` | Take or release a row in the `task_locks` collection; acquire parks as waiting until the lock is free |
| `runworkflow` | Submit a child workflow run. Ends `succeeded` at once, or with `wait=true` parks as waiting and takes the child's terminal status and results; may run for each item, one child per item (see `execution-model.md`) |
| `runscheduledworkflow` | Schedule another workflow to run later, then end |
| `setwfstatus`, `setwfproperty` | Write the run's status message or a workflow-scoped param, then end |
| `approval`, `manual` | Create an action and wait for a person |
| `eventwait` | Wait for a matching inbound event unless pre-approved |
| `sleep` | Park as waiting; the watcher completes it after the duration |
| `uploadartifact`, `downloadartifact` | Wait for a dispatcher, which runs the default worker's `artifact upload` / `artifact download` (`flow.dispatcher.artifact.image`). The engine fills the link params when it hands the task out, verifies an upload when it ends, ends an optional download of a missing artifact `succeeded` with `found=false` without dispatching it, and appends `found=true` to a dispatched download that succeeds (`data-model.md`) |

## Catalogue

Catalogue tasks are built from the `boomerang-io/tasks` monorepo into the `boomerangio/task-flow` image. The
migrations seed 89 tasks and their revisions from `seed/tasks.json` and `seed/task-revisions.json` into `tasks` and
`task_revisions`, matched by name and inserting only what is absent
(`service-core/src/main/java/io/boomerang/migration/_0017__SeedTaskCatalogue.java:68-160`); the two artifact tasks
follow (`_0018__SeedArtifactTasks`). A seeded task is never inserted again once present, so a task added to the seed
after a release needs a change unit of its own to reach an existing database, which is what
`_0023__SeedAiTaskFamily` is for `ai-prompt` and `ai-agent`. A `template` or `script` task without
an explicit image inherits the run's `boomerang.io/task-default-image` value (`DAGUtility.java:212-218`).
The engine-handled `run-workflow` and `run-scheduled-workflow` entries declare the params the engine reads
(`workflowRef` and the boolean `wait`; plus `futureIn`, `futurePeriod`, `timezone`, `time`). On an upgraded v3
install, `_0019__V3UpgradeCatalogueRevisions` adds whichever of them the seeded revision declares and the install's
revision lacks, and moves any revision off the retired `worker-flow` image (`:44-46,72`).

A workflow node's `taskVersion` is pinned when the workflow is saved, not when it runs:
`WorkflowService.createWorkflowRevisionEntity` resolves each non-start/end node through
`TaskService.retrieveAndValidateTask` and stamps the resolved version onto the node — the version the node asked
for, or the catalogue's latest when it asked for none. At run time `DAGUtility.createTaskList` resolves the same
way and records the result on the TaskRun (`engine/DAGUtility.java:140-142`), so a stored node with no version
still resolves latest. Publishing a new Task version therefore changes nothing for workflows already saved
against an older one, which is the point: a run is reproducible from its revision. To move a workflow forward,
save it again with the node's `taskVersion` set to the target version (or omitted, to take latest); the editor
surfaces this per node as a "New version available" prompt, driven by the `upgradesAvailable` flag
`WorkflowService.areTaskUpgradesAvailable` sets
(`client-web/src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.tsx:58-64,133`).

## AI tasks

An `ai` task calls an OpenAI-compatible endpoint. The author never builds a container: the node references one of
two seeded catalogue tasks of type `ai`, the engine treats it as any other dispatched type, and a dispatcher runs
the image and command the revision declares, `boomerangio/task-ai:2.0.0` with `prompt` or `agent` (how Flow's
Kubernetes dispatcher does, and which images it admits, is in `dispatcher-kubernetes.md`, "AI tasks"; an
in-process dispatcher runs the same contract in its own handler and ignores the image). Everything the model call
needs is a declared param:

| Task | Params | Results |
| --- | --- | --- |
| `ai-prompt`, one completion | 17: `model` (required), `prompt` or `promptFile`, `systemPrompt`, `messages` or `messagesFile` (an exact message array replacing the prompts), `endpoint` and `token` (optional; the executor's defaults apply when absent), `temperature`, `maxTokens`, `seed`, `parameters` (an open JSON map passed through, winning on conflict), `responseFormat`, `jsonSchema`, `files`, `maxContextBytes`, `outputFile` (the full reply written to the run workspace) | 9: `output`, `finishReason`, `model`, `promptTokens`, `completionTokens`, `totalTokens`, `reasoningTokens`, `cachedPromptTokens`, `cost` (the last three empty when the endpoint does not report them) |
| `ai-agent`, a tool-calling loop | The 17 plus `tools`, `mcpServers`, `mcpTokens` (password-typed, the only place a tool credential travels), `allowedTools`, `toolContext`, `maxTurns`, `maxToolCalls`, `parallelToolCalls`, `toolResultReinforcement`; 26 in all | The 9 plus `turns` and `toolCalls` |

The seed entries (`seed/tasks.json`, `seed/task-revisions.json`) are copies of the definition files in the
`boomerang-io/tasks` repository (`tasks/ai/catalog/ai-prompt.json`, `ai-agent.json`), which are the source of the
contract: a change there is one PR in that repository and a seed sync here, and a param or result added on one side
has to land on the other. The worker is a task image, not a product image: it is released from the same repository
on its own `task-ai@<version>` tags, each revision pins the exact `boomerangio/task-ai:<version>` it was written
against, and a deployment moves to a newer worker by seeding a new revision. `slider` is the only param type with a
numeric range, so `AbstractParam` carries nullable `min`, `max` and `step` beside `options`
(`service-core/.../model/AbstractParam.java`). JSON-valued params are `json` text the image parses up front, failing
`INVALID_PARAM` with the param's name. Params reach the image as `PARAM_<NAME>` environment variables and results
come back through `RESULTS_PATH`, as flat typed results under the same caps as every other task (decision 0041: the
1 MB engine guard, and 4 KB on the Kubernetes dispatcher), so a long `output` fails the task with
`RESULTS_TOO_LARGE` rather than truncating; `outputFile` and the task log carry what does not fit. There is no usage
field and no meter: a platform sums `totalTokens` across task runs through the existing query API.

**Token delivery.** `token` is password-typed, so it is a `secret` param: a literal typed into the node or a
reference to a workflow param reads as `*****` on the run reads and is scrubbed from the log stream
(`parameters.md`, "Sensitive parameters"). Downward it is a plain `PARAM_TOKEN` environment variable on the pod,
like every other param — there are no per-task secrets yet, so anyone who can read the pod spec or exec into the
pod can read the token.

## Errors

Every error body is the engine's standard shape — `timestamp`, `code`, `reason`, `message`, `status` — except
the authentication filter's bare 401 (`CLAUDE.md`, "API errors").

## SDK

`dispatcher-sdk`, the dispatcher software development kit (SDK), is this protocol as a Java library, so a
dispatcher author writes only the work; Flow's Kubernetes dispatcher is built on it. It depends on `spring-web`
and Jackson and nothing else, owns its wire
models (`model/*`, unknown fields ignored, an unknown enum value read as `unknown`), and its own
`DispatcherContractTest` fails when a schema, property, enum value or route in `contracts/dispatcher-v1.yaml` is
neither modelled nor listed as deliberately not. Paths are under `dispatcher-sdk/.../dispatcher/sdk/`.

| Class | Gives the author |
| --- | --- |
| `DispatcherClient` | One method per route over the host's `RestClient` (keeping its proxy, certificates and timeouts), the token on engine calls only (`DispatcherClient.java:56-64`) |
| `Dispatcher` | Registration retried with backoff until the engine answers, with `logs(url, token)` registering the live log route and its bearer and `logsToken()` handing the bearer to the host's own check (`Builder.randomLogsToken()` makes one); one long-poll per task pool, with the pacing above, `limit` = the pool's free capacity read on every poll, and optional filters; the workflow queue only when a `WorkflowHandler` is given; one heartbeat every 30 s for every task in flight; a stop that drains for 10 s (`Dispatcher.java`) |
| `TaskRunner` | The start answer honoured; the end retried with backoff until the engine takes it or refuses it with a 4xx, the task held in flight and its lease renewed meanwhile; terminate orders signalled to the running task, with no end (`TaskRunner.java`) |
| `TaskHandler`, `TaskContext` | The interface the author implements: `run(task, context)` returns a `TaskResult` or throws a `TaskFailure` (`TaskFailure.exceededQuota`, `startTimeout` for the requeued reasons); `cancel(task)` stops work for a terminate order. The context carries the cancellation signal, the artifact transfer, and `uploadLog`, which stores the finished log with the engine best effort and stops asking an engine that answered it keeps none (`LogUploader.java`) |
| `ArtifactTransfer` | The `uploadartifact` / `downloadartifact` transfer for a dispatcher without the worker image: PUT or GET on the link params filled at claim time, the download verified against `sha256` (`ArtifactTransfer.java:83-131`) |

It is published to GitHub Packages as `io.boomerang:dispatcher-sdk`, versioned with the product tag
(`https://maven.pkg.github.com/boomerang-io/flow`; `.github/workflows/ci-release.yml`, job `publish-sdk`), and is
the only Flow library published. Reading it needs a GitHub token with `read:packages`.

## Changes

| Change | Kind |
| --- | --- |
| `limit` on the task poll; `ExceededQuota` and `StartTimeout` end reasons; the start answer's `completed` means do not run | Additive (flow#500) |
| `type`, `task` and `workflowLabel` task-poll filters; termination orders follow them | Additive (flow#502) |
| The routes moved from `/api/v1/dispatcher` to `/dispatcher/v1` | Path, before the first release |
| `logsUrl` and `logsToken` at registration; `PUT /taskrun/{id}/log` stores the finished log | Additive (decision 0112) |
| `type` on each `RunParam`, with `secret` | Additive (decision 0115) |
| `RateLimited` end reason, requeued like `ExceededQuota` | Additive |

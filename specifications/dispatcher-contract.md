# Dispatcher contract

A dispatcher is any process that takes task runs from the engine, runs them, and reports how they ended. Every
dispatcher speaks one protocol under `/dispatcher/v1`, generated from the engine as an OpenAPI document served at
`/api/docs/spec/dispatcher-v1` and checked in at `contracts/dispatcher-v1.yaml`
(`dispatcher/DispatcherContractConfiguration.java:17-26`); `DispatcherContractTest` fails when the two differ. This
document gives the semantics the OpenAPI document cannot, and what a task is to any dispatcher: which types reach one,
the spec and params it receives, the caps on what it may return, and the catalogue. Paths are under
`service-core/src/main/java/io/boomerang/` unless they start with a module name; `sdk/` is short for
`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/`.

## Topologies

The contract is the boundary, not the process. A dispatcher is its own deployable only where a boundary separates it
from core: credentials core must not hold, a different network zone, isolation of untrusted content, or placement on
infrastructure core cannot reach. Where none exists, the poll loop runs inside another process through
`dispatcher-sdk`, the dispatcher software development kit (SDK), which carries nothing Kubernetes-specific.

| Shape | Built | Boundary | Where the loop runs |
| --- | --- | --- | --- |
| Kubernetes Jobs or Tekton (`service-dispatcher`) | Yes | Cluster credentials, `runtimeClassName` placement, a network zone of its own | Its own image |
| In-process | Yes, through the SDK | None | The host embeds the SDK and implements `TaskHandler` |
| Docker, for local development | No | None in development; the Docker socket is root, so it is not a production executor | A small SDK-based process in the compose stack |
| Serverless containers | No | Outside the cluster, scale to zero, its own credentials | Its own deployable; its autoscaler needs a queue-depth read the contract does not have |
| Warm pool of microVMs | No | Isolation per task, short life | Each VM registers, polls with `limit=1`, ends and exits; it needs registration expiry, which is not built |

## Where the contract ends

The contract covers registration, the two queues, start, heartbeat, end, termination orders, storage release and the
two optional log pieces ("Logs"), and nothing else. How a task is run (image, command, volumes, resources,
placement) and what sits behind a live log route are the dispatcher's own (`dispatcher-kubernetes.md` for Flow's).
One thing a complete dispatcher needs sits outside it: a registration has no expiry and no deregistration route
(`dispatcher/DispatcherService.java:132-133`).

## Versioning and compatibility

The protocol carries its own version, `1`, apart from the product version.

- An additive change (a new optional query parameter, response field, end reason or route) keeps it at `1`.
- A dispatcher MUST ignore response fields it does not know, and MUST NOT rely on the order of fields or of list
  entries unless this document states one.
- The engine MUST accept a request that omits a field added later, behaving as it did before that field existed, and
  MUST treat an end reason it does not know as an ordinary failure.
- A change that removes or redefines a field, a route or a status meaning MUST ship as `/dispatcher/v2`, with
  `/dispatcher/v1` served beside it for at least one minor product release.

## Authentication

Every call carries `Authorization: Bearer <token>`: a global Flow token with an actor kind, a machine token, which
becomes the identity the request runs as; anything else is a bare 401 (`dispatcher/DispatcherAuthFilter.java:63-93`,
`core/TokenService.java:428-433`). `flow.dispatcher.auth.enabled=false` checks nothing and runs the request as the
security-off synthetic identity, independent of `flow.security.enabled` (`authorization.md`, "Dispatcher endpoints").

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

(`dispatcher/DispatcherControllerV1.java:76-258`)

## Registration

`POST /register` takes `name`, `host`, `version` and `taskTypes`, and optionally `logsUrl` and `logsToken` ("Logs").
The record is upserted on name and host, so a restarting dispatcher keeps its id, and the log source is replaced
whole on every registration (`dispatcher/DispatcherService.java:138-170`). A task type the engine does not know is
dropped (`common/enums/TaskType.java:55-60`). A dispatcher registered for no task types is answered 204 on every
task poll (`dispatcher/DispatcherService.java:350-353`).

## Polling

Both queue routes are long polls. The engine holds the request for up to 30 s, looks for claimable work whenever a
task is admitted, or a run with a workspace is submitted to start, on the engine instance holding it and at least
every `flow.dispatcher.poll.recheck-ms` (one second by default, never under 100 ms), and answers as soon as it has
claimed something, or 204 when the window passes (`dispatcher/DispatcherService.java:55-73`, `:380-429`,
`:494-497`, `dispatcher/PollWakeups.java:24-55`). A task is therefore claimed about an end round trip, an admission
and a claim round trip after its predecessor ends, not up to a re-check interval later; on a multi-instance engine a
poll held on another instance than the one that admitted the task finds it within the interval. One task answer claims at most 20 task runs to run and 40
termination orders; one workflow answer at most 20 runs. A dispatcher's client read timeout MUST exceed 30 s. It
SHOULD poll again at once after a poll that returned runs or was held for its window, and SHOULD wait about 5 s after
one that failed or was answered at once with nothing; claiming switched off (`flow.queue.enabled=false`) and a filter
that matches nothing answer at once (`:340-343`, `:359-362`). The SDK's poller does this
(`sdk/QueuePoller.java:104-123`).

A dispatcher with no free slot keeps a `limit=0` poll open so termination orders still reach it, and MAY open a work
poll beside it the moment a slot frees: each termination order is claimed by its own compare-and-set, so two polls
never receive the same one (`dispatcher/DispatcherService.java:395-402`). The SDK does this, with at most one work
poll in flight per pool (`sdk/Dispatcher.java:259-282`).

The task poll takes optional query parameters:

| Parameter | Meaning |
| --- | --- |
| `limit` | Most new task runs to claim for execution, at most 20; absent means 20 and `0` claims none (`dispatcher/DispatcherService.java:356`). Termination orders are never limited. A dispatcher SHOULD send its free capacity: a claimed task is committed to it. |
| `type` | Task types to claim, a subset of those registered; a value without `*` that is not registered is `400 QUERY_INVALID_FILTERS` (`dispatcher/ClaimFilterService.java:75-85`) |
| `task` | Tasks in their slug form: `<slug>` for a global task, `<workspace>/<slug>` for a workspace task, where `<workspace>` is the workspace's slug or id; a bare slug never matches a workspace task of the same name, and `*` applies to the slug half (`dispatcher/ClaimFilterService.java:87-128`) |
| `workflowLabel` | `key=value[,value]`, one key, matched on the workflow definition's labels (`dispatcher/ClaimFilterService.java:149-159`) |

`type`, `task` and the `workflowLabel` values are comma-separated lists in which `*` is the only wildcard, anchored at
both ends (`dispatcher/ClaimFilterService.java:190-195`). Filters are ANDed and values ORed, and termination orders
follow the same filters (`engine/TaskRunService.java:215-227`). Task slugs and labels resolve to ids cached for 30 s,
so a new task or a newly labelled workflow is claimable within 30 s (`dispatcher/ClaimFilterService.java:40`). A
poll without filters still claims work a filtered poll would; reserving work for a filtered dispatcher is not built.

## What a poll hands out

Each claim is a compare-and-set on one document, so two dispatchers never receive the same run
(`engine/TaskRunService.java:317-355`). A run goes out as the wire's own model, not the public one
(`dispatcher/model/DispatchTaskRun.java`, `dispatcher/model/DispatchWorkflowRun.java`); the SDK reads them as its
`TaskRun` and `WorkflowRun`:

| Model | Answers | Fields |
| --- | --- | --- |
| `DispatchTaskRun` | The task poll, start and end | `id`, `type`, `name`, `labels`, `timeout` (minutes; null or 0 is unguarded), `params`, `results` (the task's declared results), `workspace`, `spec`, `status`, `phase`, `taskRef` (the task's slug form, as the `task` filter takes it, resolved from the stored id once per answer; a task the catalogue no longer holds keeps its id, `dispatcher/DispatcherService.java:116-124`), `workflowRef`, `workflowRunRef`, `index` (on a for-each item) |
| `DispatchWorkflowRun` | The workflow poll and its start | `id`, `status`, `phase`, `workflowRef`, `labels`, `workspace` |

A task run in the answer is one of two orders (`sdk/model/TaskRun.java:37-48`):

| Phase and status | Order | The dispatcher |
| --- | --- | --- |
| `queued`, `ready` | Run it | MUST call start before running, and end when done |
| `completed`, `cancelled` or `timedout` | Terminate it | MUST stop any runtime work it holds for that id, and MUST NOT call end |

A terminate order comes for a task that ended cancelled or timed out while a dispatcher held it, and for a task the
engine has requeued for another attempt while a dispatcher may still hold its earlier work; claiming the order
releases the claim, so the next attempt can be claimed (`engine/TaskRunService.java:229-300`). A dispatcher with
nothing left to stop treats the order as done.

A run order carries its spec filled in at claim and its params already resolved, each with its `type`: absent means
`string`; `array` and `object` carry JSON values; `secret` marks a string whose value Flow never shows a consumer.
The value is in plain text on the claim; a dispatcher MUST keep it out of its own logs and MAY deliver it exactly as a
string (`parameters.md`, "Sensitive parameters"). The SDK reads an unknown type as `unknown`
(`sdk/model/ParamType.java:18-27`).

The engine fills in `$(params.x)`, `$(tasks.x.results.y)` and the other references in two places, at two times
(`parameters.md`):

| What | When | Stored |
| --- | --- | --- |
| The task run's own parameter values | At admission (`engine/ParameterManager.java:146`, called at `engine/TaskExecutionService.java:200-201`) | Yes: frozen by the admission compare-and-set (`engine/TaskRunService.java:362-378`), secret values encrypted |
| `spec.script`, `spec.command`, `spec.arguments`, `spec.envs` | When a dispatcher claims the task (`engine/ParameterManager.java:297`, called at `dispatcher/DispatcherService.java:451-471`) | No: the stored spec keeps its references; only the copy on the claim carries values |

So no secret value is written into a stored spec; the termination order, the start and end answers and every
consumer read carry the stored spec. Admission has checked every reference, so the fill-in fails only for a workspace
or global parameter deleted since, and the task then ends `failed` with reason `SpecFillFailed`
(`dispatcher/DispatcherService.java:460-470`). Any other failure while a claimed task is prepared ends it `failed`
with `DispatchPrepareFailed`, and the rest of the poll is still handed out, so no claim stays with a dispatcher that
never hears of it (`:425-444`).

The task run's params are the task's declared params merged with the values authored on the workflow node
(`engine/DAGUtility.java:273-276`). A `custom` task takes its runtime from its own params (`image`, `command` and
`arguments` split on newlines, and `shellScript`), not from the catalogue entry, which declares no image
(`:350-377`); a secret-typed one is copied as its reference `$(params.<name>)`, filled in at claim, so a secret
`command` or `arguments` arrives as one element (`:391-402`).

An item of a for-each task is an ordinary task run to the dispatcher. It carries the parent's params plus `item` (the
element, typed `object` when it is an object or array) and `index` (its position from 0, as text), resolved when the
item is admitted, and mounts the run's workspace as the parent does (`engine/TaskExecutionService.java:741-783`).

## Starting

`PUT /taskrun/{id}/start` carries `dispatcherRef` (the registered id) and answers with the `DispatchTaskRun`
(`engine/TaskRunService.java:941-991`); `labels`, `annotations`, `params` and a `timeout` it MAY carry are merged
while the task still waits to run (`:950-983`). The dispatcher MUST NOT run the task when the answer's phase is
`completed` (it was cancelled while being handed over) or when the engine answers 4xx; when the engine cannot be
reached it MAY run it, since the end is still fenced on the claim. The engine moves the task to running
asynchronously, so `queued` in the answer is normal (`engine/TaskExecutionService.java:274-313`).

## Ending

`PUT /taskrun/{id}/end` carries `dispatcherRef`, `status`, `statusReason`, `statusMessage` and `results`
(`engine/TaskRunService.java:993-1079`).

- `status` is `succeeded`, `failed` or `invalid`, and absent means `succeeded`; anything else is
  `400 TASKRUN_INVALID_END_STATUS` (`:1028-1036`).
- A request naming a dispatcher that no longer holds the claim is `409 TASKRUN_CLAIM_SUPERSEDED` and changes nothing,
  including an end that arrives after the engine requeued the attempt, whether or not the claim has been released
  for another attempt yet: a dispatcher holds a task only in `queued` or `running` (`:1186-1201`). A request without
  `dispatcherRef` is not fenced, so a dispatcher MUST send it.
- `results` SHOULD carry only the result names the task declares; the engine merges what arrives by name, and the
  caps below bound its size.
- `statusReason` is one of the engine's known reasons (`common/model/StatusReason.java`), of which the SDK's
  `StatusReason` is the subset a dispatcher sends (`StatusReasonTest`); any other value is an ordinary failure. Three
  mean the task never started: the engine requeues it with backoff, on the same budget of three more attempts as a
  timed-out claimant, and fails it with that reason once the budget is spent
  (`common/model/StatusReason.java:44-45`, `engine/TaskRunService.java:1088-1111`, `engine/EngineConstants.java:33-34`):

| Reason | Send when |
| --- | --- |
| `ExceededQuota` | The runtime refused to create the task's work for lack of capacity |
| `StartTimeout` | The task's work was created but never began within the dispatcher's start deadline |
| `RateLimited` | The runtime or a gateway behind it refused the task for rate, so it should be tried again later |

**Caps.** The params cap is the engine's, identical on every dispatcher. The results limit belongs to the dispatcher
that runs the task (`dispatcher-kubernetes.md`, "Results on Kubernetes"); the engine's results setting is only a
storage guard, so a faulty dispatcher cannot grow a task run toward MongoDB's 16 MB document limit.

| Cap | Property (`service-core/src/main/resources/application.properties:187-188`) | Where checked | Effect |
| --- | --- | --- | --- |
| Params | `flow.engine.task.params.max-bytes=16384` | Before admission (`engine/TaskExecutionService.java:211-226`) | The task ends `invalid` with a `PARAMS_TOO_LARGE` message and never becomes claimable |
| Results (storage guard) | `flow.engine.task.results.max-bytes=1048576` (1 MB) | In `TaskRunService.end` (`engine/TaskRunService.java:1037-1051`) | Status becomes `failed` with a `RESULTS_TOO_LARGE` message and `statusReason=ResultsTooLarge`; the oversize results are not persisted |

Each item of a for-each task is capped like any task; the parent's results are the items' values collected into
arrays by the engine, with no cap of their own (`engine/TaskExecutionService.java:792-837`). When MongoDB refuses the
combined arrays as over 16 MB, the parent fails with `statusReason=ResultsTooLarge` and no results (`:479-489`).

## Leases and heartbeat

A claim carries a lease from the moment it is made: the claim sets it 90 s ahead (`flow.dispatcher.lease-ms`,
`engine/TaskRunService.java:73-74`, `:333-339`). `PUT /{id}/heartbeat` lists the task run ids the dispatcher is still
working on, and the engine moves the lease of each one it still holds in phase `queued` or `running` 90 s ahead again
(`dispatcher/DispatcherService.java:213-224`, `engine/TaskRunService.java:622-634`). A dispatcher MUST heartbeat
every task it holds, from its claim until the engine has taken its end, at least every 30 s. A lease that lapses, or a
dispatcher with no poll or heartbeat for 60 s (`engine/WorkflowWatcher.java:74`), gets the task requeued or abandoned
(`execution-model.md`, "Timeouts and crash recovery"); the lease catches a dispatcher that crashes and comes back under
the same id within that minute, whose heartbeats no longer list the claims it lost.

## Logs

A task's log is read through core alone: `GET /api/v2/taskrun/{id}/log` authorizes the reader and scrubs the run's
secrets (`workflow/WorkflowRunService.java:517-553`), and reads from one of three sources
(`engine/TaskRunService.java:1200-1221`, `engine/LogClient.java:90-149`):

| Source | When |
| --- | --- |
| The stored log | The dispatcher uploaded one: `PUT /taskrun/{id}/log?dispatcherRef=<id>`, the log as `text/plain`, fenced on the claim like start and end (`engine/TaskRunService.java:1246-1263`). Core stores it as a log-scoped artifact of the run, kept for the "Task Log Retention" setting (7 days by default), and answers 204; a later upload for the same task run replaces it (`workflow/ArtifactService.java:394-421`; `data-model.md`, "Artifacts"). 409 `TASKRUN_CLAIM_SUPERSEDED`: another dispatcher holds the claim. 413 `TASKRUN_LOG_TOO_LARGE`: over `flow.engine.task.log.max-bytes` (10 MiB), refused whole, never truncated. 503 `ARTIFACTS_NOT_CONFIGURED`: core has no artifact store, which a dispatcher SHOULD take as final for its life. 503 `TASKRUN_LOGS_DISABLED`: the "Store Task Logs" setting is off, which MAY be switched back on, so a dispatcher SHOULD keep offering. |
| The dispatcher's live route | The claim's dispatcher registered `logsUrl`: core calls it with `workflowRef`, `workflowRunRef` and `taskRunRef` as query parameters and `Authorization: Bearer <logsToken>`, and relays the stream (`engine/LogClient.java:63-79`). What answers there (the pod, Loki) is the dispatcher's own. |
| A message | Neither answers: a 200 whose body says no log is available (`engine/LogClient.java:43-45`). Nothing on core names a dispatcher; a dispatcher that wants its running tasks followed registers a `logsUrl`. |

While the task runs, the live route answers; a log stored during this attempt, after the current claim was made
(`engine/TaskRunService.java:1225-1230`), answers instead when the claim's dispatcher registered no `logsUrl` or its
route fails before answering (`engine/LogClient.java:131-138`). So a mid-run snapshot never hides the live stream, a
requeued task never serves its earlier attempt's log, and a stream that fails part way is not restarted from the
stored copy. Once the task has ended, the stored log answers; without one, the live route.

A dispatcher SHOULD upload before it reports the end, so the log is in place when the task shows as finished, and MAY
both serve live and upload; Flow's Kubernetes dispatcher does both (`dispatcher-kubernetes.md`, "Flow's dispatcher").

A live route MUST write each line as the task writes it and get it onto the network, never collect the body first.
Spring 7 does not pass a `StreamingResponseBody`'s `flush()` to the network, so core's log route flushes the servlet
response instead (`common/util/ResponseFlushingOutputStream.java`, used at `workflow/TaskRunControllerV2.java:57`).
Core flushes every relayed read (`engine/LogClient.java:166-176`) and its secret scrub holds back only an unfinished
line (`common/util/FilterValuesOutputStream.java:14-21`). A stream stays open for up to two hours
(`service-core/src/main/resources/application.properties:72`); after 10 minutes with nothing from the dispatcher,
core's read gives up (`core/config/RestConfig.java:53`) and the stream ends with a line saying it stopped following
(`engine/LogClient.java:47-49`, `:123-130`).

## Trace context

Flow stores no traces; a task and a dispatcher export their own OpenTelemetry spans to the operator's backend. So
that every task of one run lands in one trace, a dispatcher SHOULD derive a W3C `traceparent` for each task run and
hand it to the task as `TRACEPARENT`; an in-process handler uses it as the parent context of its spans, which the
SDK's runner does for it when the host gives the builder an `ObservationRegistry` ("SDK").

The version is `00`; the trace id is `00000000` followed by the run's `workflowRunRef`, 32 lowercase hex; the parent id
is the last 16 hex of the task run's `id`; the flags are `01` (sampled). A task run `6ad1bfa6cfefa66e88d5e2e0` of run
`6ad1bfa3cfefa66e88d5e24a` gets `00-000000006ad1bfa3cfefa66e88d5e24a-cfefa66e88d5e2e0-01`. It is computed, not
carried on the wire; the SDK derives it, and gives none when either id is not 24 hex characters
(`sdk/model/TaskRun.java:55-63`). A retry is a new run, so it is its own trace, linked to its origin through the run's
`initiatedByRef`.

## Storage

`GET /{id}/workflows` hands out workflow runs that declare a workspace, claimed for provisioning
(`engine/WorkflowRunStateHelper.java:57-73`); the dispatcher provisions the run's one volume and calls
`PUT /workflowrun/{id}/start`. A provisioning failure is reported by not starting: the engine releases the claim after
a grace and fails the run after three attempts (`engine/WorkflowWatcher.java:61-62`). `POST /workspaces/releasable`
takes the run ids the dispatcher holds volumes for (500 at most, else 400) and answers with those whose run is
completed or gone (`dispatcher/DispatcherService.java:61`, `:498-531`). A dispatcher that runs nothing on shared
storage MAY skip both.

Shared storage is one workspace per run: a workflow declares a single `workspace` (`size`, `mountPath`,
`className`, `accessMode`; `common/model/WorkflowWorkspace.java:19-49`), presence is the switch, and the run copies it
at submit, the request's own `workspace` winning when it carries one (`workflow/WorkflowService.java:1820-1826`).
Every task mounts it unless its node says `workspace: false`, which the canvas cannot set, and the task run carries its
own copy with `mountPath` settled to the declared path or `/workspace` (`engine/DAGUtility.java:311-323`).

## Task types

Only `template`, `custom`, `script`, `generic`, `ai`, `uploadartifact` and `downloadartifact` tasks go to a
dispatcher: admission leaves them for a claim instead of executing them (`engine/TaskExecutionService.java:251-261`).
Every other type runs inside the engine; `TaskType` (`common/enums/TaskType.java:15-35`) is dispatched in
`engine/TaskExecutionService.java:358-414`.

| Type | Behaviour |
| --- | --- |
| `start`, `end` | Structural nodes of the graph; never executed |
| `template`, `custom`, `script`, `generic`, `ai` | Wait for a dispatcher |
| `uploadartifact`, `downloadartifact` | Wait for a dispatcher. The engine fills the link params when it hands the task out (`dispatcher/DispatcherService.java:307-328`), verifies an upload when it ends, ends an optional download of a missing artifact `succeeded` with `found=false` without dispatching it, and appends `found=true` to a dispatched download that succeeds (`engine/TaskRunService.java:1117-1148`; `data-model.md`, "Artifacts"). Flow's Kubernetes dispatcher runs the default worker's `artifact upload` or `artifact download`; an SDK dispatcher can use `ArtifactTransfer` ("SDK") |
| `decision` | Evaluates the branch and ends `succeeded`; a switch skipped or failed before it ran made no decision and takes no branch (`engine/DAGUtility.java:510-513`) |
| `acquirelock`, `releaselock` | Take or release a row in the `task_locks` collection; acquire parks as waiting until the lock is free |
| `runworkflow` | Submit a child workflow run with the node's params other than `workflowRef` and `wait` (`engine/TaskExecutionService.java:1199-1209`). Ends `succeeded` at once, or with `wait=true` parks as waiting and takes the child's terminal status and results; may run for each item, one child per item (`execution-model.md`, "Child workflows") |
| `runscheduledworkflow`, `setwfstatus`, `setwfproperty` | Schedule another workflow to run later, or write the run's status message or a workflow-scoped param, then end |
| `approval`, `manual` | Create an action and wait for a person. An approval waits for `numberOfApprovals` approvers (`numberOfApprovers` is still read; `engine/TaskExecutionService.java:1398-1409`) and ends with `approvalStatus`, `approvalDate`, `approvalUserName`, `approvalUserEmail` and `approvalComments` from the action that decided it (`workflow/ActionService.java:240-262`); a manual task ends with none |
| `eventwait`, `sleep` | Wait for a matching inbound event unless pre-approved, or park until the watcher completes it after the duration |

## Catalogue

Catalogue tasks are built from the `boomerang-io/tasks` monorepo into the `boomerangio/task-flow` image. The
migrations seed 89 tasks and their 134 revisions from `service-core/src/main/resources/seed/tasks.json` and
`task-revisions.json` (`migration/_0017__SeedTaskCatalogue.java`), and the two artifact tasks from their own change
unit (`migration/_0018__SeedArtifactTasks.java`). A seed inserts only what is absent, so a task or revision added to
the seed later needs a change unit of its own to reach an existing database (`data-model.md`, "Migrations"). A
`template` or `script` task without an image takes the `task` settings' `default.image` (seeded as
`boomerangio/task-flow:3.1.0`) when its task run is created (`engine/DAGUtility.java:327-332`).

A workflow node's task version is pinned when the workflow is saved, to the version it asked for or the latest
(`workflow/WorkflowService.java:1424-1425`), and the task run records it (`engine/DAGUtility.java:238-242`), so a new
task revision changes nothing for a saved workflow until it is saved again.

## AI tasks

An `ai` task calls an OpenAI-compatible endpoint. The node references one of two seeded catalogue tasks of type `ai`,
the engine treats it as any other dispatched type, and a dispatcher runs the image and command the revision declares:
`boomerangio/task-ai:2.1.0` at revision 2 and `2.0.0` at revision 1, with the command `prompt` or `agent`
(`dispatcher-kubernetes.md`, "AI tasks"; an in-process dispatcher ignores the image). Admission labels the task run
`boomerang.io/model` with its `model` param's value unless that param is secret
(`engine/TaskExecutionService.java:228-238`). Everything the model call needs is a declared param:

| Task | Params | Results |
| --- | --- | --- |
| `ai-prompt`, one completion | 17: `model` (required), `prompt` or `promptFile`, `systemPrompt`, `messages` or `messagesFile` (an exact message array replacing the prompts), `endpoint` and `token` (optional; the executor's defaults apply when absent), `temperature`, `maxTokens`, `seed`, `parameters` (an open JSON map passed through, winning on conflict), `responseFormat`, `jsonSchema`, `files`, `maxContextBytes`, `outputFile` (the full reply written to the run workspace); revision 2 adds `images` (run-workspace paths or https URLs, sent as image content parts), `imageDetail` and `maxImageBytes`, 20 in all | 9: `output`, `finishReason`, `model`, `promptTokens`, `completionTokens`, `totalTokens`, `reasoningTokens`, `cachedPromptTokens`, `cost` (the last three empty when the endpoint does not report them) |
| `ai-agent`, a tool-calling loop | The 17 plus `tools`, `mcpServers`, `mcpTokens` (password-typed, the only place a tool credential travels), `allowedTools`, `toolContext`, `maxTurns`, `maxToolCalls`, `parallelToolCalls`, `toolResultReinforcement`; 26 in all, 29 at revision 2 with the same three image params | The 9 plus `turns` and `toolCalls` |

Revision 1 of each stays seeded beside revision 2, so a workflow pinned to it keeps running unchanged. A workspace
image over `maxImageBytes` fails the task with `FILE_TOO_LARGE`; a JSON-valued param that does not parse fails it with
`INVALID_PARAM`. Results fall under every task's caps ("Ending"), so a long `output` fails the task with
`ResultsTooLarge` rather than truncating; `outputFile` and the task log carry what does not fit. The seed entries copy
the definition files in the `boomerang-io/tasks` repository (`tasks/ai/catalog/ai-prompt.json`, `ai-agent.json`), the
source of the contract (`migration/_0023__SeedAiTaskFamily.java`); each revision pins the exact worker image, and a
deployment moves to a newer worker by seeding a new revision. `token` is password-typed, so it is a `secret` param:
masked on reads and scrubbed from the log stream (`parameters.md`, "Sensitive parameters"), but a plain `PARAM_TOKEN`
environment variable on a Kubernetes pod, readable by anyone who can read the pod spec.

## Errors

Every error is the engine's standard body (`CLAUDE.md`, "API errors") except one: the authentication filter's bare
401. A registration without a host is `400 DISPATCHER_INVALID_REQ` (`dispatcher/DispatcherService.java:139-141`). A
poll or heartbeat under an id the engine does not hold is `404 DISPATCHER_NOT_REGISTERED` (`:214-216`, `:244-246`,
`:346-348`): nothing deletes a registration, so the id goes unknown when the database is reset under a running
dispatcher. The SDK registers again on that 404 and makes the call once more under the new id, with the registration
backoff; no other error re-registers (`sdk/Dispatcher.java:211-240`).

## SDK

`dispatcher-sdk` is this protocol as a Java library, so a dispatcher author writes only the work; Flow's Kubernetes
dispatcher is built on it. It depends on `spring-web` and Jackson only (Micrometer Observation arrives with
`spring-web`; SLF4J and logback are optional, for task log capture), owns its wire models (`sdk/model/`; unknown
fields ignored, an unknown enum value read as `unknown`), and its `DispatcherContractTest` fails when anything in
`contracts/dispatcher-v1.yaml` is neither modelled nor listed as deliberately not.

| Class | Gives the author |
| --- | --- |
| `DispatcherClient` | One method per route over the host's `RestClient`, keeping its proxy, certificates and timeouts; the token on engine calls only (`sdk/DispatcherClient.java:56-64`) |
| `Dispatcher` | Registration retried with backoff until the engine answers (`sdk/Dispatcher.java:242-277`), and again when a poll or heartbeat is answered `404 DISPATCHER_NOT_REGISTERED` (`:211-240`), with `logs(url, token)` registering the live log route and its bearer and `logsToken()` handing the bearer to the host's own check (`Builder.randomLogsToken()` makes one); one work poll per task pool with the pacing above, `limit` set to the pool's free capacity on every poll, and optional filters, plus a `limit=0` poll while the pool is full (`sdk/Dispatcher.java:240-290`); the workflow queue only when a `WorkflowHandler` is given; one heartbeat every 30 s listing every task in flight; a stop that stops the polls, has every runner refuse the claims still arriving, then shuts the executor down and drains for 10 s (`sdk/Dispatcher.java:133-166`) |
| `TaskRunner` | A claim taken into flight and handed to the executor as one step against the stop, under a read lock the stop takes for writing, so a claim is either drained or refused before it is taken, never registered and dropped (`sdk/TaskRunner.java:102-139`); the start answer honoured; the end retried with backoff, 1 s doubling to 30 s, until the engine takes it or refuses it with a 4xx other than 408 or 429, the task held in flight and its lease renewed meanwhile; terminate orders signalled to the running task, with no end (`sdk/TaskRunner.java:194-257`). Around every handler call (`:211-239`): an observation named `flow.task`, started before the handler and stopped after it with the handler's exception as its error, carrying the task-run id, task name and workflow run id as low-cardinality keys and the task's `traceparent` as a receiver carrier, so the registry's tracing handler parents it on the run's trace and the handler's own spans join it (`:244-254`); with no registry on the builder it is a no-op. With a `TaskLogCapture` on the builder, the handler's thread carries the task-run id in the SLF4J MDC under `taskRunId` for the handler's duration, and the captured log is uploaded through `LogUploader` as the handler returns, before the end is reported and replacing any log the handler uploaded itself (`:237`), and every `logUploadInterval` while it runs, 30 s by default, zero for only at the end (`:256-268`) |
| `TaskHandler`, `TaskContext` | The interface the author implements: `run(task, context)` returns a `TaskResult` or throws a `TaskFailure` with one of `StatusReason`'s reasons (`TaskFailure.exceededQuota`, `startTimeout` and `rateLimited` for the requeued ones); `cancel(task)` stops work for a terminate order. The context carries the cancellation signal, the artifact transfer, and `uploadLog`, which stores the finished log with the engine best effort and stops asking an engine that answered it keeps none (`sdk/LogUploader.java:41-67`) |
| `TaskLogCapture` | One buffer per task run, fed by the host's log framework through `append(taskRunId, line)` with the id each event carries in its MDC (`sdk/TaskLogCapture.java:52-57`); the first `headBytes` and the last `tailBytes` are kept, 64 KB each by default, with one line between them saying how many bytes were dropped (`:96-120`); `snapshot(taskRunId)` is the log so far, for a host that serves a live log route. `logback.TaskLogAppender` is the adapter for logback: `attach(capture)` adds it to the root logger (`sdk/logback/TaskLogAppender.java:31-38`); a log4j2 host writes the equivalent three-line appender. SLF4J and logback are optional dependencies of the SDK, so a dispatcher that captures nothing carries neither |
| `ArtifactTransfer` | The `uploadartifact` / `downloadartifact` transfer for a dispatcher without the worker image: PUT or GET on the link params filled at claim, the download verified against `sha256` (`sdk/ArtifactTransfer.java:83-131`) |

It is the only Flow library published: to GitHub Packages as `io.boomerang:dispatcher-sdk`, versioned with the product
tag (`https://maven.pkg.github.com/boomerang-io/flow`; `.github/workflows/ci-release.yml:96`, job `publish-sdk`).
Reading it needs a GitHub token with `read:packages`.

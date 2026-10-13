# Dispatcher contract

A dispatcher is any process that takes task runs from the engine, runs them, and reports how they ended:
Flow's own Kubernetes dispatcher (`service-dispatcher`), an in-process dispatcher inside a host product, or a
future serverless one. They all speak one protocol under `/api/v1/dispatcher`. Its routes and wire models are
generated from the engine as an OpenAPI document — served at `/api/docs/spec/dispatcher-v1` and checked in at
`contracts/dispatcher-v1.yaml` (`dispatcher/DispatcherContractConfiguration.java`); `DispatcherContractTest`
fails when the two differ. This document gives the semantics the document cannot. Paths below are under
`service-core/src/main/java/io/boomerang/` unless they start with a module name.

## Versioning and compatibility

The protocol carries its own version, `1`, apart from the product version.

- An additive change — a new optional query parameter, a new response field, a new end reason, a new route —
  keeps the protocol at `1` and is listed under "Changes" below.
- A dispatcher MUST ignore response fields it does not know, and MUST NOT rely on the order of fields or of
  list entries unless this document states one.
- The engine MUST accept a request that omits a field added later, behaving as it did before that field
  existed, and MUST treat an end reason it does not know as an ordinary failure.
- A change that removes or redefines a field, a route or a status meaning MUST ship as `/api/v2/dispatcher`,
  with `/api/v1/dispatcher` served beside it for at least one minor product release.

## Authentication

Every call carries `Authorization: Bearer <token>`: a global Flow token with an actor kind — a machine token
(`dispatcher/DispatcherAuthFilter.java:79-105`, `core/TokenService.java:433-438`). Anything else is a bare
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
| Report the outcome | `PUT /taskrun/{id}/end` | the task run |
| Release storage | `POST /workspaces/releasable` | which held volumes may go |

(`dispatcher/DispatcherControllerV1.java:64-205`)

## Registration

`POST /register` takes `name`, `host`, `version` and `taskTypes`. The record is upserted on name and host, so a
restarting dispatcher keeps its id (`dispatcher/DispatcherService.java:99-126`). A dispatcher registered for
no task types is answered 204 on every task poll (`:274`).

## Polling

Both queue routes are long polls. The engine holds the request for up to 30 s, looks for claimable work every
second, and answers as soon as it has claimed something — at most a page of 20 — or 204 when the window passes
(`DispatcherService.java:48-50`, `:291-337`). A dispatcher's client read timeout MUST exceed 30 s.

A dispatcher SHOULD poll again as soon as a poll returns runs or was held for its window, and SHOULD wait about
5 s after a poll that failed or was answered at once with nothing — claiming switched off
(`flow.queue.enabled=false`) and a filter that matches nothing both answer that way. Flow's dispatcher does
exactly this (`service-dispatcher/.../client/EngineClient.java:29-36`).

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
- Results over `flow.engine.task.results.max-bytes` (1 MiB) fail the task as `ResultsTooLarge` (`:53`).
- `statusReason` is a closed set (`lib-common/.../model/TaskRunEndRequest.java`). Two of them mean the task
  never started, and the engine requeues it instead of failing it — with backoff, on the same budget of three
  more attempts as a timed-out claimant — and fails it with that reason only once the budget is spent
  (`engine/TaskRunService.java:62`, `:1006`):

| Reason | Send when |
| --- | --- |
| `ExceededQuota` | The runtime refused to create the task's work for lack of capacity |
| `StartTimeout` | The task's work was created but never began within the dispatcher's start deadline |

## Leases and heartbeat

A claim has no lease until the dispatcher's first heartbeat for it. `PUT /{id}/heartbeat` lists the task run
ids the dispatcher is still working on; the engine sets each owned one's lease 90 s ahead
(`flow.dispatcher.lease-ms`, `DispatcherService.java:62`, `:138-151`). A dispatcher SHOULD beat every 30 s for any
task that can outlive a minute. A lease that lapses, or a dispatcher with no poll or heartbeat for 60 s
(`engine/WorkflowWatcher.java:76`), gets the task requeued or abandoned (`execution-model.md`).

## Storage

`GET /{id}/workflows` hands out workflow runs that declare workspaces, claimed for provisioning; the dispatcher
provisions their volumes and calls `PUT /workflowrun/{id}/start`. A provisioning failure is reported by not
starting: the engine releases the claim after a grace and fails the run after three attempts.
`POST /workspaces/releasable` takes the run and workflow ids the dispatcher holds volumes for (500 of each at
most, else 400) and answers with those whose owner is finished (`DispatcherService.java:54`, `:367`). A
dispatcher that runs nothing on shared storage MAY skip both.

## Errors

Every error body is the engine's standard shape — `timestamp`, `code`, `reason`, `message`, `status` — except
the authentication filter's bare 401 (`CLAUDE.md`, "API errors").

## Changes

| Change | Kind |
| --- | --- |
| `limit` on the task poll; `ExceededQuota` and `StartTimeout` end reasons; the start answer's `completed` means do not run | Additive (flow#500) |
| `type`, `task` and `workflowLabel` task-poll filters; termination orders follow them | Additive (flow#502) |

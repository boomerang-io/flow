# 0112 — A task log is served from its stored copy first, then from the route its dispatcher registered

**Status:** accepted · **Date:** 2026-10-15

## Context

The engine read every task log from one configured dispatcher URL, with no authentication, from a pod that is
deleted after seven days. With several dispatcher shapes (decision 0105) — some with no HTTP surface, some gone
before anyone reads — that path serves one of them. IBM reads logs from Loki behind the dispatcher's log route
and must keep doing so.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Route by the claim: core looks up the claim's dispatcher and calls its host | Several Kubernetes dispatchers | Still unauthenticated; nothing for an in-process or short-lived dispatcher; the log dies with the pod |
| B. Dispatchers push logs to core; core reads a log backend itself for the rest | Every shape, once core knows each backend | Core would need the pod labels and Loki filters only the dispatcher has |
| C. Two optional contract pieces: a dispatcher registers where its live logs are read and the bearer to present, and may upload the finished log, which core stores and serves first | Every shape; Loki stays behind the dispatcher that knows the labels | Stored logs need the artifact store; a live read still crosses two services |

## Decision

C. At registration a dispatcher sends `logsUrl` and `logsToken`; before its end it may `PUT
/dispatcher/v1/taskrun/{id}/log`, fenced on the claim and capped by `flow.engine.task.log.max-bytes`
(`dispatcher/DispatcherControllerV1.java`, `engine/TaskRunService.java` `storeLog`). Core stores the log as a
log-scoped artifact of the run (`workflow/ArtifactService.java` `storeTaskLog`) and serves a task's log from
the first of: the stored copy, the registered route with the registered bearer, a message
(`engine/TaskRunService.java` `streamLog`, `engine/LogClient.java` `resolve`); the configured dispatcher URL
core used to hold is gone, so nothing on core names a dispatcher. Core stays the only
reader the webapp talks to, so authorization and secret scrubbing stay in one place. Flow's Kubernetes
dispatcher generates the bearer at every start, accepts nothing else on its log route
(`service-dispatcher/.../dispatcher/LogAuthFilter.java`), and uploads each finished pod log
(`dispatcher/TaskService.java`).

## Consequences

- A finished log outlives the pod and needs no dispatcher up, where core has an artifact store; without one
  the live path serves as before, and a dispatcher that is told so stops uploading.
- A core older than this change cannot read a newer dispatcher's logs during a version skew: the route now
  refuses an unauthenticated read. One tag ships both images.
- `log` is a third artifact scope: unlisted, uncounted toward storage, unreadable through the artifact
  routes, with its own switch and retention in the Artifacts settings and the artifacts' expiry sweep.

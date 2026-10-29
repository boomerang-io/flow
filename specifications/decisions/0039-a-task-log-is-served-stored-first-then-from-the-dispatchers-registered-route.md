# 0039 — A task log is served from its stored copy or from the route its dispatcher registered

**Status:** accepted · **Date:** 2026-10-15

## Context

Dispatchers come in several shapes (decision 0037): some have no HTTP surface, some are gone before anyone reads a
log, and a pod log disappears with the pod. One operator reads logs from Loki behind the dispatcher's log route, which
knows the pod labels and filters, and must keep doing so.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Core calls the host of the dispatcher holding the claim | Several Kubernetes dispatchers | Unauthenticated; nothing for an in-process or short-lived dispatcher; the log dies with the pod |
| B. Dispatchers push every log; core reads a log backend itself for the rest | Core knows each backend | Core would need the pod labels and Loki filters only the dispatcher has |
| C. Two optional contract pieces: a registered live-log route and bearer, and an upload of the finished log that core stores | Every shape; Loki stays behind the dispatcher | Stored logs need the artifact store; a live read still crosses two services |

## Decision

C. A dispatcher registers `logsUrl` and `logsToken` (`dispatcher/DispatcherService.java:152-153`) and may
`PUT /dispatcher/v1/taskrun/{id}/log`, fenced on the claim and capped by `flow.engine.task.log.max-bytes`, 10 MiB
(`dispatcher/DispatcherControllerV1.java:223-258`, `engine/TaskRunService.java:1246-1262`). Core stores it as a
`log`-scoped artifact of the run (`workflow/ArtifactService.java:395-421`). A finished task's log comes from the stored
copy, else the registered route with its bearer, else a message; a running task's comes from the route, else a copy
stored during this attempt (`engine/TaskRunService.java:1200-1222`, `engine/LogClient.java:63-79`). Core stays the
only reader the webapp talks to, so authorization and secret scrubbing stay in one place. Flow's Kubernetes dispatcher
generates its bearer at every start (`service-dispatcher/.../config/DispatcherConfig.java:57`), accepts nothing else
on its log route (`service-dispatcher/.../dispatcher/LogAuthFilter.java:17-24`), and uploads each finished pod log
(`service-dispatcher/.../dispatcher/TaskService.java:176-186`).

## Consequences

- A finished log outlives the pod and needs no dispatcher up, where core has an artifact store; without one, the
  software development kit stops uploading for the dispatcher's life
  (`dispatcher-sdk/src/main/java/io/boomerang/dispatcher/sdk/LogUploader.java:49-55`).
- A core that sends no bearer cannot read a dispatcher's live logs, as the route refuses an unauthenticated read; one
  product tag ships both images.
- A stored log is unlisted, uncounted toward storage and unreadable through the artifact routes, with its own switch
  and retention in the Artifacts settings (`logs.enabled`, `logs.retention.days`).

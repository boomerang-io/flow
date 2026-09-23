# 0076 — A Docker executor runs tasks on one host, for the no-Kubernetes quickstart

**Status:** accepted · **Date:** 2026-09-15

## Context

Both shipped executors need a Kubernetes cluster, so trying Flow meant installing one (and, for the default,
Tekton on top). The local stack could prove everything up to `ready` and nothing beyond it, because no
dispatcher could run. Every task Flow actually runs is one image to completion with environment variables,
optional volumes, a timeout and a small result — which is what a Docker daemon already does.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Kubernetes only | Every evaluator already runs a cluster | Trying the product costs a cluster install; the local stack can never show a run finish |
| B. Docker-in-Docker inside the dispatcher | Task containers must be invisible to the host | A privileged container, a second daemon, its own image cache and storage — heavy, and slower for zero gain locally |
| C. A third `TaskExecutor` driving the host's Docker daemon | One host is enough and the interface already fits | The dispatcher needs the host socket, which is root-equivalent on that host |

## Decision

C. `dispatcher.executor=docker` selects `DockerExecutor` (`service-dispatcher/.../docker/DockerExecutor.java:61`),
which creates, watches, cancels and deletes one container per task over the Docker Engine API, and
`docker-compose.docker.yml` mounts `/var/run/docker.sock` so task containers start as siblings of the
dispatcher. Being able to run the whole product end to end with `docker compose` and nothing else decided it;
the socket's blast radius is acceptable for a local quickstart and is not a deployment posture.

## Consequences

- The product runs end to end with no Kubernetes; a run reaches `succeeded` on a laptop.
- Workspace storage becomes a runtime concern: `WorkspaceStore` (`dispatcher/WorkspaceStore.java:14`) backs a
  workspace with a claim or a labelled Docker volume, and one reconcile path releases either.
- Docker enforces no deadline, so the executor stops the container at the task's timeout itself.
- Placement and isolation settings — runtime class, node selector, tolerations, host aliases — have no meaning
  here, and neither do a workspace's size, class or access mode.
- Azure Container Apps was considered alongside this and deferred; revisit when a hosted runtime is asked for.

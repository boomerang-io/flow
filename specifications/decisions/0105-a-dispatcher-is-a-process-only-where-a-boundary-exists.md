# 0105 — A dispatcher is the contract plus an SDK, and a separate process only where a boundary exists

**Status:** accepted · **Date:** 2026-10-10

## Context

Five executor shapes are wanted: Kubernetes Jobs and Tekton, Docker for local use, serverless containers, a
warm pool of microVMs each taking one task, and an in-process executor inside a host product (ARCHIE is both
caller and executor). The question was whether execution folds back into `service-core` or stays a separate
deployable. Cheer adopted Flow precisely to move untrusted repository content out of its system of record;
ARCHIE's tasks are model calls that need no container and no second deployable.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Fold execution into `service-core` | One deployment is the only one that will ever exist | Core holds cluster credentials or the Docker socket; Cheer's trust boundary disappears; one failure domain for API and execution |
| B. One worker image per executor | Every executor is a long-lived daemon on infrastructure core cannot reach | A daemon where no boundary exists is an extra hop and an upgrade for nothing — Istio deleted Mixer and merged its control plane into one binary for this reason; ARCHIE would run a second deployable to make model calls |
| C. One contract, one SDK, and a process only where a boundary exists | All five shapes and all three products | Two copies of the wire models kept in step by the contract (decision 0102); topology is a judgement reviewers make per executor |

## Decision

C. The claim-based protocol at `/dispatcher/v1` (`contracts/dispatcher-v1.yaml`, `dispatcher-contract.md`)
is the boundary that matters; the `service-dispatcher` process is one implementation of it. The protocol half
of that module is `dispatcher-sdk`, with no Kubernetes dependency. A dispatcher gets its own process when it
needs credentials core must not hold, runs in another network zone, isolates untrusted content, or places work
on infrastructure core cannot reach. Kubernetes, serverless and microVM dispatchers pass that test; a Docker
dispatcher (development only) and an in-process one do not. Durable-execution systems and agent sandboxes
converged on the same shape — a consolidated server, workers as an embedded SDK that long-polls, sends its
capacity, heartbeats and is fenced on a lease — so the contract, not the process, is the asset.

## Consequences

- `service-core` never gains Kubernetes, Tekton or Docker dependencies; the task types the engine already runs
  itself (`task-runtime.md`, "Task types handled inside the engine") remain the only execution inside it.
- Core reaches a dispatcher only through what it registered: the log route and bearer (decision 0112,
  `engine/LogClient.java:28-29,55`); nothing on core names a dispatcher.
- Ephemeral dispatchers need registration expiry and a queue-depth read for autoscalers. Both are additive v1
  changes, built when a pool or serverless dispatcher is built, not before.

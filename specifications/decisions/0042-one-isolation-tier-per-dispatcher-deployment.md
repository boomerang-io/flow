# 0042 — One isolation tier per dispatcher deployment, no per-task tier

**Status:** accepted · **Date:** 2026-08-25

## Context

Some deployments need tasks sandboxed by gVisor, Kata or confidential containers, and Kubernetes exposes
that as the pod's `runtimeClassName`. The open question was whether a workflow author picks an isolation
tier per task, which would add a field to the task spec and a routing rule in the queue, or whether the
operator picks it once for the deployment.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A per-task `isolation` field, routed to a matching dispatcher | Mixed trust tiers behind one dispatcher | New spec field, new queue routing, and an author can request a tier the cluster lacks |
| B. One `runtimeClassName` per dispatcher deployment | Trust tiers map to deployments | A second tier means a second dispatcher deployment |

## Decision

B. `dispatcher.tasks.runtimeClassName` is applied to every task the deployment runs — on the pod spec by
the Jobs executor (`service-dispatcher/src/main/java/io/boomerang/kube/KubeJobsExecutor.java:172-173`)
and on the TaskRun `podTemplate` by the Tekton executor (`kube/TektonServiceImpl.java:467-470`). A
deployment that needs a different tier registers as a separate dispatcher with its own name and task types,
and the engine already routes claims by registered task type
(`service-core/src/main/java/io/boomerang/dispatcher/DispatcherService.java:207`). Not adding a field or a
routing rule ahead of a real mixed-tier deployment decided it.

## Consequences

- No data-model change; isolation is an operator setting next to node selector and tolerations.
- Revisit only if one deployment genuinely needs mixed trust tiers behind one dispatcher.

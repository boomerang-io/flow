# 0078 — An `ai` task is a first-class type executed as a pod from a Flow-shipped worker image

**Status:** accepted · **Date:** 2026-09-16

## Context

Calling a language model is now an ordinary step in an automation, but Flow had no way to express one: an
author had to build and publish a container that held an API token, parsed a prompt and wrote results. The
call itself is the same everywhere — an OpenAI-compatible endpoint, a model, a prompt, a token — so the work
is declaring it, not packaging it. The question was where the call runs.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The dispatcher makes the HTTP call itself (n8n, Langflow, Dify) | One node type, no extra image, results immediately | Untrusted prompt content and a customer API token share the dispatcher process; an AI call blocks a dispatcher thread for minutes; egress from the dispatcher pod |
| B. The author builds a container (Argo Workflows, Tekton) | The team already ships images | Every author reimplements the same call; the token handling is theirs to get wrong; nothing to render in the canvas |
| C. A declared type whose image the platform resolves (Kubeflow) | The call is uniform and the platform owns the runtime | One more image to build and version; the type must join the dispatched set everywhere it is enumerated |

## Decision

C. `ai` joins `template`, `custom`, `script` and `generic` as a dispatched type
(`service-core/src/main/java/io/boomerang/engine/TaskExecutionService.java:208-212,340`), the seeded `ai`
catalogue task declares no image, and the dispatcher resolves `flow.dispatcher.ai.image` for the type. Two
things decided it: untrusted prompt content must not be parsed in the same process that holds the
dispatcher's engine credential, and a dispatcher registered with `taskTypes=[ai]` is by itself the egress
zone — one deployment, one network policy, no new concept (decision 0042).

## Consequences

- An author declares eleven params and gets a model call; the canvas renders them from the seeded revision.
- `token` is password-typed, so it is blanked and scrubbed upward (decision 0043), but it still reaches the
  pod as a plain `PARAM_TOKEN` environment variable. Per-task secrets would close that; revisit when a
  customer needs the token withheld from anyone who can read the pod spec.
- `output` is an ordinary result under the 4 KB cap (decision 0041), so a long response fails the task with
  `RESULTS_TOO_LARGE`. Revisit with the artefact store (decision 0045), not by raising the cap.
- Token usage is six flat results, not an entity field: a platform meters by summing `totalTokens` over task
  runs through the existing query API.

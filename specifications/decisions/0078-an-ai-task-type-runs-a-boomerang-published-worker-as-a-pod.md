# 0078 — An `ai` task is a first-class type executed as a pod from a Boomerang-published worker image

**Status:** accepted · **Date:** 2026-09-16

## Context

Calling a language model is now an ordinary step in an automation, but Flow had no way to express one: an author
had to build and publish a container that held an API token, parsed a prompt and wrote results. The call is the
same everywhere (endpoint, model, prompt, token), so the work is declaring it, not packaging it: where does it run?

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The dispatcher makes the HTTP call itself (n8n, Langflow, Dify) | One node type, no extra image, results immediately | Untrusted prompt content and a customer API token share the dispatcher process; an AI call blocks a dispatcher thread for minutes; egress from the dispatcher pod |
| B. The author builds a container (Argo Workflows, Tekton) | The team already ships images | Every author reimplements the same call; the token handling is theirs to get wrong; nothing to render in the canvas |
| C. A declared type whose image the platform resolves (Kubeflow) | The call is uniform and the platform owns the runtime | One more image to build and version; the type must join the dispatched set everywhere it is enumerated |

## Decision

C. `ai` joins `template`, `custom`, `script` and `generic` as a dispatched type
(`engine/TaskExecutionService.java:243,373`); the dispatcher runs the image and command the `ai` revision declares,
like any other type, and each seeded revision (`ai-prompt`, `ai-agent`) pins its exact `boomerangio/task-ai` tag, released
from `boomerang-io/tasks`, not a product image (decision 0006 stands;
`service-dispatcher/.../executor/TaskImageResolver.java:125-143`). Two things
decided it: untrusted prompt content must not be parsed in the same process that holds the dispatcher's engine
credential, and a dispatcher registered with `taskTypes=[ai]` is by itself the egress zone — one deployment, one
network policy, no new concept (decision 0042). The zone holds because a declared image must be under an allowed
prefix (`flow.dispatcher.ai.image-prefixes`, default the Boomerang worker); any other is refused at dispatch.

## Consequences

- An author declares eleven params and gets a model call; the canvas renders them from the seeded revision.
- `token` is password-typed, so it is blanked and scrubbed upward when declared as a workflow param and
  referenced from the node — a literal typed into the node is not, because the filter's type authority is the
  workflow revision's param spec (decision 0043, true of all 84 catalogue password params). Downward it reaches
  the pod as a plain `PARAM_TOKEN` variable; per-task secrets would close both, revisit when a customer needs it.
- `output` is an ordinary result under the results caps (decision 0041; 4 KB on Kubernetes), so a long response
  fails the task with `RESULTS_TOO_LARGE`. A long response belongs in an artifact (decision 0085), not in a raised cap.
- Token usage is six flat results, not an entity field: a platform meters by summing `totalTokens` over task runs.

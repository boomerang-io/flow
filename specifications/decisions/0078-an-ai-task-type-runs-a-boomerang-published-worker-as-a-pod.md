# 0078 — An `ai` task is a first-class type executed as a pod from a Boomerang-published worker image

**Status:** accepted · **Date:** 2026-09-16

## Context

Calling a language model is an ordinary step in an automation, and the call is the same everywhere: endpoint, model,
prompt, token. Without a declared type an author builds and publishes a container that holds a token, parses a prompt
and writes results. The question is where the call runs.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The dispatcher makes the HTTP call itself (n8n, Langflow, Dify) | One node type, no extra image | Untrusted prompt content and a customer token share the dispatcher process; a call blocks a dispatcher thread for minutes |
| B. The author builds a container (Argo Workflows, Tekton) | The team already ships images | Every author reimplements the call and its token handling; nothing for the canvas to render |
| C. A declared type whose image the platform publishes (Kubeflow) | The call is uniform and the platform owns the runtime | One more image to build and version; the type joins the dispatched set everywhere it is listed |

## Decision

C. `ai` is dispatched like `template`, `custom`, `script` and `generic` (`engine/TaskExecutionService.java:252-258`); the
dispatcher runs the image and command the revision declares and never a script
(`service-dispatcher/.../executor/TaskImageResolver.java:124-151`). The seeded `ai-prompt` and `ai-agent` revisions pin
exact `boomerangio/task-ai` tags, released from `boomerang-io/tasks` rather than with the product
(`service-core/src/main/resources/seed/task-revisions.json`). Two things decided it: untrusted prompt content must not
be parsed in the process that holds the dispatcher's engine credential, and a dispatcher registered for `ai` alone is
by itself the egress zone (decision 0042). The zone holds because a declared image must start with
`flow.dispatcher.ai.image-prefixes`, default `boomerangio/task-ai`, or the task is refused before anything is created
(`TaskImageResolver.java:71-77`, `service-dispatcher/.../dispatcher/TaskService.java:127-132`).

## Consequences

- An author fills the revision's params (20 on `ai-prompt`, 29 on `ai-agent`) and the canvas renders them. The
  dispatcher may supply the endpoint and token itself (`OPENAI_BASE_URL`, `OPENAI_API_KEY`; `TaskImageResolver.java:87-110`).
- `token` is password-typed, so a value on the node or a workflow param is stamped secret, encrypted at rest and masked
  on reads (decision 0043, `workflow/WorkflowService.java:1645-1651`); it reaches the pod as a plain `PARAM_TOKEN`.
- `output` is an ordinary result under the results caps (4 KB on Kubernetes), so a long response fails with
  `RESULTS_TOO_LARGE`; it belongs in an artifact (decision 0067). Usage is flat results (`promptTokens` to `cost`)
  that a platform meters by summing over task runs.

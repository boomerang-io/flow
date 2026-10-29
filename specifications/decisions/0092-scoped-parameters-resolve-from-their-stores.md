# 0092 — Scoped parameters resolve from their stores, inserted text is never expanded, and a lone reference reads as written

**Status:** accepted · **Date:** 2026-10-19

## Context

`$(global.params.x)`, `$(workspace.params.x)` and `$(context.params.x)` need values at run time without putting
every secret on every run document or making engine input a `boomerang.io/*` annotation. Some inserted text is
untrusted (task results, webhook payloads), and a definition read must show where a secret comes from, not the secret.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Values: read the layers from their stores at run start and at each admission | Live values are acceptable within a run | An edit reaches tasks not yet admitted |
| Values: resolve scoped references into every task run at run start | Every task must see one set of values | Resolved secrets on task runs that never execute; one write per task at start |
| Definition reads: mask a value that is only a reference | Clients can be told `*****` means unchanged | Hides the wiring; a client that diffs definitions sees a change on every read |
| Definition reads: show a value that is exactly one Flow reference | References carry no secret's text | None found: what it resolves to is masked on every run read |

## Decision

Layers are read from their stores (`service-core/src/main/java/io/boomerang/workflow/ParamLayerService.java:140-180`)
through a 10-second per-instance cache (`core/ParamLayerCache.java:41-49`); nothing is copied onto the run. Three
rules keep untrusted text inert: a task result is inserted as written; on a run started by a webhook, event, GitHub
delivery, parent task or retry, a run param that differs from the revision default is inserted as written
(`engine/ParameterManager.java:80-86,579-641`); and password-typed global and workspace values join the run-read
redaction union (`workflow/WorkflowRunService.java:199-223`). `$(team.params.x)` still resolves as the workspace
scope, logs a warning once per reference and is never suggested (`common/model/ParamLayers.java:122-161`,
`engine/ParameterManager.java:936-944`). `ParameterUtil.isSingleFlowReference` accepts only a value that is exactly
one reference the resolver substitutes, never shell text such as `$(date)` (`common/util/ParameterUtil.java:52-63`),
and definition reads leave such a value as written (`common/util/DataAdapterUtil.java:66,173-178`).

## Consequences

- No parameter values on the run document; task runs hold what admitted tasks used. Revisit resolving at run start
  if a run is shown to fail because a parameter changed while it ran.
- `team.params` is removed in the next major version; workflows that use it log what to change.
- Text around a reference (`Bearer $(params.token)`) is masked, and a run read masks the value a reference resolved to.

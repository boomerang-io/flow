# 0109 — Deployment shape is properties, not a mode

**Status:** accepted · **Date:** 2026-10-15

## Context

`flow.mode=engine` amounted to three property defaults (security off, quotas off, one workspace) plus a
parallel copy of the workspace surface: every webapp page that touched a workspace needed an engine-only
controller and service beside the standard one (the workspace read, the profile read; PR #507 was the third
such copy). Decisions 0084 and 0086 had already moved the webapp, schedules and insights off the mode, so what
remained gated was a mode name standing in for a handful of switches.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the gating and add engine copies as pages need them | the two shapes genuinely load different code | every workspace-facing feature is written twice and tested twice; the copies drift |
| B. Keep `flow.mode` as a preset that only sets property defaults | operators want one word for the embedded shape | an implicit default an operator must know; two ways to say the same thing |
| C. Remove the mode; properties express the shape and every bean always loads | the shapes differ only in a few switches | an embedder sets two properties instead of one |

## Decision

Option C. `flow.mode`, its condition and the engine copies are gone; `flow.security.enabled` defaults to
`true` on its own (`service-core/src/main/java/io/boomerang/core/security/FlowSecurityProperties.java:19-20`)
and the new `flow.workspace.single` (default `false`, `core/security/FlowWorkspaceProperties.java:19-20`)
keeps the system-workspace guard (`SingleWorkspaceInterceptorConfiguration.java:22-26`) and refuses workspace
create and delete (`workspace/WorkspaceService.java:189,355`). The quota subsystem switch is gone too: the
stored `features.workspaceQuotas` setting decides (`workflow/WorkflowService.java:214-218`). The deciding
reason is that one surface served everywhere costs less than two kept in step.

## Consequences

- An embedding product sets `flow.security.enabled=false` and `flow.workspace.single=true`; a former engine
  deployment drops `FLOW_MODE` and sets both explicitly.
- Two property tiers: deployment shape is `flow.<subsystem>.<switch>` properties; runtime product toggles are
  `features.<name>` stored settings; a switch lives in exactly one tier.
- Revisit if an embedder contractually needs a smaller classpath; nothing today does.

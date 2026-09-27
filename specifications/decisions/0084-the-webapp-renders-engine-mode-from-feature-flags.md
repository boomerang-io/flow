# 0084 — The webapp renders engine mode from feature flags, not from the mode

**Status:** accepted · **Date:** 2026-09-28

## Context

Products that embed Flow run `service-core` in engine mode: one `system` workspace, security off, no webapp.
Operators want the webapp to show that one workspace's workflows and runs. The webapp needs its startup reads
to answer, and must hide the pages whose endpoints engine mode does not load.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. The server computes the flags from what is loaded; the webapp reads only flags | The webapp should work against any deployment shape without knowing it | A few new flags; every page that calls a mode-gated endpoint needs a flag guard |
| B. `/api/v2/context` exposes `mode`, and the webapp branches on it | Few pages differ | Mode checks spread through the webapp; a third shape means another round of branching |
| C. A third run mode, single workspace | The shape is a product of its own | Every `@ConditionalOnFlowMode` and every mode test gains a branch |

## Decision

Option A. `FeatureService` turns a flag on only when its setting is on and its surface is loaded
(`core/FeatureService.java:34-80`), with new flags for `workspace.single`, `schedules`, `integrations` and
`authentication`, following ARCHIE's `singleWorkspace` toggle, which hides the switcher and does not redirect.
Engine mode gains a profile read (`workspace/EngineProfileControllerV2.java`). `GET /api/v2/auth/config` is
not opened in engine mode: the webapp skips it when `authentication` is `false`
(`client-web/src/Features/App/App.tsx:142-147`), so no public endpoint is added.

## Consequences

- An engine deployment can add `client-web` with no configuration beyond `CORE_SERVICE_INTERNAL_ORIGIN`.
- The webapp has no sign-in against engine mode; it is for local development or behind a protected route.
- Engine-mode flags ignore the stored settings. Revisit if a standalone single-team install needs
  `workspace.single` as a stored setting, which is a data-model change.

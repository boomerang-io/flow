# 0111 — A workspace quota of zero is unlimited; an absent quota inherits the platform default

**Status:** accepted · **Date:** 2026-10-15

## Context

A stored workspace quota had two states: absent inherited the platform default from the `quotas` settings
document, and any number was the limit. `0` was read as absent for the run-duration ceiling, so the only way to
say "no limit" was a sentinel: the `system` workspace was seeded with `2147483647` for its four counts. The run's
own timeout already read `0` as unguarded (`workflow/RunTimeoutPolicy.java`), so the two disagreed, and the
webapp displayed the sentinel as a number.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the sentinel | no workspace ever needs "no limit" from the editor | a magic number in the seed, the API and every screen; `0` means two things |
| B. `0` is unlimited, absent inherits | an operator needs both "no limit" and "the default" per quota | the v3 `0`, a literal limit of zero, flips meaning unless the migration maps it |
| C. `null` is unlimited, no inheritance | every workspace carries its own full quota set | the platform default stops applying to existing workspaces; a seven-field write per workspace |

## Decision

Option B. Every one of the five quotas has three stored states: absent inherits the platform default at read
(`workspace/WorkspaceService.java` `setCustomQuotas`, which keeps a stored `0` as `0`), `0` is unlimited, a positive
number is the limit. One resolver, `Quotas.limited` (`workspace/model/Quotas.java`), is called by every check in
`workflow/WorkflowService.java` (`canCreateWithQuotas`, `assertRunQuotas`, `checkWorkspaceStorageQuota`,
`guardQuotasAfterAdmission`); `getWorkflowMaxDurationForTeam` returns a stored `0` as no ceiling, and a run that
declares no timeout still takes the platform default (`RunTimeoutPolicy.defaultTimeout`). The deciding reason is
that Tekton's `timeout: 0` and Temporal's unset timeout already mean "no limit" to the people who set these values.

## Consequences

- The `system` workspace is seeded with `0` for its four counts and no run duration; the sentinel is gone.
- The v3 migration drops a v3 `0` (`_0007__V3MigrateWorkspaces.limitOrAbsent`): v3 enforced it as a limit of zero,
  which v5 cannot express, so that workspace inherits the platform default rather than becoming unlimited.
- The webapp shows "Unlimited" for a `0`, draws no usage bar against it, and the editor accepts `0`.
- Revisit if a per-quota "inherit" must be distinguishable from absent in the API — today a client cannot tell a
  stored `0` from a platform default of `0`, nor restore one field to inherit without the restore-all delete.

# 0091 — Settings groups are named for what they control, and storage defaults stay with the dispatcher

**Status:** accepted · **Date:** 2026-09-30

## Context

The Settings tab lists the seeded settings documents by name. Four carried "Configuration" or
"Configure", two were called "Workspace Configuration - … Storage", and the quota defaults were keyed
`workspaces`. The two storage documents held eight v3-era fields (`storage.size`, `storage.class`,
`storage.accessMode`, `max.storage.size`, twice) that no code reads: a volume's size comes from the
workflow's own storage spec, capped by the workspace quota (`workflow/WorkflowService.java:497,506`) and
defaulted by the dispatcher that creates the claim (`service-dispatcher/.../WorkspaceService.java:24-30`,
`kube.workspace.storage.*`). One of the "storage" documents also held the two engine ceilings
`max.nesting.depth` and `max.foreach.items` (`engine/TaskExecutionService.java:82-91`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Rename the display text only | Nothing else may move | The dead storage fields stay on the screen; "Activity Storage" keeps naming a group of run limits |
| B. Rename text, rename `workspaces` to `quotas`, delete the dead storage fields, keep the ceilings as "Run limits" | The screen should say what each group controls | Two change units; a key the code reads changes in lock-step |
| C. Move the storage defaults back into settings the dispatcher fetches | An admin must change the default size from the product | A dispatcher protocol change; a deployment without Kubernetes, or a different executor, has no storage class at all |

## Decision

B (`_0055__RenameSettingsGroups`, `_0056__RetireStorageSettings`, `seed/settings.json`). Storage
defaults belong to the process that talks to the cluster, so they stay dispatcher properties: another
executor may need none of them, and the product should not carry a setting nothing reads. Customization
stays its own group rather than joining Features, which is ten booleans `FeatureService` turns into
flags; the customization fields are strings `ContextService` reads into the platform context.

## Consequences

- The Settings list is nine plain nouns; the code reads `quotas` through one constant.
- Changing the platform default volume size means changing a dispatcher property; option C is the
  route back if that becomes a product need.
- The dead Slack signature check in the authentication filter, which read a group the v3 migration
  had renamed, is removed rather than repointed; `SlackService` keeps its own, which reads `integration`.

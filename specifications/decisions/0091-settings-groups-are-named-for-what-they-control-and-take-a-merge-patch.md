# 0091 — Settings groups are named for what they control, storage defaults stay with the dispatcher, and settings and global parameters take a merge patch

**Status:** accepted · **Date:** 2026-10-19

## Context

The Settings tab lists the seeded settings documents by name, so each name has to say what the group controls, and a
group must not hold a field nothing reads. Admin settings and global parameters are unversioned documents an admin edits
one value at a time; replacing them whole made every secret travel back as `*****` to be kept.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Groups: A. Default volume size and class as settings the dispatcher fetches | An admin must change storage defaults in the product | A protocol change; another executor may have no storage class at all |
| Groups: B. Storage defaults as dispatcher properties; groups named for what they control | The process that talks to the cluster owns its defaults | Changing the default volume size is a dispatcher deployment change |
| Update: C. Full-replace `PUT` | Clients always hold the whole resource | Every change carries every field; a secret survives only by the `*****` round trip |
| Update: D. `PATCH` with JSON Merge Patch (RFC 7396) | Partial edits of a document-shaped resource | `null` clears here but keeps a secret on a `PUT` apply; the body is read as a tree |
| Update: E. `PATCH` with JSON Patch (RFC 6902) operations | Edits to lists by position, moves, tests | Heavier for every client, on resources with no lists worth patching |

## Decision

B and D. The nine groups are what `service-core/src/main/resources/seed/settings.json` says (Artifacts, Authentication,
Customization, Features, Integrations, Tasks, Quotas, Run limits, Audit), built on every install by
`migration/_0016__BuildSettingsFromSeed.java`, which maps a v3 `workspaces` or `teams` key onto `quotas` (`:54`). A
volume's size comes from the workflow's workspace, defaulted and capped at save from `max.workspace.storage`
(`workflow/WorkflowService.java:475-493`); its class and access mode default in the dispatcher
(`service-dispatcher/.../dispatcher/WorkspaceService.java:25-32`). Customization stays apart from Features: its values
are strings the platform context reads (`core/ContextService.java:110-115`), not flags. `PATCH /api/v2/settings` takes
`{"<group>": {"<key>": value}}` (`core/SettingsService.java:73`) and `PATCH /api/v2/parameters/{name}` the members to
change (`workflow/ParameterService.java:73`), each read as a tree so an explicit `null` is told from an absent member;
the `*****` marker still keeps a stored secret (`:108-113`). There is no `PUT` on either. Workflows, tasks and schedules
keep their `PUT` apply, because each apply writes a whole new version.

## Consequences

- A client sends only what it changes, and leaves an untouched secret out rather than echoing it.
- An unknown settings group or key is refused with `400 SETTINGS_INVALID_KEY` (`SettingsService.java:135`), since a
  typo would otherwise do nothing silently; an unknown parameter member is ignored, as elsewhere.
- Revisit the merge patch for workflows or tasks only if their applies stop creating a version per call.

# 0123 — Settings and global parameters take a merge patch

**Status:** accepted · **Date:** 2026-10-19

## Context

Admin settings and global parameters were updated by a full-replace `PUT`: a client changing one setting sent its
whole group, and a client editing one param sent the whole param, so every secret had to travel back as `*****` to
be kept. Neither resource is versioned, so nothing is gained by replacing it whole.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the full-replace `PUT` | Clients always hold the whole resource | Every change carries every field; a secret survives only by the `*****` round trip |
| B. `PATCH` with JSON Merge Patch (RFC 7396) | Partial edits of a document-shaped resource | `null` must mean clear here but keep on `PUT` applies, which has to be documented; the body is read as a tree, not the model |
| C. `PATCH` with JSON Patch (RFC 6902) operations | Edits to lists by position, moves, tests | Heavier for every client, for resources that have no lists worth patching |

## Decision

B, for settings and global parameters only: `PATCH /api/v2/settings` takes `{"<group>": {"<key>": value}}`
(`core/SettingsService.java:72`) and `PATCH /api/v2/parameters/{name}` takes the members to change
(`workflow/ParameterService.java:73`), each read as a JSON tree so an explicit `null` can be told from an absent
member; the secret rule's marker still keeps (`:106`). The `PUT` routes are removed with no alias. Workflows,
catalogue tasks and schedules keep their `PUT` apply, because each apply writes a whole new version. What decided
it: a partial edit of an unversioned document is exactly what a merge patch is for, and it lets an untouched secret
be left out rather than echoed.

## Consequences

- A client sends only what it changes; the webapp sends only changed members and entries.
- `null` clears on these two routes but keeps a secret on a `PUT` apply; `api-contract.md` "Partial updates" says so.
- An unknown settings group or key is refused with `400 SETTINGS_INVALID_KEY` instead of being ignored, because a
  typo in a seeded key would otherwise do nothing silently. An unknown param member is ignored, as elsewhere.
- Clients of the removed `PUT` routes must move; workspace parameters stay inside the workspace `PATCH`.
- Revisit for workflows or tasks only if their applies stop creating a version per call.

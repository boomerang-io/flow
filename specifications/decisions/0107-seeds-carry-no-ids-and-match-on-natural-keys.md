# 0107 — Seeds carry no ids and match on natural keys

**Status:** accepted · **Date:** 2026-10-15

## Context

The seeds in `service-core/src/main/resources/seed/` carried 237 hard-coded MongoDB ids. Task revisions and workflow
templates joined their tasks by id, and several migration units matched stored documents on an id before their
natural key (#461). Nothing in the running product read those ids; they were the legacy loader's ids, copied from v3.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the ids | Seeds must also recognise a v3 document whose natural key changed | Every new seed needs an invented id, and the joins are opaque in review |
| B. No ids; match on natural keys; seed joins by task name | Natural keys are unique and stable | A v3 catalogue task an install renamed is not recognised, so the seed adds its own copy beside it |

## Decision

Option B. The seeds hold no `_id`. Each unit matches on a natural key: settings on `key` and its earlier keys
(`_0016__BuildSettingsFromSeed`), roles on `type` and `name`, the system workspace on `name` and `type`, tasks on
`name` (`_0017__SeedTaskCatalogue`), workflow templates on `name` and `version`, and integration templates on `name`
(`_0020__SeedTemplates`). A seeded revision's `parentRef`, and a template task's `taskRef`, name the catalogue task,
and the unit resolves them to the live id on insert. v3's Sleep task is recognised by its arguments
(`_0006__V3MigrateTaskCatalogue`). The one reason it beat A: every v3 document the dump holds already matches on its
natural key (8 settings documents, 87 of 87 catalogue tasks, both templates), so the ids protected nothing.

## Consequences

- A new seed needs only its natural key, and a reviewer can read a revision's parent by name.
- A renamed v3 catalogue task leaves two tasks, the install's and the seed's; reopen this if one is reported
  (as #503 was closed).
- `V3DumpMigrationTest` and `MigrationChainTest.seedsCarryNoIdsAndJoinByName` pin the joins.

# 0030 — Seeds carry no ids and match on natural keys

**Status:** accepted · **Date:** 2026-10-15

## Context

The change units seed settings, roles, the system workspace, the task catalogue and workflow and integration templates
from `service-core/src/main/resources/seed/`, onto an empty database or beside a v3 install's own copies, and must
recognise a document already there.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Hard-coded MongoDB ids, joined by id | A v3 document whose natural key changed must still be recognised | Every new seed needs an invented id; joins are opaque in review |
| B. No ids; match on natural keys; join by task name | Natural keys are unique and stable | A v3 catalogue task an install renamed is not recognised, so the seed adds its own beside it |

## Decision

B. The seeds hold no `_id`. Each unit matches a natural key: settings on `key` and its earlier keys
(`_0016__BuildSettingsFromSeed`), roles on `type` and `name` (`migration/_0015__SeedRoles.java:49-51`), the system
workspace on `name` and `type` (`migration/_0004__SeedSystemWorkspace.java:109`), tasks on `name`
(`migration/_0017__SeedTaskCatalogue.java:92`), workflow templates on `name` and `version` and integration templates on
`name` (`migration/_0020__SeedTemplates.java:56-77`). A seeded revision's `parentRef` and a template task's `taskRef`
name the catalogue task, resolved to the live id on insert (`_0017__SeedTaskCatalogue.java:109-115`,
`_0020__SeedTemplates.java:87-101`); v3's Sleep task is recognised by its arguments
(`migration/_0006__V3MigrateTaskCatalogue.java:119-120`). Every v3 document in the reference dump already matches on its
natural key, so ids protected nothing.

## Consequences

- A new seed needs only its natural key, a reviewer reads a revision's parent by name, and
  `MigrationChainTest.seedsCarryNoIdsAndJoinByName` pins the joins.
- A seeded workflow template ships in `seed/workflow-templates.json`; once seeded it is a system workspace workflow
  that an admin edits like any other (decision 0046).
- Revisit if a v3 install's renamed catalogue tasks need adopting rather than a seeded copy beside them.

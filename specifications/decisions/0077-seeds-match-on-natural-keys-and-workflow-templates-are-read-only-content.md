# 0077 — Seeds carry no ids and match on natural keys, and workflow templates are seeded read-only content

**Status:** accepted · **Date:** 2026-10-15

## Context

The change units seed settings, roles, the system workspace, the task catalogue and workflow and integration templates
from `service-core/src/main/resources/seed/`, onto an empty database or beside a v3 install's own copies, and must
recognise a document already there. Every workflow template in every deployment comes from the seed or the v3 upgrade.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Seeds: A. Hard-coded MongoDB ids, joined by id | A v3 document whose natural key changed must still be recognised | Every new seed needs an invented id; joins are opaque in review |
| Seeds: B. No ids; match on natural keys; join by task name | Natural keys are unique and stable | A v3 catalogue task an install renamed is not recognised, so the seed adds its own beside it |
| Templates: C. A managed resource (create, import, delete) | Customers author and share their own | Write routes, screens and permissions for content only the seed produces |
| Templates: D. Seeded content plus create-from-template | Templates are a curated starting library | A template changes only with a release |

## Decision

B and D. The seeds hold no `_id`. Each unit matches a natural key: settings on `key` and its earlier keys
(`_0016__BuildSettingsFromSeed`), roles on `type` and `name` (`migration/_0015__SeedRoles.java:49-51`), the system
workspace on `name` and `type` (`migration/_0004__SeedSystemWorkspace.java:109`), tasks on `name`
(`migration/_0017__SeedTaskCatalogue.java:92`), workflow templates on `name` and `version` and integration templates on
`name` (`migration/_0020__SeedTemplates.java:56-77`). A seeded revision's `parentRef` and a template task's `taskRef`
name the catalogue task, resolved to the live id on insert (`_0017__SeedTaskCatalogue.java:109-115`,
`_0020__SeedTemplates.java:87-101`); v3's Sleep task is recognised by its arguments
(`migration/_0006__V3MigrateTaskCatalogue.java:119-120`). Every v3 document in the reference dump already matches on its
natural key, so ids protected nothing. Templates are content: `WorkflowTemplateControllerV2` has only `GET /{name}` and
`GET /query` (`workflow/WorkflowTemplateControllerV2.java:43,73`), and creating a workflow from one is a plain workflow
create (`client-web/src/Features/Home/Home.tsx:74-83`).

## Consequences

- A new seed needs only its natural key, a reviewer reads a revision's parent by name, and
  `MigrationChainTest.seedsCarryNoIdsAndJoinByName` pins the joins.
- A new template ships in `seed/workflow-templates.json` and needs a release; `_0011__V3ExtractWorkflowTemplates` lands
  a v3 database's `scope=template` workflows in `workflow_templates`.
- Revisit templates if a customer asks to author their own: that needs a write route and per-workspace ownership.

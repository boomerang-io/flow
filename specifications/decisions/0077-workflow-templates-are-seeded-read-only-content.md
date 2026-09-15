# 0077 — Workflow Templates are seeded, read-only content

**Status:** accepted · **Date:** 2026-09-15

## Context

Workflow Templates shipped with a full management surface — create, apply and delete over
`/api/v2/workflowtemplate`, plus an admin screen to import and remove them — yet nothing produced a
template except the loader's seed file and the v3 upgrade. The management paths had no users, and
half of the original controller had already been commented out since v4.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep full management | Customers author and share their own templates | Keeps an authoring surface, its permissions and its screens alive for a feature nobody uses; the write paths carry the only remaining callers of the template/task-catalogue validation |
| B. Seeded content plus create-from-template | Templates are a curated starting library | Templates can only change by a release; an operator who wants a private template writes to the collection directly |
| C. Remove templates entirely | The starting library adds no value | Breaks the Home screen's "create from template" flow and strands the v3 upgrade's imported templates |

## Decision

Option B. Templates are content, not a managed resource: `WorkflowTemplateControllerV2` keeps only
`GET /{name}` and `GET /query` (`service-core/src/main/java/io/boomerang/workflow/WorkflowTemplateControllerV2.java:57,84`),
and `WorkflowTemplateService` is read-only. The one reason that decided it: every template in every
deployment came from the loader, so the write routes protected nothing and cost a screen, an
import flow and a delete confirmation. Creating a Workflow from a template stays a plain Workflow
create with the template body (`client-web/src/Features/Home/Home.tsx:61-78`).

## Consequences

- The webapp keeps browsing templates and starting a Workflow from one; the import and delete
  screens are gone, as is `POST`/`PUT`/`DELETE` on the route.
- The `workflow_templates` collection is unchanged, and `_0010__V3ExtractWorkflowTemplates` still
  lands a v3 database's `scope=template` workflows in it.
- A new template ships in `service-loader/src/main/resources/seed/workflow-templates.json`, so it
  needs a release. Revisit if a customer asks to author templates of their own — that would restore
  a write route, with per-workspace ownership this never had.

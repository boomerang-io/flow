# 0033 — Every page shares one header, Home fans out from its loader, and administration is one Manage link

**Status:** accepted · **Date:** 2026-09-30

## Context

Pages need one header shape that leaves the editor's canvas most of a 1280×720 window. Home rolls up today's runs,
waiting actions, the newest runs and the next schedule across every workspace the caller belongs to, which no endpoint
returns, and seven admin screens need a side-nav place that does not crowd the workspace items.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Header: A. A 48px editor bar (back link, name, tabs) | The canvas needs every pixel | The editor alone loses its breadcrumb and looks unlike every other page |
| Home: B. Extend the profile's per-workspace `insights` | The numbers are wanted on every screen | Four more queries per workspace on every page load, for numbers only Home shows |
| Home: C. A new `GET /api/v2/home/summary` | Users routinely belong to many workspaces | A controller, model and tests for one page; the same fan-out moves server-side |
| Manage: D. An indented "Administer" submenu or a landing page of cards | Few, or rarely visited, admin screens | Seven indented rows, or one more click to everything |

## Decision

One header, a loader fan-out and one Manage link. Every route's header is at least 112px: breadcrumb, title, then a row
for the tabs or a one-line description (`client-web/src/Styles/_carbon-components.scss:69-111`). A list page is titled
with its route; a page inside one object is titled with its name and ends its breadcrumb with the route
(`client-web/src/Features/WorkflowEditor/Header/Header.tsx:75-81`). Home's loader reads the profile, then fires
`workflowrun/count`, `workflowrun/query`, `action/query` and `schedule/query` for every workspace in one wave and merges
them (`client-web/src/Features/Home/homeLoader.ts:150-182`), adding nothing to the data model or to other routes; a
failed read marks only its part as not loaded, never zero. Admins get one "Admin" link
(`core/NavigationService.java:171-179`) to a layout route (`client-web/app/routes.ts:53-62`) whose seven tabs, Settings
to Audit, each show only when the caller's grant and the feature flags allow
(`client-web/src/Features/Manage/manageTabs.ts:27-36`).

## Consequences

- A new page gets its header from `FeatureHeader` with no page-level styles; the side nav has no indented level, and the
  tab list is the one place that gates administration. Workflow templates have no admin page (decision 0030).
- Home's numbers come from routes already secured per workspace. The approvals and manual-task split is counted from the
  five newest actions, so it is a lower bound past five; the total is exact.
- Revisit the editor bar if users need the canvas taller still, and a summary endpoint if users with more than about ten
  workspaces, or Home's server-side wait exceeding Activity's, show the fan-out hurting.

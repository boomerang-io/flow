# 0090 — Administration is one "Manage" link to a tabbed page, and workflow templates leave the product UI

**Status:** accepted · **Date:** 2026-09-30

## Context

The side nav carried an "Administer" menu of seven indented children (`core/NavigationService.java`, the
menu block replaced by this change), two of them named like workspace items ("Task Manager" twice) and one,
"Template Workflows", opening a page that could only list seeded, read-only content
(`workflow/WorkflowTemplateControllerV2.java` has two GET routes). Every admin screen also drew its own
header and breadcrumb.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the submenu, restyle the indent | The list stays short | Carbon's submenu indent is the design; two "Task Manager" rows remain |
| B. One "Manage" link to a layout route with tabs, gated by grants and flags | Seven or more admin screens | Each screen loses its own header; the server sends one link |
| C. An admin landing page of cards | Screens are rarely visited | One more click to everything |

## Decision

B, the same shape Manage Workspace already has (`app/routes.ts`, the `manage` block). The tabs are Settings,
Workspaces, Users, Parameters, Tokens, Tasks and Audit (`client-web/src/Features/Manage/Manage.tsx`); the
"Global" prefix goes, since the area's name says it. Workflow templates keep their read-only API for the Home
screen and lose their admin page; the catalogue moves to the public docs, the way Langflow publishes its
use cases.

## Consequences

- The side nav is seven rows shorter and has no indented level.
- Permission gating moves to one place, the tab list, matching what each route already checks.
- A managed template resource (create from a workflow, edit, delete) is a separate decision if a
  team asks for it; nothing here forecloses it.

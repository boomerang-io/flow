# 0088 — Home's cross-workspace rollup fans out from the route loader instead of a new summary endpoint

**Status:** accepted · **Date:** 2026-09-29

## Context

The redesigned Home shows, across every workspace the caller belongs to, today's run counts, the
actions waiting on them, the newest runs and the next scheduled run. No endpoint returns that
rollup. The profile response already carries a per-workspace `insights` block (workflow and member
counts), computed inside the bootstrap that every route pays for
(`service-core/src/main/java/io/boomerang/workspace/WorkspaceService.java:837`).

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Extend the profile's `insights` with runs, actions and schedules | The numbers are wanted on every screen | Four more Mongo queries per workspace on every page load, for numbers only Home shows |
| B. One new `GET /api/v2/home/summary` endpoint | Users routinely belong to many workspaces | New controller, model and tests for one page; the same fan-out moves server-side |
| C. Fan out from Home's loader to the four existing per-workspace reads | A person belongs to a handful of workspaces | 4 × N requests plus one profile read, all server-side and in one wave; the numbers are only as fresh as the page |

## Decision

C. Home's loader reads the profile, then fires `workflowrun/count`, `workflowrun/query`,
`action/query` and `schedule/query` for every workspace in one wave and merges the pages
(`client-web/src/Features/Home/homeLoader.ts:153`). It is the only design that adds nothing to the
data model or to the cost of the routes that do not show these numbers. Each read is settled on
its own, so one failing workspace contributes zeros and a warning, never a blank page.

## Consequences

- Home needs no backend change; the numbers come from endpoints that already exist and are
  already secured per workspace.
- The approvals / manual split is counted from the page returned (five newest), so it is a lower
  bound when more than five actions are waiting; the total is exact.
- Revisit as B if a load test or an incident shows the fan-out hurting: the trigger is users with
  more than about ten workspaces, or Home's server-side wait exceeding the Activity page's.

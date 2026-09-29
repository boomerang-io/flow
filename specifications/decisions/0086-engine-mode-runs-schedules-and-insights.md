# 0086 — Engine mode runs schedules and insights

**Status:** accepted · **Date:** 2026-09-29

## Context

Engine mode loaded neither schedules nor insights, so an embedding product could not start a run on a timer or
see run statistics. Cheer's automations create a workflow with a schedule trigger, and the engine-mode webapp
hid both pages. Neither package depends on workspace management, which is the part engine mode leaves out.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Load schedules and insights in both modes | Products embed Flow for automation and want its scheduler | Every engine instance runs the schedule sweep; the sweep is already safe across instances |
| B. Keep both standalone-only; the embedding product schedules and submits runs itself | Embedding products own their own scheduler | Each product rebuilds cron, time zones and missed-fire handling |
| C. A property that switches schedules on in engine mode | Some engine deployments must not fire schedules | A second switch on the firing path, which is meant never to be off |

## Decision

Option A. The schedule package and `InsightsService` lose their standalone-only gate, and the `schedules` flag is
on in both modes while `insights` follows its setting (`core/FeatureService.java:69-75`). The scheduler already
needs no leader: one compare-and-set per fire (`schedule/ScheduleService.java:457-470`). Insights read the audit
trail, which the engine writes in both modes.

## Consequences

- An embedding product creates a schedule through `/api/v2/workspace/system/schedule`, and the engine fires it.
- The engine-mode webapp shows the Schedules and Insights pages.
- Insights only show runs while audit capture is on at the write level; an engine install with audit off sees
  an empty page.

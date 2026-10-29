# 0139 — A task param reads the layers it inherits, never the task's own params

**Status:** accepted · **Date:** 2026-10-29

## Context

v3 flattened the global, team, workflow and context layers into `$(params.x)`, and a task's inputs were
referenced only by its template. v5 added the task's own params as a layer, so `url: $(params.url)` read
itself: first the literal text reached the dispatcher, then (from 2026-10-20) admission refused it as a
self-reference. Upgraded v3 workflows and the seeded Summarise and MongoDB templates use that pattern, so
their tasks failed with `ParamUnresolved`.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Task param values resolve from global, workspace, workflow and context only; the spec keeps reading the task's params | Upgraded v3 workflows must keep working | Task params can no longer compose from a sibling, which v3 never offered |
| B. Keep the self-reference error; rewrite seeds and v3 workflows to `$(workflow.params.x)` | References should always be explicit | A new change unit over v3 data; hand-written v3-style values still fail |

## Decision

A: the v3 layering is the platform's contract. `resolveParamLayers` builds its layers without the task's
params (`engine/ParameterManager.java:144-150`, `buildParameterLayering` `:534-570`), the secret taint
follows the same layers (`:459-490`), and the reference-cycle check is gone because task params cannot
reach each other. `fillSpec` still reads the task's resolved params.

## Consequences

- `x: $(params.x)` passes the workflow's `x` through, as in v3; upgrades and the seeded templates run.
- A task param that names only a sibling is reported as undeclared; compose in the spec instead.
- Parameter layering joins the review gate in `CLAUDE.md`.

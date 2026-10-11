# 0087 — One page header on every route, and a page inside an object is titled with its name

**Status:** accepted · **Date:** 2026-09-29

## Context

Page headers ran from 109px to 175px depending on the route, and in the workflow editor the header and task
palette took more than half of a 1280×720 window before the first task. The editor's title, "Editor", repeated
what its tabs said while the workflow's name sat at the end of the breadcrumb.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. One 112px-minimum header anatomy (breadcrumb, title, a row for tabs or the description) on every route; compact palette | Space and consistency both matter | Small per-page style changes |
| B. A single 48px editor bar (back link, name, tabs, version) | The canvas needs every pixel | The editor alone loses its breadcrumb and looks unlike every other page |
| C. Leave headers as they are | — | 22% of the height stays on the editor header; heights keep varying |

## Decision

Option A (`client-web/src/Styles/_carbon-components.scss`, the "Page header" section of `design-system.md`). A
list page is titled with its route; a page inside one object is titled with that object's name and ends its
breadcrumb with the route, following the user detail page, which already did. Pages with tabs drop the
description line, and controls for the tabbed content sit on the tab row.

## Consequences

- At 1280×720 the editor canvas grows from 517px to 560px tall and the palette shows 10 tasks instead of 4½.
- Every route's header measures 112px; a new page gets it from `FeatureHeader` with no page-level header styles.
- Revisit B if a user needs the canvas taller still, for example on a laptop at 1280×720 with the browser's
  own bars.

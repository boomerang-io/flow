# Boomerang Flow — Design System Specification

**Status**: 📎 Reference (Stable core, Evolving token/component inventory)
**Basis**: IBM Carbon Design System v11 + the Boomerang theme + Boomerang Carbon add-ons.

> This is different from ARCHIE (EY Motif) and cheer.dev. Boomerang Flow's design system is
> **IBM Carbon** with the **Boomerang theme** layered on top and the **Boomerang Carbon
> add-ons** component library. Do not import EY Motif or cheer.dev design tokens/components here.

## Why this spec lives in a backend repo

`flow.service.workflow` is the backend (Java/Spring). It ships **no** frontend today. The
Boomerang Flow **UI lives in a separate repo, `flow.client.web`** (`boomerang.app.flow`), which
is the authoritative implementation this spec is derived from.

This document exists here so that:
1. Any UI surface this project ever grows (an admin/console page, generated docs, an embedded
   view) is consistent with Boomerang from the first line — not re-invented.
2. The design system is a documented architectural reference alongside the other v5 specs.
3. The `design-system` skill has a source of truth to enforce.

**Source of truth for values**: the installed packages in `flow.client.web`
(`@boomerang-io/styles`, `@boomerang-io/carbon-addons-boomerang-react`, `@carbon/react`). The
ramps and tokens below are transcribed from there — when in doubt, read the package, not this file.

## Package stack (verified from `flow.client.web`)

| Package | Version | Role |
| ------- | ------- | ---- |
| `@carbon/react` | `1.59.0` | IBM Carbon v11 — components, SCSS, tokens, grid, type. |
| `@boomerang-io/carbon-addons-boomerang-react` | `4.1.2-beta.0` | Boomerang component library + the `boomerang` Carbon theme + add-on SCSS. |
| `@boomerang-io/styles` | `1.1.0` | Boomerang brand palette (colour ramps) and shared styles. |
| `@boomerang-io/utils` | `1.1.0` | Shared Boomerang utilities. |
| `@carbon/charts` + `@carbon/charts-react` | `^1.13.6` | Carbon data visualisation. |
| `@carbon/pictograms-react` | `^11.50.0` | Carbon pictograms. |
| React | `17.x` | UI runtime. |

Styling is **SCSS + CSS Modules** (`*.module.scss`), not Tailwind and not CSS-in-JS.

## Theme wiring (the canonical recipe)

The Boomerang theme is Carbon's **White** theme with the Boomerang theme merged over it, applied
under the `[data-carbon-theme="boomerang"]` selector. This is the exact, working setup from
`flow.client.web/src/Styles/_carbon-components.scss` — replicate it verbatim for any new Carbon app:

```scss
@use "sass:map";

// Carbon base
@use "@carbon/react/scss/config" with ($use-akamai-cdn: true);
@use "@carbon/react/scss/reset";
@use "@carbon/react/scss/motion";
@use "@carbon/react/scss/type";

// Boomerang theme merged onto Carbon's white theme (compat layer)
@use "@boomerang-io/carbon-addons-boomerang-react/scss/global/themes/boomerang";
@use "@carbon/react/scss/compat/themes" as compat;
@use "@carbon/react/scss/compat/theme" with (
  $theme: map.merge(compat.$white, boomerang.$theme)
);

// Base + component tokens
@use "@carbon/react/scss/fonts";
@use "@carbon/react/scss/grid";
@use "@carbon/react/scss/layer";
@use "@carbon/react/scss/zone";
@use "@carbon/react/scss/components/button/tokens" as button;
@use "@carbon/react/scss/components/notification/tokens" as notification;
@use "@carbon/react/scss/components/tag/tokens" as tag;
@use "@carbon/react/scss/components";

// Boomerang add-ons SCSS, boomerang mode on
@use "@boomerang-io/carbon-addons-boomerang-react/scss/global" with ($use-theme-boomerang: true);

// Emit the theme's CSS custom properties under the boomerang theme selector
[data-carbon-theme="boomerang"] {
  @include theme.add-component-tokens(map.merge(button.$button-tokens, boomerang.$v11-button-tokens));
  @include theme.add-component-tokens(notification.$notification-tokens);
  @include theme.add-component-tokens(tag.$tag-tokens);
  @include theme.theme();
}
```

Global stylesheet import order (`styles.scss`): `variables → animation → carbon-components →
@boomerang-io/styles → base`.

## Tokens — use tokens, never raw hex in components

### 1. Carbon compat tokens (the working token layer)

The app consumes Carbon's **v10→v11 compat** tokens as CSS custom properties `--cds-*`. Reach for
these first — they re-theme automatically:

- Surfaces: `--cds-ui-background`, `--cds-ui-01`, `--cds-ui-02`, `--cds-ui-03`
- Text: `--cds-text-01` (primary), `--cds-text-02` (secondary), `--cds-text-03` (placeholder)
- Interactive/brand: `--cds-interactive-01`, `--cds-link-01`
- Support: `--cds-support-01` (error), `--cds-support-02` (success), `--cds-support-03` (warning), `--cds-support-04` (info)

In SCSS, the Carbon token functions/mixins (`$ui-01`, `$text-01`, `type-style()`,
`$spacing-05`, …) are available from the `@use` above. Body defaults (from `_base.scss`) are
`background: var(--cds-ui-background)`, `color: var(--cds-text-01)`, `line-height: 1.3`,
`font-family: "IBM Plex Sans"`.

### 2. Boomerang brand palette (`@boomerang-io/styles`)

The brand ramps live in `@boomerang-io/styles` as SCSS variables, each in graded steps `1…90`.
Verified ramps include: **aqua, blue, cerulean, cool-gray, cool-white, gold, gray** (and more —
read the package for the full set). Examples:

```
$cerulean-90: #1b2834   $aqua-30: #00b6cb   $gold-20: #ffb000
$cool-gray-50: #6f7878   $blue-50: #2d74da   $gray-1: #eaeaea
```

**Boomerang's signature brand colour is purple** (`#6e32c9`, see Flow accents below). Use the
palette variables by name; do not paste raw hex into component styles.

### 3. Flow accent tokens (app-specific, `src/Styles/_variables.scss`)

Flow defines a small set of product accents on `:root` for workflow-execution semantics. These
are the ONLY app-level raw values, and they exist because they encode run status, not brand:

| Token | Value | Meaning (Flow run/status semantics) |
| ----- | ----- | ----------------------------------- |
| `--flow-switch-primary` | `#6e32c9` | Boomerang purple — primary/active accent. |
| `--flow-wait` | `#491d8b` | Waiting / queued state. |
| `--flow-failure` | `#a51920` | Failed / error state. |
| `--flow-darkest-blue` | `#1b2834` | Darkest surface accent (headers, deep chrome). |
| `--flow-lightest-teal` | (in styles) | Lightest teal accent. |

Map new run-status UI to these accents (or the Carbon `support-*` tokens) — never introduce a new
status colour ad hoc.

## Typography

- Font family: **IBM Plex Sans** (loaded via Carbon fonts; `$use-akamai-cdn: true` serves them
  from Akamai). Mono/code: IBM Plex Mono.
- Use Carbon **type tokens / `type-style()`**, not hardcoded `font-size`/`line-height`. The type
  scale (`$productive-heading-0X`, `$body-long-0X`, `$label-01`, …) comes from
  `@carbon/react/scss/type`.

## Layout, grid, spacing

- **Carbon 2x Grid** (`@carbon/react/scss/grid`) — 16-column responsive grid; use `Grid`/`Column`
  (or `.cds--grid`/`.cds--col`).
- **Spacing scale**: Carbon `$spacing-01 … $spacing-13` (0.125rem → 10rem). Use these, not
  arbitrary px. Layer/zone tokens (`@carbon/react/scss/layer`, `/zone`) manage elevation context.

## Components — prefer the library, in this order

1. **Boomerang add-ons** (`@boomerang-io/carbon-addons-boomerang-react`) — Boomerang-styled/extended
   components. Prefer these when one exists. Verified in-use set (from `flow.client.web`, ~146
   imports): `UIShell`, `User`, `HeaderMenuItem`, `ComposedModal`, `ConfirmModal`, `ModalForm`,
   `ModalFlow`, `ModalFlowForm`, `DynamicFormik`, `DataDrivenInput`, `AutoSuggest`, `ComboBox`,
   `Creatable`, `CheckboxList`, `RadioGroup`, `TextInput`, `TextArea`, `Toggle`, `Loading`,
   `TooltipHover`, `ToastNotification`, `notify`, and the error surfaces
   `Error / Error403 / Error404 / ErrorFullPage / ErrorMessage / ErrorPage`.
   - The **`UIShell`** provides the Boomerang header/side-nav chrome — use it for app shell, do
     not hand-roll navigation.
2. **Carbon** (`@carbon/react`) — for anything the add-ons don't cover (`Button`, `DataTable`,
   `Modal`, `Tabs`, `Tag`, `InlineNotification`, form inputs, etc.).
3. **Custom** — only when neither library has it. Build it from Carbon tokens + the 2x grid so it
   themes correctly; never with raw colours or off-scale spacing.

### Class-name conventions

- Carbon component classes are prefixed **`cds--`** (v11). Override with care and scope under
  `#app`, as `_base.scss` already does for a few Carbon internals.
- Boomerang custom classes use **`b-`**; app-local classes use **`c-`**. New styles go in a
  co-located `*.module.scss` (CSS Modules), matching neighbours.
- The theme is activated by the attribute **`data-carbon-theme="boomerang"`** on a root element.

## Data visualisation

Use `@carbon/charts` / `@carbon/charts-react` (already themed to Carbon). Do not add a second
charting library. If building charts, also honour the `dataviz` skill's colour rules on top of
Carbon's categorical palette.

## Do / Don't

**Do**
- Consume `--cds-*` compat tokens and Boomerang palette variables; theme via `data-carbon-theme="boomerang"`.
- Use the Boomerang add-ons component first, Carbon second, custom last.
- Use Carbon type tokens, the 2x grid, and the `$spacing-*` scale.
- Keep IBM Plex Sans; map run-status UI to the Flow accent tokens.

**Don't**
- Paste raw hex into component styles (the Flow accent `:root` tokens are the only sanctioned raw values).
- Introduce Tailwind, CSS-in-JS, EY Motif, or cheer.dev design assets.
- Hand-roll navigation/modals/forms that the add-ons already provide.
- Add a second UI or charting framework.

## Open items (fill when UI work actually lands here)

- Confirm the current `@boomerang-io/*` and `@carbon/react` versions at the time of use (the table
  above is a point-in-time transcription from `flow.client.web` v3.12.0).
- If this repo adds a UI, port `styles.scss` / `_carbon-components.scss` / `_variables.scss` from
  `flow.client.web` rather than re-deriving them.

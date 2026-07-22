---
name: design-system
description: Boomerang Flow design system — IBM Carbon v11 + the Boomerang theme + Boomerang Carbon add-ons (@boomerang-io/carbon-addons-boomerang-react). Use this skill when creating or modifying ANY frontend/UI element for a Boomerang Flow surface — React components, SCSS, Carbon usage, colour tokens, typography, layout, status colours — or when reviewing UI for design consistency. This is NOT EY Motif (ARCHIE) and NOT the cheer.dev system. Read this before writing any UI code.
---

# Boomerang Flow Design System

Boomerang Flow's UI is **IBM Carbon Design System v11** with the **Boomerang theme** layered on
top and the **Boomerang Carbon add-ons** as the component library. Signature brand colour is
**purple (`#6e32c9`)**. Do not use EY Motif (ARCHIE) or cheer.dev tokens/components here.

> Note: `flow.service.workflow` is the **backend** — it ships no frontend today. The Boomerang
> Flow UI lives in the separate **`flow.client.web`** repo (`boomerang.app.flow`), which is the
> source of truth. This skill governs any UI this project does produce (admin/console, generated
> docs, embedded views) and keeps it consistent with Boomerang.

## Step 1: Read the specification

```
Read: specifications/design-system.md
```

It holds the exact package versions, the canonical theme-wiring SCSS, the token system, and the
verified component list. The spec is the source of truth; this skill is the how-to.

## Step 2: If a value or component is uncertain, read the real package

The authoritative implementation is `flow.client.web`. When you need a precise token value, a
component API, or the theme setup, read it there rather than guessing:

```
Reference repo: /Users/tysonlawrie/Workspaces/boomerang-io/flow.client.web
```

- `src/Styles/_carbon-components.scss` — the canonical Carbon+Boomerang theme wiring.
- `src/Styles/_variables.scss` — the Flow accent tokens (`--flow-switch-primary`, `--flow-wait`, …).
- `src/Styles/styles.scss`, `_base.scss` — import order and global base styles.
- `node_modules/@boomerang-io/styles` — the Boomerang brand colour ramps (aqua/blue/cerulean/
  cool-gray/gold/gray, steps 1…90).
- `node_modules/@boomerang-io/carbon-addons-boomerang-react` — the component library + `boomerang` theme.

## Core rules (NON-NEGOTIABLE)

1. **Stack**: Carbon v11 (`@carbon/react`) + `@boomerang-io/carbon-addons-boomerang-react` +
   `@boomerang-io/styles`. No Tailwind, no CSS-in-JS, no second UI/charting framework.
2. **Theme**: activate with `data-carbon-theme="boomerang"`. Wire the theme exactly as in
   `_carbon-components.scss` — Boomerang theme merged onto Carbon's **white** theme via the compat
   layer. Copy that recipe; don't improvise a theme.
3. **Tokens over hex**: style with Carbon compat tokens (`--cds-ui-background`, `--cds-text-01`,
   `--cds-interactive-01`, `--cds-support-01…04`) and named Boomerang palette variables. The only
   sanctioned raw values are the Flow accent `:root` tokens (they encode run status, not brand).
4. **Components** in priority order: (1) Boomerang add-ons — `UIShell`, `ComposedModal`,
   `ConfirmModal`, `ModalForm`/`ModalFlow`, `DynamicFormik`, `DataDrivenInput`, `AutoSuggest`,
   `ComboBox`, `TextInput`, `TextArea`, `Toggle`, `Loading`, `TooltipHover`, `ToastNotification`,
   `notify`, `Error*` surfaces, `User`; (2) Carbon (`Button`, `DataTable`, `Tabs`, `Tag`, …);
   (3) custom, built from Carbon tokens + 2x grid, only when neither library has it. Use `UIShell`
   for app chrome — never hand-roll header/side-nav.
5. **Type**: IBM Plex Sans via Carbon fonts; use Carbon type tokens / `type-style()`, never
   hardcoded font sizes.
6. **Layout/spacing**: Carbon 2x grid; the `$spacing-01…13` scale — no arbitrary px.
7. **Status colours**: map run/task status to the Flow accent tokens (`--flow-switch-primary`
   active/purple, `--flow-wait` queued, `--flow-failure` failed, `--flow-darkest-blue` chrome) or
   Carbon `support-*` — never invent a new status colour.
8. **Styling mechanics**: SCSS + CSS Modules (`*.module.scss`) co-located with the component.
   Carbon classes are `cds--`; Boomerang custom `b-`; app-local `c-`. Scope Carbon overrides under
   a root (`#app`) as `_base.scss` does.

## Reviewing UI for consistency

Flag: raw hex in components; Tailwind/CSS-in-JS creeping in; hand-rolled nav/modals/forms that the
add-ons already provide; hardcoded font sizes or off-scale spacing; ad-hoc status colours; a
missing/incorrect `data-carbon-theme="boomerang"`; or any EY Motif / cheer.dev leftover. Confirm
new colours resolve to a Carbon compat token, a Boomerang palette variable, or a Flow accent token.

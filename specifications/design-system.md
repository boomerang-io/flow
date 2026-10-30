# Design system

Boomerang Flow's design system is IBM Carbon v11 with the Boomerang theme and the Boomerang Carbon add-ons; no other
design system's tokens or components belong here. Paths below are under `client-web/` unless they start with another
module.

## Package stack

The webapp uses `@carbon/react` 1.75.0 (Carbon v11 components, SCSS, tokens, grid and type),
`@boomerang-io/carbon-addons-boomerang-react` 4.9.0-beta.15 (the Boomerang components, the `boomerang` theme and
add-on SCSS), `@boomerang-io/styles` 1.1.0 (the brand palette), `@boomerang-io/utils`, `@carbon/charts` with
`@carbon/charts-react`, `@carbon/pictograms-react`, `@xyflow/react` 12 for the canvas, and React 18
(`package.json:28-34,61,98`).

Styling is SCSS with CSS Modules (`*.module.scss`, imported by 175 components). Tailwind, CSS-in-JS, a second UI or
charting framework, and another design system's assets do not belong in the webapp.

## Theme wiring

The Boomerang theme is Carbon's White theme with the Boomerang theme merged over it through Carbon's compat layer,
emitted as CSS custom properties under `[data-carbon-theme="boomerang"]` together with Carbon's button (merged with
Boomerang's v11 button tokens), notification and tag component tokens (`src/Styles/_carbon-components.scss:12-42`).
The attribute sits on the document's `<html>` element (`app/root.tsx:64`). A new Carbon app copies that file rather
than improvising a theme.

The global stylesheet imports, in order, `variables`, `animation`, `carbon-components`, `@boomerang-io/styles` and
`base` (`src/Styles/styles.scss:1-5`).

## Tokens: use tokens, never raw hex in components

### 1. Carbon tokens

Components read Carbon's tokens as CSS custom properties `--cds-*`. The compat theme emits both the v10 names and the
v11 names, and the code uses both:

| Role | v10 name | v11 name |
| --- | --- | --- |
| Surfaces | `--cds-ui-background`, `--cds-ui-01`, `--cds-ui-02`, `--cds-ui-03` | `--cds-background`, `--cds-layer-01`, `--cds-border-subtle-01` |
| Text | `--cds-text-01`, `--cds-text-02`, `--cds-text-03` | `--cds-text-primary`, `--cds-text-secondary`, `--cds-text-placeholder` |
| Interactive | `--cds-interactive-01`, `--cds-link-01` | `--cds-interactive`, `--cds-link-primary` |
| Support | `--cds-support-01` (error), `-02` (success), `-03` (warning), `-04` (info) | `--cds-support-error`, `-success`, `-warning`, `-info` |

A module stylesheet `@use`s the Carbon module it needs (`@carbon/react/scss/colors` in 41 files, `/spacing`,
`/motion`, `/type`); nothing is in scope from the global stylesheet. The body takes `--cds-ui-background`,
`--cds-text-01` and `line-height: 1.3` (`src/Styles/_base.scss:9-14`); the font is IBM Plex Sans through
`--cds-font-family` (`src/Styles/_carbon-components.scss:118-120`).

### 2. Boomerang brand palette (`@boomerang-io/styles`)

The brand ramps are SCSS variables in `@boomerang-io/styles/lib/_colors.scss`, in steps `1…90`: aqua, blue,
cerulean, cool-gray, cool-white, gold, gray, green, indigo, lime, magenta, neutral-white, orange, peach, purple, red,
teal, ultramarine, violet, warm-gray, warm-white and yellow (for example `$cerulean-90` is `#1b2834`, `$teal-1` is
`#c0f5e8`). The theme's interactive and link colour is blue (`#0072c3`); Flow's purple accent is `--flow-switch-primary`
(below). Carbon's palette is used the same way (`@use "@carbon/react/scss/colors";`, then `colors.$cool-gray-20`).

`pnpm run lint:style` runs stylelint's `color-no-hex` over `src/**/*.{scss,css}` and fails the `typecheck` job of the
web CI workflow on any hex outside `src/Styles/_variables.scss` (`stylelint.config.mjs`, `package.json:14`,
`.github/workflows/ci-web.yml:72-74`). `src/Styles/markdown.css`, a copy of GitHub's markdown stylesheet, opts out in
its first line.

### 3. Flow accent tokens (`src/Styles/_variables.scss`)

Flow defines five product accents on `:root` (`src/Styles/_variables.scss:1-11`). They are the only raw colour values
in the app's stylesheets:

| Token | Value | Meaning |
| ----- | ----- | ------- |
| `--flow-switch-primary` | `#6e32c9` | Purple, the primary and active accent (the AI node border, the for-each stack). |
| `--flow-wait` | `#491d8b` | Waiting or queued state. |
| `--flow-failure` | `#a51920` | Failed or error state. |
| `--flow-darkest-blue` | `#1b2834` | Darkest surface accent (headers, deep chrome). |
| `--flow-lightest-teal` | `#c0f5e8` | The `$teal-1` step of the Boomerang teal ramp, used on dark chrome (the tutorial's finish marker, notification-banner links). |

New run-status UI MUST map to these accents or the Carbon support tokens, never a new status colour.

## Typography

- IBM Plex Sans, loaded through Carbon's fonts module with `$use-akamai-cdn: true`
  (`src/Styles/_carbon-components.scss:5-7,20`). Code and parameter names use IBM Plex Mono through
  `--cds-code-01-font-family` (`src/Features/Parameters/ParametersTable/parametersTable.module.scss:15-17`).
- New styles SHOULD take sizes from Carbon's type tokens (`type-style()` or the `--cds-<style>-font-size` properties,
  from `@carbon/react/scss/type`) rather than a literal `font-size`.

## Layout and spacing

- The Carbon grid stylesheet is loaded (`src/Styles/_carbon-components.scss:21`), but no page uses `Grid`/`Column`:
  pages lay out with flexbox and CSS grid in their own modules.
- New styles SHOULD use Carbon's spacing scale (`$spacing-01 … $spacing-13`, 0.125rem to 10rem, from
  `@carbon/react/scss/spacing`) rather than arbitrary values. Layer and zone tokens (`@carbon/react/scss/layer`,
  `/zone`) set elevation context.

## Page header

Every route's header is the add-ons `FeatureHeader` in one anatomy, set once in
`src/Styles/_carbon-components.scss:69-111`: 16px top, the breadcrumb (18px), the title (36px line, 28px regular),
then a 40px row holding the page's tabs or, when it has none, its one-line description (truncated). It is a
`min-height` of 112px, not a height, so a long description or 200% zoom grows it instead of clipping.

| Part | Rule |
| --- | --- |
| Breadcrumb | Every page has one. Workspace pages start `Home / <workspace>`; the Manage area's layout carries `Home / Manage` once for every tab (`src/Features/Manage/Manage.tsx:56-77`). |
| Title | A list page is titled with its route (Workflows, Actions, Settings). A page inside one object is titled with that object's name, and the breadcrumb ends with the route: the editor is `Home / Workflows / Editor` titled with the workflow's name (`src/Features/WorkflowEditor/Header/Header.tsx:64-81`), Activity detail is `Home / Activity / Activity detail` titled with the run's workflow, a user is titled with the user's name. |
| Right slot | Page facts and actions (search, today's numbers, run facts) sit in `actions`. Controls sit after the facts, inside to outside: icon and secondary buttons, the one primary action, then an overflow menu (⋮) for the rest, destructive items last. A status is a Carbon tag in the status colour: on Activity detail it leads the run facts with the duration inside (`src/Features/WorkflowRun/RunHeader/RunHeader.tsx:199-213`). Controls that act on tabbed content (the editor's version switcher, a task's version actions) sit on the tab row instead, so the title keeps the width. |
| Tabs and description | A page with tabs carries no description line (Actions, Manage). |

The workflow editor's task palette follows the same density: one 32px title row ("Add a task", Expand all, the
collapse control), one search row with the filter and the task count in the placeholder, and 40px task rows
(`src/Features/WorkflowEditor/Designer/Tasks/Tasks.tsx:232-263`, `tasks.module.scss:40`,
`Task/task.module.scss:11`).

## Components: prefer the library, in this order

1. **Boomerang add-ons** (`@boomerang-io/carbon-addons-boomerang-react`, imported by 145 files): `UIShell`, `User`,
   `HeaderMenuItem`, `FeatureHeader` with its title, subtitle and nav tabs, `FeatureSideNav`, `ComposedModal`,
   `ConfirmModal`, `ModalForm`, `ModalFlow`, `ModalFlowForm`, `DynamicFormik`, `DataDrivenInput`, `ComboBox`,
   `Creatable`, `CheckboxList`, `RadioGroup`, `TextInput`, `TextArea`, `Toggle`, `Loading`, `TooltipHover`,
   `ToastNotification`, `notify`, and the error surfaces `Error`, `Error403`, `Error404`, `ErrorFullPage`,
   `ErrorMessage`, `ErrorPage` and `ErrorDragon`. The library has no `AutoSuggest`, so the editor's parameter inputs
   are plain fields (`src/Features/Reactflow/components/shared/inputs.tsx:16-33`). `UIShell` provides the header
   and side-nav chrome; navigation is never hand-rolled. Flow feeds the shell's Docs and Release Notes, turns off its
   launchpad About and profile row for its own (`src/Features/App/Navbar/Navbar.tsx:66-115`), and patches out its
   fixed-colour help-menu rule (`client-web/patches/`).
2. **Carbon** (`@carbon/react`) for anything the add-ons do not cover (`Button`, `DataTable`, `Tabs`, `Tag`,
   `ContentSwitcher`, `InlineNotification`, form inputs).
3. **Custom** only when neither library has it, built from Carbon tokens and the spacing scale so it themes
   correctly.

### Lists and tables

List pages follow the Activity page, not Carbon's table toolbar and batch-action bar: labelled filters ("Filter by
Workflow", "Filter by status", "Filter by trigger") in a row above the table, a date range on the right
(`src/Features/Activity/Activity.tsx:276-330`), sortable headers, a row that opens the item, and status as an icon
with coloured text rather than a tag (Activity, Actions, Users). Search on a list of people or workspaces sits in the
page header. Actions opens on what is waiting (`src/Features/Actions/Actions.tsx:48,51-59`).

### Switching views and choosing values

| Need | Control |
| --- | --- |
| Two views of one side panel | Contained `Tabs`, full width, a count on each tab (Activity detail's Task log and Artifacts, `src/Features/WorkflowRun/TaskRunList/TaskRunList.tsx:106-113`) |
| Several views of one page's content | A `ContentSwitcher` beside the page's primary button (Schedules: List, Upcoming, Week, Month; `src/Components/ScheduleViews/ScheduleViews.tsx:88-103`) |
| One choice inside a form | Radio buttons; a choice with settings opens them underneath (the schedule form's When, `src/Components/ScheduleManagerForm/ScheduleManagerForm.tsx:359-374`) |

Content switchers are Boomerang blue, not the theme's inverse ink: one rule points the switcher's `border-inverse`,
`layer-selected-inverse` and `text-secondary` tokens at the interactive and link colours
(`src/Styles/_carbon-components.scss:61-67`).

### Parameters

Workflow, workspace and global parameters share one table
(`src/Features/Parameters/ParametersTable/ParametersTable.tsx:33-39,90-96`): Name first in monospace (what tasks type),
Label, then Type and Default value for a workflow or Value for the other two, a Required, Read-only or Secured tag,
Description, and icon buttons rather than a menu: a pencil for Edit, a copy icon for Copy reference and a trash can in
the error colour for Delete, each with a tooltip that opens left because the table clips above the row; a read-only
parameter keeps only Copy reference (`:145-171`). Above the table, the Schedules list's toolbar: a layered search and
Add parameter at one height (`:193`). The workflow has its own modal and the workspace and global scopes share one; both
are titled "Edit parameter" or "Add parameter"
(`src/Features/WorkflowEditor/Parameters/PropertiesModal/PropertiesModal.tsx:24`,
`src/Features/Parameters/CreateEditParametersModal/CreateEditParametersModal.tsx:73`), show the name as text with its
reference once it exists (`$(params.x)`, `$(workspace.params.x)`, `$(global.params.x)`), and treat the label as
optional, falling back to the name. The workflow modal previews the field a run shows.

### Schedules

A workflow's Schedules tab and the workspace Schedules page share `src/Components/ScheduleViews`: a content switcher
over List (a table with When in words, Next run, Time zone and a status tag), Upcoming (the next 30 days), Week and
Month (`ScheduleViews.tsx:13-23`). The list's toolbar holds the search, the status filter, the switcher and Create
schedule at one height (`ScheduleViews.tsx:88-103`); a row's Edit, Disable or Enable, and Delete are icon buttons, as on
the parameters table (`src/Components/SchedulePanelList/SchedulePanelList.tsx:327`), and an empty list shows
`EmptyState` (`:97,119`). The calendar views share a toolbar: Today as a ghost button with the calendar icon, then
previous, next and the dates shown (`ScheduleViews.tsx:142-150`). The schedule form's When offers Once, Hourly, Daily,
Weekdays, Weekly and Custom (cron), stored as the three schedule types (`src/Utils/cronHelper.tsx:26-27,128`), with a
line under the time zone saying what will happen and when it next runs.

### Task parameter inputs

A catalogue task's parameters are rendered generically: the editor's config modal hands `task.spec.params` to the
add-ons' `DynamicFormik` (`src/Features/Reactflow/components/Template/TemplateNode/TaskForm/TaskForm.tsx:74,101-119`),
whose `DataDrivenInput` dispatches on each parameter's `type`, with local wrappers for text inputs, text areas and
editors (`TaskForm.tsx:111-115`). The vocabulary is `InputType` (`src/Constants/index.ts:91-109`); the Task Manager's
field form offers it plus `time`
(`src/Components/TemplateConfigModal/TemplateConfigModalContent/TemplateConfigModalContent.tsx:23-39`).

| Input type | Renders as | Notes |
| ---------- | ---------- | ----- |
| `boolean` | add-ons `Toggle` | Vertical orientation (`src/Features/Reactflow/components/shared/inputs.tsx:159-161`). |
| `email`, `password`, `text`, `time`, `url` | add-ons `TextInput`, through the local `AutoSuggestInput` | `password` renders a masked field. |
| `number` | the same, `type="number"` | `min`/`max` feed DynamicFormik's yup schema. |
| `select` | add-ons `ComboBox` | Options are `{ key, value }` pairs. |
| `textarea` | add-ons `TextArea`, through the local `TextAreaSuggestInput` | |
| `texteditor`, `texteditor::{javascript,shell,text,yaml}` | `TextEditorModal` | Syntax mode from the suffix. |
| `slider` | `src/Components/Slider/Slider.tsx:49` | Carbon `Slider` plus a number input; reads `min`/`max`/`step`. |

`DataDrivenInput` has no branch for `slider`, so a slider parameter reaches the local component through its
`customComponent` escape hatch (`src/Utils/paramsHelper.ts:35-39`). It writes its value back as a **string**, like every
parameter (`src/Components/Slider/SliderInput.tsx:59`), clamped to `[min, max]` and snapped to `step` with the
floating-point residue of fractional steps trimmed (`src/Components/Slider/Slider.tsx:23-27,82-95`).

`min`, `max` and `step` are nullable numbers on the parameter model (`src/Types/index.tsx:151-156`), as on the backend;
only `slider` uses all three, and the field form shows them for that type alone, seeding 0 to 100 in steps of 1
(`TemplateConfigModalContent.tsx:42,85-89,177`).

### Task palette icons and categories

The editor palette groups tasks by the catalogue's free-text `category`, with `workflow` first and the rest
alphabetical (`src/Features/WorkflowEditor/Designer/Tasks/Tasks.tsx:164-182`). The task's `icon` field is a key into
one shared icon map (`src/Utils/taskIcons.tsx:27`), used by the palette tile
(`src/Features/WorkflowEditor/Designer/Tasks/Task/Task.tsx:23`), the canvas node
(`src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.tsx:263-267`) and the palette's "Filter by
Task Type" list. A task whose `icon` is not in the map falls back to Carbon's `Bee`, so **an icon key must exist here
before the backend seeds a task that uses it**.

| Icon key (`task.icon`) | Carbon icon | Typical category |
| ---------------------- | ----------- | ---------------- |
| `AI` | `MachineLearningModel` (`src/Utils/taskIcons.tsx:38`) | `AI`: the `ai-prompt` and `ai-agent` tasks |
| `API/HTTP call` | `Api` | `Utilities` |
| `Automated task` | `Rocket` | any |
| `Code`, `Terminal` | `Code`, `Terminal` | `Utilities` |
| `Message` | `Chat` | `Communication with Slack` |
| … | see `taskIcons` for the full list | |

Node type and icon are separate: `ai` is its own node type (`src/Constants/index.ts:125`) mapped to a thin wrapper
over the Template node (`src/Features/Reactflow/Reactflow.tsx:95`) that only re-tints the border with the purple
accent (`src/Features/Reactflow/components/Ai/AiNode.module.scss:6-8`). A new node type MUST be added to all three of
`markerTypes`, `edgeTypes` and `nodeTypes`, which are keyed exhaustively off `NodeType`
(`src/Features/Reactflow/Reactflow.tsx:36,66,93`).

### Task configuration modal

The task edit modal is the add-ons `ComposedModal` at its default size, laid out like Carbon's tearsheet header so the
fields get the height: the title `Edit <task>`, the description on one line (clipped, the full text as its `title`;
`src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.tsx:111-119`), then the header block every task
form shares (`src/Features/Reactflow/components/shared/TaskFormTabs.tsx:38-60`): the always-editable **Task name** field
and the tab list flush against the block's bottom border. The body has no padding above the block (each form's
`.container` zeroes it) and the tab panel starts `$spacing-05` below the bar, so the first field starts about 212 px
down (255 px before).

| Tab | Shown for | Holds |
| --- | --- | --- |
| Parameters | every task | the task's own inputs; a custom or script task's editable result list (`src/Features/Reactflow/components/shared/inputs.tsx`, `ResultsInput`) |
| Configure | a type that can run for each item (`template`, `script`, `custom`, `ai`, `generic`, `uploadartifact`, `downloadartifact`; `src/Features/Reactflow/components/shared/foreach.ts:24-32`) | how the task runs ("Run for each item") |
| Results | a task whose catalogue entry declares results | each result's name in monospace and its description, read-only (`TaskFormTabs.tsx`, `ResultsList`) |

- The tabs sit inside one `ModalForm`/`DynamicFormik` form, so Apply saves every tab and validation is shared. A type
  with no Configure tab and no declared results has no tab bar: the name alone above its inputs.
- A tab label MUST show state the user cannot see from the other tab: a purple `Tag` ("For each") while a setting on
  that tab is on, and a `WarningFilled` icon in the error colour, with an `aria-label` "<Tab> has an error", while
  one of its fields is invalid. Apply never fails for a reason on a tab the user is not looking at.
- The modal resets button padding, so the tab list restores Carbon's tab padding itself
  (`src/Features/Reactflow/components/shared/TaskFormTabs.module.scss`).

### Palette entry points

A palette item MAY be an entry point to a setting rather than a node type of its own. "For each" is listed first in
the Workflow category (`src/Features/WorkflowEditor/Designer/Tasks/Tasks.tsx:24-32,201`); dropping it opens a
`ComposedModal`, "Repeat a task for each item", that picks the task and its items and then opens that task's normal
form with the setting on (`src/Features/Reactflow/components/Foreach/ForeachModal.tsx:46-51,67-70`). What lands on
the canvas is an ordinary task. The palette item and the Configure toggle store the same thing, so nothing records
which path made it.

### Fanned-out tasks on the canvas and in the run

- **Canvas node:** a task with `foreach` set is drawn as a stack of cards: two offset copies behind the node in
  `--flow-switch-primary`, and a badge at its top-left reusing `.badgeContainer`
  (`src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.module.scss:279-316`). In run mode the badge
  reports progress, "For each · 2 of 3 succeeded", or "For each · 0 items"
  (`src/Features/Reactflow/components/Template/TemplateNode/TemplateNode.tsx:197-207`). The node's status is the
  parent task run's, found by name while skipping items (`src/Utils/taskRunHelper.ts:22-27`).
- **Task log:** item task runs are grouped under their task, never listed flat. The task's entry shows a purple "For
  each" `Tag` and a summary ("3 items · 1 succeeded · 1 running · 1 failed", or the failure message when it never
  fanned out), and a ghost "Show items" button with `aria-expanded` lists each item's index, value, status, the reason
  when it failed, duration and attempt, and its own "View Log"
  (`src/Features/WorkflowRun/TaskRunList/TaskRunItem/RunTaskItem.tsx:117-128,181-192,315-344`). The parent has no pod,
  so it offers no log of its own (`RunTaskItem.tsx:170`). Whether a task fans out comes from the workflow's node
  definition, not from whether items exist (`src/Utils/taskRunHelper.ts:17-19`).
- **Task log actions:** an entry's actions ("View Details", "View Log", "Show items") sit on one wrapping flex row
  with a gap, and every entry is as tall as its content
  (`src/Features/WorkflowRun/TaskRunList/TaskRunItem/runTaskItem.module.scss:5,248-254`).

### Canvas node ports

React Flow v12 translates a side handle half its width outward. Flow's ports are already offset by their full width
(the `.port` rules in the Start, End and Template node styles), so the global stylesheet keeps only the vertical
centring and every port touches its node (`src/Features/Reactflow/styles.scss:24-30`). A new node type's ports MUST
follow the same offset rather than re-adding the transform.

### Class-name conventions

- Carbon component classes are prefixed **`cds--`** (v11). Overrides are scoped under `#app`, as `_base.scss` does for
  a few Carbon internals (`src/Styles/_base.scss:58`); modals render in a portal outside `#app`.
- New styles go in a co-located `*.module.scss`. A few older components still use global `c-` classes (the slider,
  `src/Components/Slider/Slider.tsx:124-142`).

## Data visualisation

Charts use `@carbon/charts-react`, already themed to Carbon (the Insights runs-per-day chart,
`src/Features/Insights/RunsPerDayChart.tsx:2-3`); a second charting library is not added. A chart that needs literal
colours reads them off the status tokens at mount (`src/Features/Insights/statusColors.ts:14-20`).

## Motion

Flow is a productivity tool used many times a day, so motion is decided by frequency before it is styled: an interaction
a person triggers constantly gets no animation, a daily one gets a fast subtle one, and only first-run or empty-state
moments may be expressive. New motion takes its values from Carbon's motion tokens; some older stylesheets still carry
literal durations (`src/Components/CreateWorkflow/CreateWorkflowContent/createWorkflow.module.scss:35`).

| Rule | Value | Where |
| --- | --- | --- |
| Hover on a tile, row or card changes background only | `motion.$duration-fast-02` (110 ms) with `motion.motion(standard, productive)` | `src/Features/Home/pulseTiles.module.scss:22`, and every Home card and row |
| Nothing animates on mount | none | Home is server-rendered; text, cards and numbers appear at once |
| Status never pulses, numbers never count up, nothing scales on hover, lists never stagger | none | |
| Reduced motion is honoured everywhere | one global `prefers-reduced-motion: reduce` rule sets every animation and transition to 0.01 ms | `src/Styles/_base.scss:32-41` |

Carbon components carry their own transitions and do not check the preference themselves, which is why the
reduced-motion rule is global and lives in the base stylesheet, not per component.

## Pages

### Home

The landing page (`src/Features/Home/Home.tsx`) is a rollup of every workspace the caller belongs to: a hero band
with the day's one-sentence summary, four "pulse" tiles that link into Activity, Actions, Schedules and the workspace
list (`src/Features/Home/PulseTiles.tsx:66-115`), the actions waiting on the person with Approve and Reject inline,
the workspace cards, the five newest runs, the templates and a short Learn list. A first run (no workspaces) shows a
three-step checklist and four concept tiles instead. Its read fans out per workspace from a route loader
(`src/Features/Home/homeLoader.ts:26-27,163-168`). The hero's eyebrow carries the platform's configured name from the
app context (`src/Features/Home/Home.tsx:294`); the product name is never a literal in the page.

### Insights

The Insights page (`src/Features/Insights/Insights.tsx:213-237`) answers, in order: is it reliable, is it getting
slower, what fails and why, where does the time go. In `src/Features/Insights/`:

| Part | Shape |
| --- | --- |
| Headline tiles | Four, each with a previous-period delta coloured by whether the change is welcome (`HeadlineTiles.tsx`) |
| Runs per day | Stacked bars by outcome, never a smoothed line, because counts are discrete (`RunsPerDayChart.tsx`) |
| Model usage | When the period ran an `ai` task: total tokens, cost when any task reported one, one row per model (`UsagePanel.tsx`) |
| Workflow table | One row per workflow, worst success rate first; the selected row's detail (where the time goes, why it fails, model usage) renders inside the panel under the table as tinted cards, and a line asks to select a row when none is (`WorkflowTable.tsx`, `WorkflowDetail.tsx`) |
| Duration spread | A p5 to p95 bar with a p50 mark on a log scale, the run timeout marked, in place of a scatter of every run (`DurationSpread.tsx`) |
| Task runs | The period's task runs grouped by task (the default), task type, workflow, model, for-each item or a run label, over the whole period or per hour, day or month: runs, failures, mean, p50, p95 and max duration, and tokens and cost where reported (`TaskBreakdown.tsx:17-39`) |

The chart library takes literal colours, so the outcome colours are read off the status tokens at mount, with the
theme's values as fallbacks (`statusColors.ts`); everything else is tokens, and outcomes with no runs stay out of
legends. Run labels are a typed `key=value` filter, applied on Enter or blur so typing does not reload the page
(`Insights.tsx:420-436`).

### Admin

The platform's administration is one side-nav link, "Admin" with the cog, below a divider that sets it apart from the
workspace's "Manage workspace" (`service-core/src/main/java/io/boomerang/core/NavigationService.java:158-179`), to one
tabbed page (`src/Features/Manage/Manage.tsx`): Settings, Workspaces, Users, Parameters, Tokens, Tasks and Audit, each a
nested route with its own loader (`app/routes.ts:53-62`), shown only when the caller's grants and the feature flags
allow it (`src/Features/Manage/manageTabs.ts:29-35`); the root sends the caller to the first tab they may see. The
layout's header carries the breadcrumb, title and tab row, so a tab's page starts with its controls. A user's detail
page stays outside the tabs, titled with the user's name (`app/routes.ts:63`).

Two tabs have their own shape:

- **Settings** is one group at a time (`/admin/settings/:group`): the groups in a left section list (`FeatureSideNav`,
  as on the workflow Configure screen), the group's name and description above a single-column form, and a save bar at
  the bottom that wakes only when something changed (`src/Features/Settings/SettingsSection/SettingsSection.tsx`). A
  group's name is a plain noun (Quotas, Integrations, Run limits) and its description one sentence, both seeded.
- **Audit** follows the list-page style: count tiles (`src/Components/StatTile`), the capture configuration as one line
  linking to Settings, the labelled filter row, and the table in a rounded panel with the outcome as an icon plus its
  name (`src/Features/Audit/Audit.tsx`).

Workflow templates have no page: they are seeded content, listed on Home, with their catalogue in the public docs.

## Browser support

The production build targets every maintained browser above 0.2% of usage except Opera Mini, so current Chrome, Firefox,
Edge and Safari are supported and Internet Explorer is not; development targets the latest Chrome, Firefox and Safari
(`package.json:131-142`, `browserslist`).

## Accessibility and how tests locate things

Carbon components expose their accessible names through their label props, and assistive technology and the end-to-end
suite both depend on them: `labelText` on inputs, `iconDescription` on icon-only buttons, `title` or text on tiles and
headings. A control without one is invisible to a screen reader and to a `getByRole`/`getByLabel` locator. Two
conventions the canvas editor follows and new UI MUST keep:

- Every interactive element carries a role and a name. The palette entries are `role="option"` named by the task's
  display name (`src/Features/WorkflowEditor/Designer/Tasks/Task/Task.tsx:33`); icon-only buttons carry
  `iconDescription` (the version buttons, `src/Features/WorkflowEditor/Header/Header.tsx:110,130`) or an `alt`
  (`src/Components/WorkflowEditButton/WorkflowEditButton.tsx:14`: "Workflow edit button").
- Form inputs are named by their parameter label, not their key: `textbox "Shell Interpreter"`, not `#shell`
  (`src/Features/Reactflow/components/shared/inputs.tsx:30`, `e2e/tests/foreach-ui.spec.ts:118`).

The end-to-end suite (`e2e/`) locates elements in this order, which doubles as an accessibility check: a journey
that can only be written with CSS selectors is a journey a screen-reader user cannot follow.

1. `getByRole(role, { name })` / `getByLabel(...)`: the accessible contract, preferred.
2. `getByTestId(...)`: for containers with no natural role (list rows, cards); `data-testid` values are part of the
   UI's test contract and renames MUST update the suite.
3. CSS or structural selectors: only for third-party internals with no accessible surface (the
   `.react-flow__node-*`/`.react-flow__handle` canvas classes, `.CodeMirror`), each with a comment saying why.

When a locator fails, read the Playwright page snapshot in the failure's `error-context.md`: it is the accessibility
tree, so it shows what the test should target and what a screen reader gets.

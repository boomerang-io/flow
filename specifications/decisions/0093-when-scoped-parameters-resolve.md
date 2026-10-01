# 0093 — Scoped parameters resolve from their stores, and untrusted text is never expanded

**Status:** proposed · **Date:** 2026-10-01

## Context

Global, workspace and workflow-context values are copied into three run annotations at submit and read back
at run start and at every task admission (`task-runtime.md`, "What the container receives"). The copy dates
from flow and the engine being separate services; it puts every secured value on every run document and
reads engine input from `boomerang.io/*` annotations. Substitution also expands references inside inserted
values, including task results and trigger payloads, and secured global and workspace values are not
redacted on reads.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Today: snapshot in annotations | — | Secrets on every run; engine input in annotations |
| 2. Pull layers from their stores at run start and at each admission | Live values are acceptable within a run | A global or workspace edit, a token rotation or a settings URL change reaches tasks not yet admitted |
| 5. Resolve scoped references into every task run at run start | Every task must see one set of values | Resolved values, secrets included, on task runs that never execute and shown in Activity; text inserted at start becomes live at admission unless escaped; one write per task at start |

Both options drop the annotations, add no field to `RunParam`, `AbstractParam` or any run entity, and take
the workflow context from the run's revision. Three rules apply under either:

- Task results are inserted as written; a `$(...)` inside one is never expanded.
- On a webhook, event or GitHub run, a run param whose value differs from the revision default came from
  the payload and is inserted as written. Values from people, schedules and authenticated API callers resolve.
- Password-typed global and workspace values join the redaction union (current values; four characters or
  more, as decision 0043).

## Decision

Proposed: option 2 with the three rules. Option 5's one advantage, a single set of values per run, has no
reported need, and it widens where secrets are stored. Pending the maintainer's choice.

## Consequences

- No parameter values on the run document; task runs hold what admitted tasks used. A rotated secret is no longer scrubbed from runs that used the old value; resolving secrets at dispatch
  (issue #452) removes stored copies altogether.
- Revisit option 5 if a run is shown to fail because a parameter changed while it ran.

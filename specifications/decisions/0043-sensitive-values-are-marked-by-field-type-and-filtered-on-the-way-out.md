# 0043 — Sensitive values are marked by field type and filtered on the way out

**Status:** accepted · **Date:** 2026-08-25

## Context

Password-typed parameters were already blanked on definition and configuration reads, but a WorkflowRun
or TaskRun response returned the resolved values in plain text, and a script that echoed one showed it in
the task log. The trust model is asymmetric: values are sensitive upward, from the engine to a UI or API
consumer, while plain delivery downward into the execution substrate is acceptable because only operators
reach it.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A new `sensitive` flag on the param and on the wire | The marker must travel with the value | A data-model change and a second source of truth beside the field type |
| B. Treat `type=password` as the marker and filter on the scoped reads and the log stream | The definition is the type authority | Every read that serves consumers must join against the definition |
| C. Encrypt at rest and mask in the container | The substrate is untrusted | Out of scope for the trust model above; tracked separately |

## Decision

B. `type=password` (`lib-common/src/main/java/io/boomerang/common/util/DataAdapterUtil.java:22`) is the
only marker. The workspace-scoped run `get` and `query` blank password-typed params by name and scrub the
resolved values from task params, spec fields and results
(`service-core/src/main/java/io/boomerang/workflow/WorkflowRunService.java:145-149,160-170,209`); the task
log stream is wrapped in `FilterValuesOutputStream` (`:339-346`). Engine and dispatcher reads stay
unfiltered because the runtime must see the real values. Extending the existing `filter*` helpers instead
of adding a field kept one source of truth.

## Consequences

- No new field, no migration; a password-typed param is redacted everywhere consumers read.
- A secret shorter than four characters is blanked by name but not value-scrubbed, and a value straddling a
  chunk boundary inside a single line over 64 KB can leak in the stream — accepted edges.
- Encryption at rest is a separate item (boomerang-io/flow#315).

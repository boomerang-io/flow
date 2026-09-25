# 0082 — A secret is a parameter type

**Status:** accepted · **Date:** 2026-09-25

## Context

"Sensitive values are marked by field type and filtered on the way out" made the `password` field the only
marker, so every consumer read had to join run params against the workflow revision and each catalogue
task's spec. A value sent as a secret on a run request, or a string built from a secret, had no marker at
all, and the dispatcher could not tell a secret from any other param when per-task secret injection lands.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. A side list of secret names on the TaskRun | The param model must not change | A second source of truth beside the params; a new stored field that every write must keep in step |
| B. A `sensitive: true` flag on each param | Secrets can be any shape | A flag that can disagree with the type; arrays and objects marked secret have no defined delivery |
| C. `secret` as a `ParamType` value, carried on the wire | Secrets are strings | One enum value and an existing field put on the wire; non-string secrets are rejected |

## Decision

C. `ParamType.secret` (`lib-common/src/main/java/io/boomerang/common/enums/ParamType.java:15`), derived
from the `password` field (`ParameterUtil.getRunParamType`), sent directly on a run request, or acquired
by a string that substitutes in a secret (`engine/ParameterManager.java:393`). Consumer reads redact by
type (`DataAdapterUtil.filterWorkflowRunValueByFieldType(run, ParamType)`). The type already lives on the
param, so this is the only option with one source of truth. The `password` field keeps its name - renaming
it would be a migration for a synonym.

## Consequences

- A secret is recognised without a definition join; the join and value scrub stay for older runs and for
  secrets that land in free text.
- Arrays and objects are never secret; one that takes in a secret relies on the value scrub.
- The global and workspace parameter layers carry no types, so a reference to one does not taint.
- Encryption at rest and a per-task Kubernetes Secret for the executors build on this type; neither exists yet.

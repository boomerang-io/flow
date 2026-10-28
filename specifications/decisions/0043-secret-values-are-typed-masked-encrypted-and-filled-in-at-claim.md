# 0043 — A secret is a parameter type: masked on the way out, encrypted at rest, and filled into the spec at claim

**Status:** accepted · **Date:** 2026-10-19

## Context

Secret values sit on every parameter layer, from global parameters to task runs. They reach the container plainly
(only operators reach the substrate) but never a UI or API consumer, must not be stored in plaintext, and every layer
must read and write them alike. The `password` field alone marks nothing sent on a run or built from a secret.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Marker: a `sensitive` flag, or a side list of secret names | Secrets can be any shape | A second source of truth beside the type |
| Marker: `secret` as a `ParamType`, carried on the wire | Secrets are strings | Non-string secrets are rejected |
| Spec: ciphertext tokens, or `$PARAM_X` references in place of values | The dispatcher can decrypt, or every task is a shell | The key reaches every dispatcher, or a `command` without a shell breaks |
| Spec: keep references, fill a copy at claim | A short drift window is acceptable | Global and workspace values in a spec are read at claim, not admission |
| Reads and writes: a write-only value plus a `secret {set, updatedAt}` marker | Clients must detect rotation | A field on the shared param models |

## Decision

`ParamType.secret` (`service-core/src/main/java/io/boomerang/common/enums/ParamType.java:15`) comes from a `password`
field (`common/util/ParameterUtil.java:86-90`), a run request (a non-string value is refused,
`workflow/WorkflowService.java:1925-1937`), or a string that substitutes one in (`engine/ParameterManager.java:196-201`).
Consumer reads redact by type and scrub resolved values from free text and the log stream
(`workflow/WorkflowRunService.java:199-223,535-552`); engine and dispatcher reads stay plain. A Mongo hook encrypts
typed secrets in the stored document and decrypts on load (`core/config/SecretValueMongoListener.java:62-131`).
Admission stores the resolved params, sealed, and leaves the spec's references; the claim fills a copy
(`engine/TaskRunService.java:362-378`, `dispatcher/DispatcherService.java:451-471`). Every layer writes by one rule,
`*****` or no value keeps, `""` clears, anything else replaces (`common/util/DataAdapterUtil.java:132-137`), and reads
by one: set reads `*****`, unset reads nothing (`:173-178`). Each choice keeps one source of truth and adds no stored field or protocol change.

## Consequences

- Arrays and objects are never secret and rely on the value scrub, which skips values under four characters and can
  leak across a chunk of one log line over 64 KB (`common/util/FilterValuesOutputStream.java:18-25`).
- A global or workspace value written straight into a spec can change before claim; one deleted meanwhile ends the
  task `failed` with `SpecFillFailed`. Revisit with versioned references if a reproduced incident calls for it.
- Plaintext stored before encryption stays until its next save; workflow templates and task results are not encrypted.

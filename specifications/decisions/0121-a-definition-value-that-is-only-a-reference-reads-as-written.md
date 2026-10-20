# 0121 — A definition value that is only a reference reads as written

**Status:** accepted · **Date:** 2026-10-19

## Context

Definition reads mask every `password`-typed value as `*****`, including a value that is only a Flow reference
such as `$(workflow.params.aiToken)`. A reference holds no secret, so masking it hid the wiring from authors, and
a client that compares definitions saw a change on every read: an embedding product re-applied every workflow on
every start until it learned to skip the marker. Saving the marker back already keeps the stored value.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Show a value that is exactly one Flow reference, with nothing around it, on definition reads | References carry no secret's text | None found: the param stays secret-typed, so what it resolves to is masked on every run read |
| B. Mask as today and document that `*****` means unchanged | Clients can be told | Every integrator rediscovers it |
| C. Do not stamp a reference-only node value `secret` at save | The referenced param is always itself a password | A reference to a param not declared as a password would then resolve, and show, unmasked |

## Decision

A. One predicate, `ParameterUtil.isSingleFlowReference` (`common/util/ParameterUtil.java:52`), accepts only the
reference forms the resolver substitutes (`$(params.x)`, `$(<scope>.params.x)`, `$(tasks.t.results.r)`, each with an
optional path) and never shell text such as `$(date)`; it shares its pattern and scopes with the resolver
(`ParameterUtil.REFERENCE_PATTERN`). The definition-read filters skip a value it accepts: workflow node and
schedule values (`DataAdapterUtil.filterDefinitionValueByFieldType`), and workflow, catalogue task, global and
workspace parameter defaults and values (`DataAdapterUtil.java:64-115`). Run reads, status events and the log and
value scrubs are unchanged, because a run's params hold resolved values.

## Consequences

- Authors see where a secret comes from; clients that diff definitions see stable values.
- Text around a reference (`Bearer $(params.token)`) is still masked: it may be partly a literal secret.
- A secret whose literal value happens to be a lone reference would read as written; Flow resolves such a value
  on a run, so it cannot be a usable secret.

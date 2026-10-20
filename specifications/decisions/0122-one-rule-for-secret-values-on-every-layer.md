# 0122 — One rule for secret values on every layer

**Status:** accepted · **Date:** 2026-10-19

## Context

Each layer that holds a secret read and kept it its own way: global parameters omitted the value, workflow
defaults read blank, nodes, schedules and runs read `*****` even when unset, and settings also took the
ciphertext back. A blank meant "keep" almost everywhere, so a secret could not be cleared, a client could not
tell a set secret from an unset one, and a run form left blank wiped a secret default.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Per-layer sentinels, as before | Each surface has one client | Every client relearns each layer; nothing can be cleared |
| B. A write-only value plus a read-only `secret {set, updatedAt, version}` marker (Grafana, GitHub, Terraform) | Clients must see that a secret changed | A field on the shared param models, stored per secret |
| C. One sentinel rule on every layer: `*****` or no value keeps, `""` clears, anything else replaces; a set secret reads `*****`, an unset one nothing | Clients must tell set from unset and clear a secret | No signal that a secret was rotated |

## Decision

C, because it needs no new field yet gives every layer the same read shape and a way to clear. One helper decides
every write (`DataAdapterUtil.keepSecretValue`, `common/util/DataAdapterUtil.java:132`) and one the read shape
(`maskSecretValue`, `:173`); global and workspace parameters, workflow defaults and nodes, catalogue task defaults,
schedules and settings all call them (`core/SettingsService.java:74`). A run request follows the same rule against
its defaults (`ParameterUtil.suppliedParams`, `ParameterUtil.java:215`). A marker with nothing stored is no value,
never the secret. The cipher recognises only its whole label and throws when it cannot encrypt
(`core/SecretCipher.java:38-60`).

## Consequences

- Every layer shows whether a secret is set, and `""` clears it; blank no longer keeps.
- A client that echoes a read keeps every secret; one that blanked untouched fields now clears them.
- No per-secret change signal: who changed a secret is the audit trail's to record. Revisit with B when a
  client needs to detect rotation without re-sending.

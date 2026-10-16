# 0100 — One key encrypts every secret at rest; the v3 pair is read once to re-encrypt

**Status:** accepted · **Date:** 2026-10-10

## Context

v3 encrypted secured settings with AES-CBC under a key derived from `mongo.encrypt.secret` and `mongo.encrypt.salt`
(`service-core/src/main/java/io/boomerang/core/model/EncryptionConfig.java:13,16`), and a v3 database upgraded in
place carries those ciphertexts. The settings cipher is moving to AES-GCM (#408), and secret parameters will be
encrypted at rest after #452. The question was which key each of these uses, and how the v3 values come across.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep one pair, reuse it for GCM and for parameters | The v3 key is acceptable to keep | A v3 instance that never set one keeps a blank-derived key forever; no rotation at upgrade |
| B. Two pairs: the v3 pair under its old name, read once; a new pair for everything v5 encrypts | The upgrade is the moment to rotate, and new installs must set a key | Two more properties; the re-encrypt unit reads both |
| C. A pair per store (settings, parameters) | Key separation is wanted | Three secrets to generate, inject and rotate, all derived the same way, for no gain |

## Decision

Option B. `mongo.encrypt.secret`/`salt` keep their names and now mean the v3 pair, read only by the re-encrypt
change unit on its one run. `flow.encrypt.secret`/`salt` is the one key v5 encrypts with: settings now, the
`password`-typed global and workspace parameters and `secret`-typed run parameters when they move to rest. A blank
v5 key refuses startup (`core/model/EncryptionConfig.java:27-30`); a v3 pair that fails to decrypt stops the
migration rather than leaving a credential unreadable, unless the operator opts into blanking those values
(decision 0106). The one reason that decided it between A and B: the re-encrypt pass already decrypts with one
key and encrypts with another, so an operator who supplies a different v5 pair rotates at upgrade for nothing
extra. Both pairs default to the same `FLOW_ENCRYPT_*` values (decision 0106), so rotation is the operator's
choice, not automatic. Implemented by
`service-core/src/main/java/io/boomerang/migration/_0022__ReencryptSettingsAesGcm.java` and
`core/model/EncryptionConfig.java:19-30`.

## Consequences

- Operators manage one live secret; a v3 upgrade keeps its pair unless a new one is set.
- Separation between stores, if ever wanted, is derived from the one key with a per-purpose label, never a new pair.
- A later key rotation reuses the re-encrypt unit's decrypt-old, encrypt-new pass.

# 0100 — One key encrypts every secret at rest, and a v3 install upgrades with its chart Secret as it is

**Status:** accepted · **Date:** 2026-10-14

## Context

Secured settings, secret parameter values and stored integration tokens are encrypted at rest. A v3 database upgraded
in place carries settings encrypted under `mongo.encrypt.*` with a fixed initialisation vector and no authentication tag.
v3's chart generated that pair into its Secret as `FLOW_ENCRYPT_SECRET`/`FLOW_ENCRYPT_SALT` (charts repository,
`bmrg-flow/templates/secrets.yaml:15-16,33-34`), and some installs no longer have it.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Keys: keep v3's pair for everything | v3's key is acceptable to keep | A v3 install that never set one keeps a blank-derived key; no rotation at upgrade |
| Keys: v3's pair read once to re-encrypt, one new key for everything v5 encrypts | New installs must set a key; an upgrade may rotate | Two more properties |
| Upgrade: no defaults; stop on any value that does not decrypt | Every operator maps both pairs by hand | The chart Secret fails as it is; a lost pair blocks the upgrade |
| Upgrade: both pairs default to `FLOW_ENCRYPT_*`; opt-in reset of values that do not decrypt | v3 Secrets are reused, and some are lost | No automatic rotation; a reset blanks credentials to re-enter |
| Upgrade: reset without asking | Lost pairs are common | A mistyped pair silently blanks every credential |

## Decision

One key, `flow.encrypt.secret`/`salt`, encrypts everything with authenticated AES-256-GCM (`Encryptors.delux`,
`service-core/src/main/java/io/boomerang/core/model/AESAlgorithm.java:49-52`), labelled `crypt_v1{AESGCM|…}`
(`core/SecretCipher.java:25`); a blank key refuses startup (`core/model/EncryptionConfig.java:19-32`).
`mongo.encrypt.*` means v3's pair and is read only by `migration/_0022__ReencryptSettingsAesGcm.java`, which
re-encrypts v3's values under the new key. Both pairs default to `FLOW_ENCRYPT_SECRET`/`FLOW_ENCRYPT_SALT`, and
`MONGO_ENCRYPT_*` overrides v3's pair alone (`service-core/src/main/resources/application.properties:134-140`). A
value that does not decrypt stops the migration unless `flow.migration.reset-unreadable-settings=true` blanks it and
logs the setting (`_0022__ReencryptSettingsAesGcm.java:95-114`). The pass already decrypts with one key and encrypts
with another, so rotating at upgrade costs nothing; a wrong pair looks exactly like a lost one, so blanking
credentials is the operator's explicit choice.

## Consequences

- Operators manage one live secret, and a v3 install upgrades with its chart Secret unchanged.
- Rotation at upgrade is the operator's choice: new `FLOW_ENCRYPT_*` values, with the old ones as `MONGO_ENCRYPT_*`.
- The reset is one-way: an install without its pair upgrades and re-enters its integration credentials.

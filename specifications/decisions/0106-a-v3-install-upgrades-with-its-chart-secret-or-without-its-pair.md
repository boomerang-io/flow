# 0106 — A v3 install upgrades with its chart Secret as it is, or without its pair

**Status:** accepted · **Date:** 2026-10-14

## Context

v3's chart generated the encryption pair into the release's Secret as `FLOW_ENCRYPT_SECRET` and `FLOW_ENCRYPT_SALT`
and handed them to the service as `mongo.encrypt.*` (charts `bmrg-flow/templates/secrets.yaml:10-16`,
`deployment-service.yaml:36`). In v5 those variable names bind to the new key, `flow.encrypt.*`, so a reused Secret
left the v3 pair blank and the re-encrypt unit stopped. An install that has lost the Secret could not upgrade at all.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. No defaults; stop on any value that does not decrypt | Every operator maps both pairs by hand and still has v3's | The chart Secret fails as it is; a lost pair blocks the upgrade for good |
| B. Both pairs default to `FLOW_ENCRYPT_*`; opt-in reset of values that do not decrypt | v3 Secrets are reused, and some installs have lost theirs | The upgrade keeps v3's key unless the operator sets a new one; a reset blanks credentials to enter again |
| C. As B, but reset without asking | Lost pairs are the common case | A mistyped pair silently blanks every credential |

## Decision

Option B. `flow.encrypt.*` and `mongo.encrypt.*` both default to `FLOW_ENCRYPT_SECRET`/`FLOW_ENCRYPT_SALT`
(`service-core/src/main/resources/application.properties:137-143`); `MONGO_ENCRYPT_*` overrides the v3 pair alone.
`flow.migration.reset-unreadable-settings=true` makes `_0022__ReencryptSettingsAesGcm` blank each v3 value that does
not decrypt, logging it by setting, instead of stopping. The one reason it beat C: a wrong pair is
indistinguishable from a lost one, so blanking credentials must be the operator's explicit choice.

## Consequences

- A v3 install upgrades with its chart Secret unchanged, and one without its pair upgrades and re-enters its
  integration credentials.
- Rotation is no longer automatic at upgrade, as decision 0100 intended: an operator rotates by setting new
  `FLOW_ENCRYPT_*` values and the old ones as `MONGO_ENCRYPT_*`.
- The reset is one-way; the docs must say so before an operator sets it.

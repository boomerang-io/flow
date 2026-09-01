# 0024 — The v3 upgrade path is kept in place through the change-unit chain

**Status:** accepted · **Date:** 2026-09-01

## Context

The loader chain migrates a v3 database directly to the v5 shape (`_0004` – `_0014`), repairs v4 installs
(`_0024`, `_0025`) and then applies every later unit. Collapsing it to a clean v5 baseline was proposed as a
simplification, at the same time as v4 client and API compatibility was being dropped.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Collapse the chain to a fresh v5 baseline | Every install is new or exported and re-imported | Existing v3 and v4 installs cannot upgrade in place |
| B. Keep the full chain and gate legacy units on the detected install generation | Installs upgrade in place | Every unit must stay idempotent and generation-gated forever; the chain only grows |

## Decision

Option B. Installs will upgrade in place from v3 to v5, so the chain is production upgrade code. `_0001` detects
the install generation from the legacy Mongock changelog and records it once
(`service-loader/src/main/java/io/boomerang/loader/migration/InstallGeneration.java:31-54`,
`LegacyGenerationMarker.java:28-41`); v3-only and v4-only units read the marker and no-op elsewhere.
`V3DumpMigrationTest` exercises the chain against a real v3 dump
(`service-loader/src/test/java/io/boomerang/loader/V3DumpMigrationTest.java:39-40`). Data-migration compatibility
and client compatibility are separate axes: the former is kept, the latter is dropped.

## Consequences

- The chain MUST NOT be collapsed or renumbered; schema changes are appended as new units.
- v4 client inputs (the `team` alias, the shadow permission mode) are gone; the `access_token` URL parameter stays as a product feature for webhook senders.
- Every new unit MUST be idempotent and safe on all three generations.

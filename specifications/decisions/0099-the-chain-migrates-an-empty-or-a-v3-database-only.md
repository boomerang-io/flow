# 0099 — The change-unit chain migrates an empty or a v3 database, and nothing else

**Status:** accepted · **Date:** 2026-10-01

## Context

The chain had grown to 54 units because every reshaping made during v5's development was appended as a new unit, so
that a database at any earlier point could catch up. No v5 install exists outside betas, which are reset, and no v4
install exists; several v3 installs do. Most units therefore only moved a beta or v4 shape forward, or patched what an
earlier unit had just written.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep appending; never collapse | Databases exist at many earlier points | Every unit stays forever; fresh and v3 paths end with different indexes; defects hide among the patches |
| B. Support empty, v3 and v4 | v4 installs exist | About 18 v4-only conversions stay, proven only by synthetic fixtures |
| C. Support empty and v3 only; refuse anything else | Only fresh installs and v3 upgrades exist | A v4 or beta database cannot be upgraded in place |

## Decision

Option C. Units that patched an earlier unit's output were folded into it: settings are rebuilt from the seed, the
catalogue, user and graph fixes moved into the v3 units, and all indexes are one inventory built last
(`service-core/src/main/java/io/boomerang/migration/`). The first unit refuses a v4 or beta database before anything is
written. `V3DumpMigrationTest` runs the chain against a real v3 dump and pins what an upgraded install ends with.

## Consequences

- An empty and an upgraded database end with the same settings shape and the same indexes.
- The v3 units remain production upgrade code: they may be edited, but never dropped while v3 installs exist.
- From the first release, schema changes are appended as new units again; the chain is only flattened while no
  release has shipped it.

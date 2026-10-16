# 0023 — Indexes are created by migration change units, not entity annotations

**Status:** accepted · **Date:** 2026-08-14

## Context

The entities carried `@Indexed` and `@CompoundIndex` annotations, and the migration chain also created indexes.
Before the merge, the flow side ran with `spring.data.mongodb.auto-index-creation=true` and the engine side with
it unset, so one process created a second, differently shaped index set on first query while the other created
none.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Entity annotations create indexes at startup | One process, small collections | Index builds on a hot collection at boot; two authorities that drift; no unique-index dedupe step |
| B. Migration change units are the sole authority; the entities carry no index annotation | Indexes must be built once, before the application serves, with data deduped first | A reader of an entity must open the inventory to see what is indexed |

## Decision

Option B. `spring.data.mongodb.auto-index-creation=false` is pinned explicitly
(`service-core/src/main/resources/application.properties:76`), and no entity carries an index annotation. One unit,
`_0021__Indexes`, builds the whole inventory last, after every data unit and seed
(`service-core/src/main/java/io/boomerang/migration/_0021__Indexes.java:34-41`), creating each index by name
through `MigrationUtils.ensureIndex` (`migration/MigrationUtils.java:30`); each unique index is preceded by a
dedupe of the collection it guards (`_0021__Indexes.java:194-201`).

## Consequences

- The index inventory is read from `_0021__Indexes`, never from the entities.
- A new query that needs an index MUST ship with a change unit; until the first release ships the chain, that
  is an entry in the one inventory (decision 0099).
- An entity that gains `@Indexed` or `@CompoundIndex` is a review error: the annotation would be inert and would
  mislead the next reader about what the database enforces.

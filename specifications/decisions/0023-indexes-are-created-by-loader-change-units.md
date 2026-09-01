# 0023 — Indexes are created by loader change units, not entity annotations

**Status:** accepted · **Date:** 2026-08-14

## Context

The entities carry `@Indexed` and `@CompoundIndex` annotations, and the loader also creates indexes. Before the
merge, the flow side ran with `spring.data.mongodb.auto-index-creation=true` and the engine side with it unset, so
one process created a second, differently shaped index set on first query while the other created none.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Entity annotations create indexes at startup | One process, small collections | Index builds on a hot collection at boot; two authorities that drift; no unique-index dedupe step |
| B. Loader change units are the sole authority; annotations are inert | Indexes must be built once, before the application, with data deduped first | Annotations remain in the code as documentation and can mislead a reader |

## Decision

Option B. `spring.data.mongodb.auto-index-creation=false` is pinned explicitly
(`service-core/src/main/resources/application.properties:56`), so every annotation is inert. Index units create
each index by name through `MigrationUtils.ensureIndex`
(`service-loader/src/main/java/io/boomerang/loader/migration/MigrationUtils.java:30-33`); a unique index is
preceded by a dedupe step. The units are `_0017__RunIndexes`, `_0018__EventAndLockIndexes`, `_0019__DomainIndexes`,
`_0026__TokenIndexes`, `_0030__WorkspaceSearchIndexes`, `_0033__DefinitionIndexes`,
`_0036__RelationshipAndAuditIndexes` and `_0037__SweepIndexes`.

## Consequences

- The index inventory is read from the loader units, never from the entities.
- A new query that needs an index MUST ship with a new change unit.
- The annotations have drifted from the real set; removing them is housekeeping, not a behaviour change.

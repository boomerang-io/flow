# 0011 — Migrations run inside service-core as it starts, take an empty or v3 database, and build every index

**Status:** accepted · **Date:** 2026-10-01

## Context

Every replica shares one MongoDB, empty or a v3 install upgraded in place; no v4 install exists. The sweeps start at
context refresh and must never see a half-migrated database, and indexes and seeds need one ordered authority.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| Where: A. A separate loader image run as a pre-deploy Job | Migration must be a separately gated step | A fourth image and a chart hook; an embedder runs two containers |
| Where: B. In core after start (Flamingock's default `ApplicationRunner`) | Nothing starts work at refresh | The sweeps start 0–30 s after refresh, against a half-migrated database |
| Where: C. In core while the context starts (`INITIALIZING_BEAN`) | Background work must only see a migrated database | No HTTP while migrating; a startup probe must outlast the slowest unit; core's database user needs index rights |
| Indexes: D. Entity annotations at startup | One process, small collections | Builds on a hot collection at boot; two authorities drift; no dedupe first |

## Decision

C, and indexes built by a unit. `MigrationConfiguration` runs Flamingock on core's own `MongoClient`
(`migration/MigrationConfiguration.java:20-44`) with `flamingock.management-mode=INITIALIZING_BEAN`
(`service-core/src/main/resources/application.properties:83`), before the web server and the sweeps
(`event/OutboxDispatcher.java:64-66`, `engine/WorkflowWatcher.java:149-152`). Replicas starting together wait up to 30
minutes on the lock (`:84`), then skip what another applied; a failed unit fails startup.
`_0001__GuardAndDetectGeneration` refuses a v4 database, or one a 5.0 beta's separate loader migrated, before writing
anything (`migration/_0001__GuardAndDetectGeneration.java:47-63`). With `spring.data.mongodb.auto-index-creation=false`
(`application.properties:76`) and no index annotation on any entity, `_0021__Indexes` builds one inventory, the same on
an empty and an upgraded database, after every data unit and seed, deduping first where a unique index needs it
(`migration/_0021__Indexes.java:193-206`).

## Consequences

- Units `_0001` to `_0021` are the base and later changes are appended (`_0022` to `_0029`), so a database an earlier
  5.0 build migrated moves forward. A new index is an inventory entry plus a unit for existing databases (`_0029`); an
  index annotation on an entity is a review error.
- The v3 units are production upgrade code, never dropped while v3 installs exist; `V3DumpMigrationTest` runs them
  against a real v3 dump wherever one is present.
- Units take library types only (`MongoDatabase`, `Environment`): devtools' restart classloader breaks injection of
  project types. Revisit with a migrate-and-exit mode if a v3 upgrade outlasts a sensible startup probe.

# Service Loader

Database migrations for Boomerang Flow (DD-07), rewritten on
[Flamingock](https://www.flamingock.io) as a standalone (non-Spring) runner. This module owns
all v5+ schema changes: indexes, bounded backfills, and data reconciliation for the MongoDB
collections shared by the Flow services.

## How it runs

The loader runs as a **pre-deploy container/Job — one execution per deploy**, before the
service instances roll. It connects, applies any pending change units, and exits: `0` on
success, non-zero on any failure (so a failed migration halts the rollout). Flamingock's
migration lock makes a concurrent second execution wait-and-verify rather than double-apply.

```bash
java -jar service-loader.jar
```

Configuration (system property, or the environment variable fallback):

| Property                        | Env var                        | Meaning                                                        |
| ------------------------------- | ------------------------------ | -------------------------------------------------------------- |
| `flow.mongo.uri`                | `FLOW_MONGO_URI`               | MongoDB connection string (database name taken from the URI; defaults to `boomerang`) |
| `flow.mongo.collection.prefix`  | `FLOW_MONGO_COLLECTION_PREFIX` | Optional collection prefix, resolved exactly like the services' `MongoConfiguration.fullCollectionName` (prefix `flow` → `flow_task_runs`) |

Flamingock's audit history lives in `<prefix>sys_changelog_loader` (lock:
`<prefix>sys_lock_loader`) — separate from the legacy Mongock changelog
(`<prefix>sys_changelog_flow`), which is left untouched.

## Transition from the legacy flow-loader

During the deprecation window the legacy
[`boomerang-io/flow.loader`](https://github.com/boomerang-io/flow.loader) still seeds fresh
installations (collections, seed data, historical migrations). This module owns **v5+ changes
only**. The first change unit is a baseline: it detects an existing installation (legacy
changelog history or seeded workflows) and records the fact in the audit log for operator
visibility — it does not gate anything, because every change unit is idempotent regardless of
prior state.

## Authoring change units

- One class per change in `io.boomerang.loader.migration`, named `_<order>__<Behaviour>`
  (e.g. `_0002__TaskRunClaimAndSweepIndexes`), annotated
  `@Change(id, author, transactional = false)` + `@TargetSystem(id = "flow-mongodb")` with
  `@Apply` / `@Rollback` methods. `MongoDatabase` and `CollectionNames` are injectable.
- **Idempotent and existence-checked.** A change unit must be safe to run against any prior
  state: create-if-absent semantics, index creation tolerant of already-exists, dedupes that
  skip already-reconciled documents. The audit log is the record of execution, not the guard.
- **Resolve collection names through `CollectionNames`** — never hardcode a prefixed name.
- **No heavy backfills.** Long-running data rewrites belong to watcher due-work inside the
  services, never in the loader — the loader blocks the deploy for as long as it runs. The
  loader's write-backfills must stay small and enumerable (e.g. duplicate-group dedupes).
- The Flamingock annotation processor generates pipeline metadata at compile time; new change
  units are picked up automatically from the stage package.

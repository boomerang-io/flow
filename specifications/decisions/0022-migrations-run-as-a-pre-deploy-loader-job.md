# 0022 — Migrations run as a pre-deploy loader job on Flamingock, one execution per deploy

**Status:** accepted · **Date:** 2026-07-23

## Context

Schema and seed changes used to live in a separate loader repository on Mongock. With the merge of flow and engine
into one `service-core` deployable that runs N replicas, something has to decide which instance migrates, and the
migration code has to move with the entities it reshapes.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the separate loader repository and mechanism | Migrations change rarely | Entities and their migrations drift across two repositories |
| B. Run migrations inside `service-core` at boot | A single instance, or a migration lock is acceptable at boot | N replicas race on the same chain; a slow backfill blocks every boot |
| C. A `service-loader` module in this monorepo, run once per deploy as a pre-deploy job | Kubernetes-style deployments with an ordered job | One more image; the deployment MUST gate the application on the job |

## Decision

Option C. `service-loader` runs every pending change unit on Flamingock and exits non-zero on failure, so the
rollout halts before `service-core` boots against an unmigrated database
(`service-loader/src/main/java/io/boomerang/loader/LoaderApplication.java:15-20,44-63`). The compose stack gates
`service-core` on `service_completed_successfully` (`docker-compose.yml:124-125`). One execution per deploy removes
the "which instance runs it" question by construction. Every unit is existence-checked and idempotent; heavy data
backfills belong to watcher due-work, never to a loader-blocking unit.

## Consequences

- Entities and their migrations live in one repository and ship in one product tag.
- Running migrations in-app at boot MAY be revisited now that the merge is done; Flamingock's lock would then answer the N-instance question.
- Fresh installs and upgrades run the same chain; seeds are units too (`_0020` – `_0023`).

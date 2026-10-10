# 0098 — Migrations run inside service-core as it starts, before it serves

**Status:** accepted · **Date:** 2026-10-01

## Context

The change units ran from a separate `service-loader` image as a pre-deploy Job, chosen because replicas would race on
the chain and a slow unit would block every boot. Flamingock's lock now answers the race, and a unit only delays the
first boot after an upgrade. The Job cost a fourth image, a CI workflow, a compose gate and a chart hook, made anything
embedding Flow run two containers, and left core's tests copying the seeds by hand.

## Options

| Option | Fits when | Cost / risk |
| --- | --- | --- |
| A. Keep the pre-deploy Job | Operators want migration as a separately gated step | The costs above stay |
| B. Run in core after the context starts (Flamingock's default `ApplicationRunner`) | Nothing starts work at context refresh | The outbox and watcher sweeps start 0–30 s after refresh and would run against a half-migrated database |
| C. Run in core while the context starts (`INITIALIZING_BEAN`) | Background work must only see a migrated database | No HTTP while migrating, so a startup probe must cover the slowest unit; core's database user needs index rights |

## Decision

Option C. `MigrationConfiguration` wires Flamingock to core's own `MongoClient`
(`service-core/src/main/java/io/boomerang/migration/MigrationConfiguration.java`) and
`flamingock.management-mode=INITIALIZING_BEAN` (`service-core/src/main/resources/application.properties`) runs every
pending unit before the web server, the scheduler and the dispatcher API start. The one reason that decided it between B
and C: the outbox dispatcher and watchers are scheduled at context refresh (`OutboxDispatcher.java:63-64`,
`WorkflowWatcher.java:151-154`). Replicas that start together wait on the lock, then re-read the change log and skip
what another replica applied; a failed unit fails startup and a rolling update stalls with the old pods serving.

## Consequences

- One image fewer; the headless engine, the docker quickstart and products that embed Flow run one container.
- Core's tests can run the real chain instead of hand-copied seeds.
- Units take library types only (`MongoDatabase`, Spring's `Environment`): devtools' restart classloader breaks
  Flamingock's injection of project-defined types under `spring-boot:run`.
- Revisit with a "migrate and exit" mode of the same image if a timed v3 upgrade outlasts a sensible startup probe.

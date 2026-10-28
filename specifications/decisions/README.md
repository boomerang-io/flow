# Decisions

One file per decision, numbered. Until the first 5.0.0 release ships, a record is rewritten in place when the
code moves and a record a later one absorbs is deleted, so every record reads as how the system is; from that
release on, a record is never edited except to change **Status** (a reversed decision is kept and marked
`superseded by NNNN`). Use `TEMPLATE.md`. Keep each under 40 lines and refer to things by name, not by code.

| # | Decision | Status | Date |
| --- | --- | --- | --- |
| 0001 | [service-core is one deployable that holds the engine as a package, with boundaries kept by convention](0001-service-core-is-one-deployable-with-package-boundaries-by-convention.md) | accepted | 2026-08-14 |
| 0003 | [Team is Workspace and agent is dispatcher, and a rename crosses the wire only with a migration](0003-workspace-and-dispatcher-renames-cross-the-wire-only-with-a-migration.md) | accepted | 2026-08-18 |
| 0005 | [One monorepo and one product tag build every image, and the Java images run on Ubuntu's chiseled runtime](0005-one-monorepo-and-one-product-tag-build-every-image.md) | accepted | 2026-10-15 |
| 0010 | [Claims and contended transitions are compare-and-set writes, with no locks or version retries](0010-claims-and-transitions-are-compare-and-set-writes.md) | accepted | 2026-07-23 |
| 0012 | [Outbound status events go through an outbox, not a broker, and carry a run's identity and lifecycle, not the run](0012-outbound-status-events-go-through-an-outbox-with-a-thin-payload.md) | accepted | 2026-09-16 |
| 0013 | [Pause is a flag enforced at one admission gate, never a run status](0013-pause-is-a-flag-at-one-admission-gate.md) | accepted | 2026-08-13 |
| 0014 | [Timeouts are durable deadlines reaped by a periodic sweep, and a run's deadline counts execution, not waiting](0014-timeouts-are-durable-deadlines-reaped-by-a-sweep-and-count-execution-only.md) | accepted | 2026-10-16 |
| 0016 | [A retry is a new run that re-runs from a scope and copies the task runs it keeps](0016-a-retry-is-a-new-run-that-re-runs-from-a-scope.md) | accepted | 2026-10-16 |
| 0019 | [Deleted workflows and expired runs are removed by watcher sweeps that never touch live work](0019-deleted-workflows-and-expired-runs-are-removed-by-watcher-sweeps.md) | accepted | 2026-10-16 |
| 0020 | [Typed fields carry control and execution state; labels and annotations are metadata, and the server's labels sit under `boomerang.io/`](0020-typed-fields-carry-control-state-labels-and-annotations-are-metadata.md) | accepted | 2026-10-21 |
| 0021 | [Versioned documents use the subset pattern: a parent plus one revision document per version](0021-versioned-documents-use-the-subset-pattern.md) | accepted | 2026-08-14 |
| 0023 | [Migrations run inside service-core as it starts, take an empty or v3 database, and build every index](0023-migrations-run-in-service-core-take-empty-or-v3-and-build-every-index.md) | accepted | 2026-10-01 |
| 0030 | [Relationship checks use direct queries anchored on the caller, not an in-memory graph](0030-relationship-checks-use-direct-queries-anchored-on-the-caller.md) | accepted | 2026-07-23 |
| 0032 | [Deployment shape is two properties, security off runs as an unstored synthetic admin, and the webapp reads flags](0032-deployment-shape-is-properties-and-the-webapp-renders-from-flags.md) | accepted | 2026-10-15 |
| 0034 | [A global token skips the relationship walk, and one can be bootstrapped from configuration](0034-global-tokens-skip-the-walk-and-one-can-be-bootstrapped.md) | accepted | 2026-10-15 |
| 0035 | [Sign-in is one token exchange for every identity provider, with a session cookie thereafter](0035-unified-token-exchange-with-a-session-cookie-thereafter.md) | accepted | 2026-08-18 |
| 0040 | [Parameters reach a task by engine substitution and environment variables, within engine-enforced caps](0040-parameters-reach-tasks-by-engine-substitution-and-environment-variables.md) | accepted | 2026-08-25 |
| 0042 | [Flow's Kubernetes dispatcher runs tasks behind a `TaskExecutor`, one isolation tier per deployment, and cleans its own leftovers](0042-the-kubernetes-dispatcher-runs-tasks-behind-a-taskexecutor.md) | accepted | 2026-09-23 |
| 0043 | [A secret is a parameter type: masked on the way out, encrypted at rest, and filled into the spec at claim](0043-secret-values-are-typed-masked-encrypted-and-filled-in-at-claim.md) | accepted | 2026-10-19 |
| 0044 | [Parameter names match case-insensitively with colliding variants rejected at save, and an empty value is valid](0044-parameter-names-match-case-insensitively-and-an-empty-value-is-valid.md) | accepted | 2026-09-02 |
| 0050 | [The public run models expose `status` as the external field, keep `phase` beside it, and never carry execution state](0050-the-public-run-models-expose-status-and-phase-never-execution-state.md) | accepted | 2026-08-18 |
| 0052 | [One error response shape for API errors: an unknown reference is 404, a malformed request is 400](0052-one-error-shape-unknown-reference-404-malformed-request-400.md) | accepted | 2026-09-16 |
| 0060 | [Every instance does every job, and no coordination or queue machinery is built ahead of evidence](0060-every-instance-does-every-job-nothing-built-ahead-of-evidence.md) | accepted | 2026-08-18 |
| 0062 | [Custom HTTP client configuration is a product requirement every upgrade must preserve](0062-custom-http-client-configuration-is-a-product-requirement.md) | accepted | 2026-07-23 |
| 0064 | [A claim carries a lease from the moment it is made, renewed by one batched heartbeat per dispatcher](0064-a-claim-carries-a-lease-renewed-by-one-batched-heartbeat.md) | accepted | 2026-10-20 |
| 0065 | [A task ends with a typed `statusReason` beside its `statusMessage`](0065-typed-status-reason-on-task-end.md) | accepted | 2026-09-01 |
| 0067 | [One workspace per run, provisioned and released by the dispatcher; state across runs is a workflow-scoped artifact](0067-one-workspace-per-run-and-workflow-scoped-artifacts-across-runs.md) | accepted | 2026-10-13 |
| 0069 | [Submit starts the run by default; parking is the explicit opt-out](0069-submit-starts-the-run-by-default.md) | accepted | 2026-09-02 |
| 0070 | [Audit is flat per-event documents captured by annotation; monthly run quotas count them, and a workspace quota of 0 is unlimited](0070-audit-is-flat-events-run-quotas-count-them-and-a-zero-quota-is-unlimited.md) | accepted | 2026-10-15 |
| 0074 | [Authorization uses Spring Security's native model, and a REST handler is denied unless annotated](0074-spring-security-native-model-and-handlers-denied-unless-annotated.md) | accepted | 2026-09-14 |
| 0075 | [A child run is linked to its task by lineage, receives only its node's params, and hands back its results](0075-a-child-run-is-linked-by-lineage-gets-its-node-params-and-returns-results.md) | accepted | 2026-10-20 |
| 0077 | [Seeds carry no ids and match on natural keys, and workflow templates are seeded read-only content](0077-seeds-match-on-natural-keys-and-workflow-templates-are-read-only-content.md) | accepted | 2026-10-15 |
| 0078 | [An `ai` task is a first-class type executed as a pod from a Boomerang-published worker image](0078-an-ai-task-type-runs-a-boomerang-published-worker-as-a-pod.md) | accepted | 2026-09-16 |
| 0082 | [A task runs once per item through a `foreach` setting on the task](0082-foreach-fan-out-of-one-task.md) | accepted | 2026-09-26 |
| 0087 | [Every page shares one header, Home fans out from its loader, and administration is one Manage link](0087-one-page-header-home-fans-out-and-one-manage-link.md) | accepted | 2026-09-30 |
| 0089 | [Insights read the runs and task runs a workspace still holds and group task runs on one route; the audit trail serves quotas only](0089-insights-read-retained-runs-and-group-task-runs-on-one-route.md) | accepted | 2026-10-21 |
| 0091 | [Settings groups are named for what they control, storage defaults stay with the dispatcher, and settings and global parameters take a merge patch](0091-settings-groups-are-named-for-what-they-control-and-take-a-merge-patch.md) | accepted | 2026-10-19 |
| 0092 | [Scoped parameters resolve from their stores, inserted text is never expanded, and a lone reference reads as written](0092-scoped-parameters-resolve-from-their-stores.md) | accepted | 2026-10-19 |
| 0094 | [A dispatcher polls again at once, for its free slots only, narrowed by typed filters](0094-a-dispatcher-polls-for-its-free-slots-by-typed-filters.md) | accepted | 2026-10-06 |
| 0096 | [Starting is bounded apart from running, and a task that never started is handed back](0096-a-task-that-never-started-is-handed-back.md) | accepted | 2026-10-05 |
| 0100 | [One key encrypts every secret at rest, and a v3 install upgrades with its chart Secret as it is](0100-one-key-encrypts-every-secret-at-rest.md) | accepted | 2026-10-14 |
| 0102 | [A dispatcher is anything that implements the published contract, and is its own process only where a boundary requires one](0102-a-dispatcher-is-anything-that-implements-the-published-contract.md) | accepted | 2026-10-20 |
| 0103 | [Routes are grouped under one prefix per kind of caller, and a dispatcher route runs as the dispatcher's token](0103-one-route-prefix-per-caller-and-dispatcher-routes-run-as-its-token.md) | accepted | 2026-10-20 |
| 0112 | [A task log is served from its stored copy or from the route its dispatcher registered](0112-a-task-log-is-served-stored-first-then-from-the-dispatchers-registered-route.md) | accepted | 2026-10-15 |
| 0119 | [Task references are slugs on the API and ids in storage](0119-task-references-are-slugs-on-the-api-and-ids-in-storage.md) | accepted | 2026-10-19 |
| 0120 | [Flow hands out trace context; tasks export their own spans](0120-flow-hands-out-trace-context-and-tasks-export-their-own-spans.md) | accepted | 2026-10-19 |
| 0128 | [A run's workspace is its workflow's owner, and task defaults land on the task run](0128-a-runs-workspace-is-its-workflows-owner.md) | accepted | 2026-10-21 |
| 0130 | [Task runs are queried across runs, as slim rows](0130-task-runs-are-queried-across-runs.md) | accepted | 2026-10-21 |
| 0131 | [The Java images ship a JDK AOT cache recorded at image build](0131-the-java-images-ship-an-aot-cache-recorded-at-image-build.md) | accepted | 2026-10-27 |
| 0132 | [An admission wakes the held polls by key, each on a condition of its own](0132-an-admission-wakes-the-polls-by-key.md) | accepted | 2026-10-28 |

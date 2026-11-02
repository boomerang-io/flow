# Decisions

One file per decision, numbered. Until the first 5.0.0 release ships, a record is rewritten in place when the
code moves and a record a later one absorbs is deleted, so every record reads as how the system is; from that
release on, a record is never edited except to change **Status** (a reversed decision is kept and marked
`superseded by NNNN`). Use `TEMPLATE.md`. Keep each under 40 lines and refer to things by name, not by code.

| # | Decision | Status | Date |
| --- | --- | --- | --- |
| 0001 | [service-core is one deployable that holds the engine as a package, with boundaries kept by convention](0001-service-core-is-one-deployable-with-package-boundaries-by-convention.md) | accepted | 2026-08-14 |
| 0002 | [Team is Workspace and agent is dispatcher, and a rename crosses the wire only with a migration](0002-workspace-and-dispatcher-renames-cross-the-wire-only-with-a-migration.md) | accepted | 2026-08-18 |
| 0003 | [One monorepo and one product tag build every image, on Ubuntu's chiseled Java runtime with an AOT cache](0003-one-monorepo-and-one-product-tag-build-every-image-on-a-chiseled-runtime-with-an-aot-cache.md) | accepted | 2026-10-29 |
| 0004 | [Claims and contended transitions are compare-and-set writes, with no locks or version retries](0004-claims-and-transitions-are-compare-and-set-writes.md) | accepted | 2026-07-23 |
| 0005 | [Outbound status events go through an outbox, not a broker, and carry a run's identity and lifecycle, not the run](0005-outbound-status-events-go-through-an-outbox-with-a-thin-payload.md) | accepted | 2026-09-16 |
| 0006 | [Pause is a flag enforced at one admission gate, never a run status](0006-pause-is-a-flag-at-one-admission-gate.md) | accepted | 2026-08-13 |
| 0007 | [Deadlines, due waits and retention are durable fields swept on every instance, and a run's clock counts execution](0007-deadlines-due-waits-and-retention-are-durable-fields-reaped-by-sweeps.md) | accepted | 2026-10-29 |
| 0008 | [A retry is a new run that re-runs from a scope, and a task node retries a reported failure on its own budget](0008-a-retry-is-a-new-run-from-a-scope-and-a-task-node-retries-a-failure-on-its-own-budget.md) | accepted | 2026-10-29 |
| 0009 | [Typed fields carry control and execution state; labels and annotations are metadata, and the server's labels sit under `boomerang.io/`](0009-typed-fields-carry-control-state-labels-and-annotations-are-metadata.md) | accepted | 2026-10-21 |
| 0010 | [Versioned documents use the subset pattern: a parent plus one revision document per version](0010-versioned-documents-use-the-subset-pattern.md) | accepted | 2026-08-14 |
| 0011 | [Migrations run inside service-core as it starts, take an empty or v3 database, and build every index](0011-migrations-run-in-service-core-take-empty-or-v3-and-build-every-index.md) | accepted | 2026-10-01 |
| 0012 | [Relationship checks use direct queries anchored on the caller, not an in-memory graph](0012-relationship-checks-use-direct-queries-anchored-on-the-caller.md) | accepted | 2026-07-23 |
| 0013 | [Deployment shape is two properties, security off runs as an unstored synthetic admin, and the webapp reads flags](0013-deployment-shape-is-properties-and-the-webapp-renders-from-flags.md) | accepted | 2026-10-15 |
| 0014 | [A global token skips the relationship walk, and one can be bootstrapped from configuration](0014-global-tokens-skip-the-walk-and-one-can-be-bootstrapped.md) | accepted | 2026-10-15 |
| 0015 | [Sign-in is one token exchange for every identity provider, with a session cookie thereafter](0015-unified-token-exchange-with-a-session-cookie-thereafter.md) | accepted | 2026-08-18 |
| 0016 | [Parameters are layered as in v3, resolved by the engine from their stores, and delivered as environment variables](0016-parameters-are-layered-resolved-by-the-engine-and-delivered-as-environment-variables.md) | accepted | 2026-10-29 |
| 0017 | [Flow's Kubernetes dispatcher runs tasks behind a `TaskExecutor`, one isolation tier per deployment, and cleans its own leftovers](0017-the-kubernetes-dispatcher-runs-tasks-behind-a-taskexecutor.md) | accepted | 2026-09-23 |
| 0018 | [A secret is a parameter type: masked on the way out, encrypted at rest, and filled into the spec at claim](0018-secret-values-are-typed-masked-encrypted-and-filled-in-at-claim.md) | accepted | 2026-10-19 |
| 0019 | [The public run models expose `status` as the external field, keep `phase` beside it, and never carry execution state](0019-the-public-run-models-expose-status-and-phase-never-execution-state.md) | accepted | 2026-08-18 |
| 0020 | [One error response shape for API errors: an unknown reference is 404, a malformed request is 400](0020-one-error-shape-unknown-reference-404-malformed-request-400.md) | accepted | 2026-09-16 |
| 0021 | [Every instance does every job, and no coordination or queue machinery is built ahead of evidence](0021-every-instance-does-every-job-nothing-built-ahead-of-evidence.md) | accepted | 2026-08-18 |
| 0022 | [Custom HTTP client configuration is a product requirement every upgrade must preserve](0022-custom-http-client-configuration-is-a-product-requirement.md) | accepted | 2026-07-23 |
| 0023 | [A claim carries a lease, polls run back to back for free slots and are woken by key, and work that never started is handed back](0023-claims-carry-a-lease-polls-run-back-to-back-and-a-task-that-never-started-is-handed-back.md) | accepted | 2026-10-29 |
| 0024 | [A task ends with a typed `statusReason` beside its `statusMessage`](0024-typed-status-reason-on-task-end.md) | accepted | 2026-09-01 |
| 0025 | [One workspace per run, provisioned and released by the dispatcher; a run's workspace is its workflow's owner](0025-one-workspace-per-run-provisioned-by-the-dispatcher-and-owned-by-the-workflows-workspace.md) | accepted | 2026-10-29 |
| 0026 | [Submit starts the run by default, and a workflow limits its in-flight runs at admission](0026-submit-starts-the-run-and-a-workflow-limits-its-in-flight-runs-at-admission.md) | accepted | 2026-10-29 |
| 0027 | [Audit is flat per-event documents captured by annotation; monthly run quotas count them, and a workspace quota of 0 is unlimited](0027-audit-is-flat-events-run-quotas-count-them-and-a-zero-quota-is-unlimited.md) | accepted | 2026-10-15 |
| 0028 | [Authorization uses Spring Security's native model, and a REST handler is denied unless annotated](0028-spring-security-native-model-and-handlers-denied-unless-annotated.md) | accepted | 2026-09-14 |
| 0029 | [A child run is linked to its task by lineage, receives only its node's params, and hands back its results](0029-a-child-run-is-linked-by-lineage-gets-its-node-params-and-returns-results.md) | accepted | 2026-10-20 |
| 0030 | [Seeds carry no ids and match on natural keys, and workflow templates are seeded read-only content](0030-seeds-match-on-natural-keys-and-workflow-templates-are-read-only-content.md) | accepted | 2026-10-15 |
| 0031 | [An `ai` task is a first-class type executed as a pod from a Boomerang-published worker image](0031-an-ai-task-type-runs-a-boomerang-published-worker-as-a-pod.md) | accepted | 2026-09-16 |
| 0032 | [A task runs once per item through a `foreach` setting on the task](0032-foreach-fan-out-of-one-task.md) | accepted | 2026-09-26 |
| 0033 | [Every page shares one header, Home fans out from its loader, and administration is one Manage link](0033-one-page-header-home-fans-out-and-one-manage-link.md) | accepted | 2026-09-30 |
| 0034 | [Insights read the runs and task runs a workspace still holds and group task runs on one route; the audit trail serves quotas only](0034-insights-read-retained-runs-and-group-task-runs-on-one-route.md) | accepted | 2026-10-21 |
| 0035 | [Settings groups are named for what they control, storage defaults stay with the dispatcher, and settings and global parameters take a merge patch](0035-settings-groups-are-named-for-what-they-control-and-take-a-merge-patch.md) | accepted | 2026-10-19 |
| 0036 | [One key encrypts every secret at rest, and a v3 install upgrades with its chart Secret as it is](0036-one-key-encrypts-every-secret-at-rest.md) | accepted | 2026-10-14 |
| 0037 | [A dispatcher is anything that implements the published contract, and is its own process only where a boundary requires one](0037-a-dispatcher-is-anything-that-implements-the-published-contract.md) | accepted | 2026-10-20 |
| 0038 | [Routes are grouped under one prefix per kind of caller, and a dispatcher route runs as the dispatcher's token](0038-one-route-prefix-per-caller-and-dispatcher-routes-run-as-its-token.md) | accepted | 2026-10-20 |
| 0039 | [A task log is served from its stored copy or from the route its dispatcher registered](0039-a-task-log-is-served-stored-first-then-from-the-dispatchers-registered-route.md) | accepted | 2026-10-15 |
| 0040 | [Task references are slugs on the API and ids in storage](0040-task-references-are-slugs-on-the-api-and-ids-in-storage.md) | accepted | 2026-10-19 |
| 0041 | [Flow hands out trace context; tasks export their own spans](0041-flow-hands-out-trace-context-and-tasks-export-their-own-spans.md) | accepted | 2026-10-19 |
| 0042 | [Task runs are queried across runs, as slim rows](0042-task-runs-are-queried-across-runs.md) | accepted | 2026-10-21 |
| 0043 | [An integration shares its credentials as managed workspace parameters](0043-an-integration-shares-its-credentials-as-managed-workspace-parameters.md) | accepted | 2026-10-28 |
| 0044 | [A run records the person or token that started it](0044-a-run-records-the-person-or-token-that-started-it.md) | accepted | 2026-10-29 |
| 0045 | [A run purge takes the lineage, cancels what is in flight, and waits for the dispatcher to let go](0045-a-run-purge-cancels-what-is-in-flight-and-waits-for-the-dispatcher.md) | accepted | 2026-10-29 |
| 0046 | [A template is a system-workspace workflow that other workspaces run by reference](0046-a-template-is-a-system-workspace-workflow-run-by-reference.md) | accepted | 2026-10-29 |
| 0047 | [A signed-in user holds a self-service baseline grant, and a setting says whether users create workspaces](0047-a-signed-in-user-holds-a-self-service-baseline-grant.md) | accepted | 2026-10-30 |
| 0048 | [A finally task is a task node linked from End with the `finally` condition](0048-a-finally-task-is-a-node-linked-from-end-with-the-finally-condition.md) | accepted | 2026-10-30 |

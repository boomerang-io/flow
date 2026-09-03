# Decisions

One file per decision, numbered, never edited after acceptance except to change **Status** (a reversed
decision is kept and marked `superseded by NNNN`). Use `TEMPLATE.md`. Keep each under 40 lines and refer to
things by name, not by code.

| # | Decision | Status | Date |
| --- | --- | --- | --- |
| 0001 | [Merge the flow and engine services into one deployable with a mode switch](0001-merge-flow-and-engine-into-one-deployable.md) | accepted | 2026-07-22 |
| 0002 | [Module boundaries are a convention, not Spring Modulith or ArchUnit](0002-module-boundaries-by-convention.md) | accepted | 2026-08-14 |
| 0003 | [Rename Team to Workspace at the v5 major](0003-rename-team-to-workspace.md) | accepted | 2026-07-22 |
| 0004 | [Rename the worker tier from agent to dispatcher](0004-rename-worker-tier-agent-to-dispatcher.md) | accepted | 2026-07-23 |
| 0005 | [Fold the web client into the monorepo](0005-fold-the-web-client-into-the-monorepo.md) | accepted | 2026-07-22 |
| 0006 | [One product version and tag builds every image](0006-one-product-version-and-tag-for-all-images.md) | accepted | 2026-07-22 |
| 0007 | [The merged module is named service-core; "engine" stays a package and a mode name](0007-merged-module-is-named-service-core.md) | accepted | 2026-07-23 |
| 0010 | [Work is claimed atomically with findAndModify, not with distributed locks](0010-atomic-claims-with-findandmodify.md) | accepted | 2026-07-23 |
| 0011 | [Contended transitions use compare-and-set, not @Version retries](0011-contended-transitions-use-compare-and-set.md) | accepted | 2026-07-22 |
| 0012 | [Outbound events go through a transactional outbox; no broker, no partitioning, no leader](0012-transactional-outbox-no-broker.md) | accepted | 2026-07-23 |
| 0013 | [Pause is a flag enforced at one admission gate, never a run status](0013-pause-is-a-flag-at-one-admission-gate.md) | accepted | 2026-08-13 |
| 0014 | [Timeouts and crash recovery use a periodic sweep, not per-run timers](0014-timeouts-by-periodic-sweep.md) | accepted | 2026-07-23 |
| 0015 | [One generic retry backoff; retry classes are not built until proven needed](0015-one-generic-retry-backoff.md) | accepted | 2026-08-18 |
| 0016 | [Supersede generations and a separate reconciler are not built; retry creates a new run](0016-no-supersede-generations-or-reconciler.md) | accepted | 2026-08-21 |
| 0017 | [The outbox creation-loss window is accepted](0017-outbox-creation-loss-window-accepted.md) | accepted | 2026-08-21 |
| 0018 | [Worker leases and lease renewal are deferred](0018-worker-leases-deferred.md) | superseded by 0064 | 2026-08-14 |
| 0019 | [Workflow delete is a tombstone swept by the watcher](0019-workflow-delete-is-a-tombstone.md) | accepted | 2026-07-23 |
| 0020 | [Control and execution state are typed fields; annotations and labels are metadata only](0020-control-state-is-typed-fields.md) | accepted | 2026-07-24 |
| 0021 | [Versioned documents use the subset pattern (parent plus revision documents)](0021-versioned-documents-use-the-subset-pattern.md) | accepted | 2023-08-14 |
| 0022 | [Migrations run as a pre-deploy loader job on Flamingock, one execution per deploy](0022-migrations-run-as-a-pre-deploy-loader-job.md) | accepted | 2026-07-23 |
| 0023 | [Indexes are created by loader change units, not entity annotations](0023-indexes-are-created-by-loader-change-units.md) | accepted | 2026-08-14 |
| 0024 | [The v3 upgrade path is kept in place through the change-unit chain](0024-v3-upgrade-path-kept-in-place-through-the-change-unit-chain.md) | accepted | 2026-09-01 |
| 0030 | [Relationship checks use direct queries anchored on the caller, not an in-memory graph](0030-relationship-checks-use-direct-queries-anchored-on-the-caller.md) | accepted | 2026-07-23 |
| 0031 | [Permission checks enforce: a mismatch is a real 403, not a shadow metric](0031-permission-checks-enforce-a-mismatch-is-a-real-403.md) | accepted | 2026-08-31 |
| 0032 | [With security off, requests run as a synthetic global admin that is never stored](0032-security-off-runs-as-a-synthetic-global-admin-that-is-never-stored.md) | accepted | 2026-08-26 |
| 0033 | [One property, `flow.security.enabled`, gates authentication and authorization, defaulting by mode](0033-one-property-gates-authentication-and-authorization-defaulting-by-mode.md) | accepted | 2026-08-15 |
| 0034 | [A global-scope token ignores the workspace path segment by design](0034-a-global-scope-token-ignores-the-workspace-path-segment.md) | accepted | 2026-08-24 |
| 0035 | [Sign-in is one token exchange for every identity provider, with a session cookie thereafter](0035-unified-token-exchange-with-a-session-cookie-thereafter.md) | accepted | 2026-08-18 |
| 0040 | [Parameters reach a task by engine-side substitution and environment variables](0040-parameters-reach-tasks-by-engine-substitution-and-environment-variables.md) | accepted | 2026-08-22 |
| 0041 | [Payload caps on parameters and results are enforced by the engine](0041-payload-caps-on-parameters-and-results-are-enforced-by-the-engine.md) | accepted | 2026-08-25 |
| 0042 | [One isolation tier per dispatcher deployment, no per-task tier](0042-one-isolation-tier-per-dispatcher-deployment.md) | accepted | 2026-08-25 |
| 0043 | [Sensitive values are marked by field type and filtered on the way out](0043-sensitive-values-are-marked-by-field-type-and-filtered-on-the-way-out.md) | accepted | 2026-08-25 |
| 0044 | [Parameter names match case-insensitively and colliding variants are rejected at save](0044-parameter-names-match-case-insensitively-and-colliding-variants-are-rejected.md) | accepted | 2026-08-26 |
| 0045 | [A pass-by-reference artefact store for large payloads](0045-pass-by-reference-artefact-store-for-large-payloads.md) | proposed | 2026-08-27 |
| 0046 | [Task execution runs behind a `TaskExecutor` interface with Tekton and plain Kubernetes Jobs](0046-task-execution-runs-behind-a-taskexecutor-interface.md) | accepted | 2026-08-21 |
| 0050 | [`status` is the external run field; `phase` stays exposed until the dispatcher gets its own wire model](0050-status-is-the-external-field-phase-stays-exposed-for-now.md) | accepted | 2026-08-18 |
| 0051 | [Execution-state fields never appear in the public run models](0051-execution-state-fields-never-appear-in-public-models.md) | accepted | 2026-08-18 |
| 0052 | [One error response shape for every API error](0052-one-error-response-shape-for-every-api-error.md) | accepted | 2026-08-18 |
| 0053 | [A vocabulary rename stops at the wire unless it ships with a migration](0053-a-vocabulary-rename-stops-at-the-wire-without-a-migration.md) | accepted | 2026-08-18 |
| 0060 | [No partitioning and no leader election: every instance does every job](0060-no-partitioning-no-leader-every-instance-does-every-job.md) | accepted | 2026-07-23 |
| 0061 | [Concurrency caps and per-class kill switches wait for load-test evidence](0061-concurrency-caps-and-kill-switches-wait-for-load-test-evidence.md) | accepted | 2026-08-18 |
| 0062 | [Custom HTTP client configuration is a product requirement every upgrade must preserve](0062-custom-http-client-configuration-is-a-product-requirement.md) | accepted | 2026-07-23 |
| 0063 | [A run timeout must be at least the transport timeout of the work it guards](0063-run-timeout-must-cover-transport-timeout.md) | accepted | 2026-07-23 |
| 0064 | [Claimed tasks carry a lease renewed by one batched heartbeat per dispatcher](0064-worker-leases-by-batched-heartbeat.md) | accepted | 2026-09-01 |
| 0065 | [A task ends with a typed `statusReason` beside its `statusMessage`](0065-typed-status-reason-on-task-end.md) | accepted | 2026-09-01 |
| 0066 | [A workflow task parameter without a value is rejected at save](0066-a-workflow-task-parameter-without-a-value-is-rejected-at-save.md) | superseded by 0068 | 2026-09-02 |
| 0067 | [A run that declares workspaces is started by the dispatcher after provisioning](0067-runs-with-workspaces-are-started-by-the-dispatcher-after-provisioning.md) | accepted | 2026-09-02 |
| 0068 | [An empty workflow task parameter value is valid; required-ness is the task's run-time concern](0068-an-empty-workflow-task-parameter-value-is-valid.md) | accepted | 2026-09-02 |
| 0069 | [Submit starts the run by default; parking is the explicit opt-out](0069-submit-starts-the-run-by-default.md) | accepted | 2026-09-02 |
| 0070 | [Audit is flat per-event documents captured by annotation, with levels](0070-audit-is-flat-per-event-documents-captured-by-annotation.md) | accepted | 2026-09-03 |
| 0071 | [Monthly run quotas count audit events so deletion cannot reset them](0071-monthly-run-quotas-count-audit-events-so-deletion-cannot-reset-them.md) | accepted | 2026-09-03 |

# Decisions

One file per decision, numbered. Until the first 5.0.0 release ships, a record is rewritten in place when the
code moves and a record a later one absorbs is deleted, so every record reads as how the system is; from that
release on, a record is never edited except to change **Status** (a reversed decision is kept and marked
`superseded by NNNN`). Use `TEMPLATE.md`. Keep each under 40 lines and refer to things by name, not by code.

| # | Decision | Status | Date |
| --- | --- | --- | --- |
| 0001 | [Merge the flow and engine services into one deployable](0001-merge-flow-and-engine-into-one-deployable.md) | accepted | 2026-07-22 |
| 0002 | [Module boundaries are a convention, not Spring Modulith or ArchUnit](0002-module-boundaries-by-convention.md) | accepted | 2026-08-14 |
| 0003 | [Rename Team to Workspace at the v5 major](0003-rename-team-to-workspace.md) | accepted | 2026-07-22 |
| 0004 | [Rename the worker tier from agent to dispatcher](0004-rename-worker-tier-agent-to-dispatcher.md) | accepted | 2026-07-23 |
| 0005 | [Fold the web client into the monorepo](0005-fold-the-web-client-into-the-monorepo.md) | accepted | 2026-07-22 |
| 0006 | [One product version and tag builds every image](0006-one-product-version-and-tag-for-all-images.md) | accepted | 2026-07-22 |
| 0007 | [The merged module is named service-core; "engine" stays a package](0007-merged-module-is-named-service-core.md) | accepted | 2026-07-23 |
| 0010 | [Work is claimed atomically with findAndModify, not with distributed locks](0010-atomic-claims-with-findandmodify.md) | accepted | 2026-07-23 |
| 0011 | [Contended transitions use compare-and-set, not @Version retries](0011-contended-transitions-use-compare-and-set.md) | accepted | 2026-07-22 |
| 0012 | [Outbound events go through a transactional outbox, not a broker](0012-transactional-outbox-no-broker.md) | accepted | 2026-07-23 |
| 0013 | [Pause is a flag enforced at one admission gate, never a run status](0013-pause-is-a-flag-at-one-admission-gate.md) | accepted | 2026-08-13 |
| 0014 | [Timeouts and crash recovery use a durable deadline and a periodic sweep, not per-run timers](0014-timeouts-by-periodic-sweep.md) | accepted | 2026-07-23 |
| 0016 | [Supersede generations and a separate reconciler are not built; retry creates a new run](0016-no-supersede-generations-or-reconciler.md) | accepted | 2026-08-21 |
| 0019 | [Workflow delete is a tombstone: the watcher cancels its runs, then prunes it](0019-workflow-delete-is-a-tombstone.md) | accepted | 2026-07-23 |
| 0020 | [Control and execution state are typed fields; annotations and labels are metadata only](0020-control-state-is-typed-fields.md) | accepted | 2026-07-24 |
| 0021 | [Versioned documents use the subset pattern (parent plus revision documents)](0021-versioned-documents-use-the-subset-pattern.md) | accepted | 2026-08-14 |
| 0023 | [Indexes are created by migration change units, not entity annotations](0023-indexes-are-created-by-migration-change-units.md) | accepted | 2026-08-14 |
| 0030 | [Relationship checks use direct queries anchored on the caller, not an in-memory graph](0030-relationship-checks-use-direct-queries-anchored-on-the-caller.md) | accepted | 2026-07-23 |
| 0032 | [With security off, requests run as a synthetic global admin that is never stored](0032-security-off-runs-as-a-synthetic-global-admin-that-is-never-stored.md) | accepted | 2026-08-26 |
| 0034 | [A global-scope token ignores the workspace path segment by design](0034-a-global-scope-token-ignores-the-workspace-path-segment.md) | accepted | 2026-08-24 |
| 0035 | [Sign-in is one token exchange for every identity provider, with a session cookie thereafter](0035-unified-token-exchange-with-a-session-cookie-thereafter.md) | accepted | 2026-08-18 |
| 0040 | [Parameters reach a task by engine-side substitution and environment variables](0040-parameters-reach-tasks-by-engine-substitution-and-environment-variables.md) | accepted | 2026-08-22 |
| 0041 | [Payload caps on parameters and results are enforced by the engine](0041-payload-caps-on-parameters-and-results-are-enforced-by-the-engine.md) | accepted | 2026-08-25 |
| 0042 | [One isolation tier per dispatcher deployment, no per-task tier](0042-one-isolation-tier-per-dispatcher-deployment.md) | accepted | 2026-08-25 |
| 0043 | [Sensitive values are marked by field type and filtered on the way out](0043-sensitive-values-are-marked-by-field-type-and-filtered-on-the-way-out.md) | accepted | 2026-08-25 |
| 0044 | [Parameter names match case-insensitively and colliding variants are rejected at save](0044-parameter-names-match-case-insensitively-and-colliding-variants-are-rejected.md) | accepted | 2026-08-26 |
| 0046 | [Task execution runs behind a `TaskExecutor` interface with Tekton and plain Kubernetes Jobs](0046-task-execution-runs-behind-a-taskexecutor-interface.md) | accepted | 2026-08-21 |
| 0050 | [`status` is the external run field; `phase` stays exposed until the dispatcher gets its own wire model](0050-status-is-the-external-field-phase-stays-exposed-for-now.md) | accepted | 2026-08-18 |
| 0051 | [Execution-state fields never appear in the public run models](0051-execution-state-fields-never-appear-in-public-models.md) | accepted | 2026-08-18 |
| 0052 | [One error response shape for every API error](0052-one-error-response-shape-for-every-api-error.md) | accepted | 2026-08-18 |
| 0053 | [A vocabulary rename stops at the wire unless it ships with a migration](0053-a-vocabulary-rename-stops-at-the-wire-without-a-migration.md) | accepted | 2026-08-18 |
| 0060 | [No partitioning and no leader election: every instance does every job](0060-no-partitioning-no-leader-every-instance-does-every-job.md) | accepted | 2026-07-23 |
| 0061 | [Concurrency caps, per-class kill switches and retry classes wait for load-test evidence](0061-concurrency-caps-and-kill-switches-wait-for-load-test-evidence.md) | accepted | 2026-08-18 |
| 0062 | [Custom HTTP client configuration is a product requirement every upgrade must preserve](0062-custom-http-client-configuration-is-a-product-requirement.md) | accepted | 2026-07-23 |
| 0064 | [Claimed tasks carry a lease renewed by one batched heartbeat per dispatcher](0064-worker-leases-by-batched-heartbeat.md) | accepted | 2026-09-01 |
| 0065 | [A task ends with a typed `statusReason` beside its `statusMessage`](0065-typed-status-reason-on-task-end.md) | accepted | 2026-09-01 |
| 0067 | [A run that declares a workspace is started by the dispatcher after provisioning; the provisioning claim takes only such runs](0067-runs-with-workspaces-are-started-by-the-dispatcher-after-provisioning.md) | accepted | 2026-09-02 |
| 0068 | [An empty workflow task parameter value is valid; required-ness is the task's run-time concern](0068-an-empty-workflow-task-parameter-value-is-valid.md) | accepted | 2026-09-02 |
| 0069 | [Submit starts the run by default; parking is the explicit opt-out](0069-submit-starts-the-run-by-default.md) | accepted | 2026-09-02 |
| 0070 | [Audit is flat per-event documents captured by annotation, with levels](0070-audit-is-flat-per-event-documents-captured-by-annotation.md) | accepted | 2026-09-03 |
| 0072 | [Monthly run quotas count audit events so deletion cannot reset them](0072-monthly-run-quotas-count-audit-events-so-deletion-cannot-reset-them.md) | accepted | 2026-09-03 |
| 0073 | [A run ends at `completed`; releasing its storage is the dispatcher's own reconciliation](0073-runs-end-at-completed-storage-release-is-dispatcher-reconciliation.md) | accepted | 2026-09-15 |
| 0074 | [Spring Security's native model replaces hand-rolled authentication and authorization mechanics](0074-spring-security-native-authentication-and-authorization.md) | accepted | 2026-09-02 |
| 0075 | [A child workflow run waits, cascades and nests to a capped depth, all on the existing lineage pair](0075-child-workflow-runs-wait-cascade-and-nesting-depth.md) | accepted | 2026-09-15 |
| 0077 | [Workflow Templates are seeded, read-only content](0077-workflow-templates-are-seeded-read-only-content.md) | accepted | 2026-09-15 |
| 0078 | [An `ai` task is a first-class type executed as a pod from a Boomerang-published worker image](0078-an-ai-task-type-runs-a-boomerang-published-worker-as-a-pod.md) | accepted | 2026-09-16 |
| 0079 | [A status CloudEvent carries a run's identity and lifecycle, not the run model](0079-status-events-carry-identity-and-lifecycle-not-the-run-model.md) | accepted | 2026-09-16 |
| 0080 | [An unknown reference is 404; a malformed request stays 400](0080-an-unknown-reference-is-404-a-malformed-one-is-400.md) | accepted | 2026-09-16 |
| 0081 | [Leftover Tekton runtime objects are cleaned by our own reconciler](0081-leftover-tekton-runtime-objects-are-cleaned-by-our-own-reconciler.md) | accepted | 2026-09-23 |
| 0082 | [A task runs once per item through a `foreach` setting on the task](0082-foreach-fan-out-of-one-task.md) | accepted | 2026-09-26 |
| 0084 | [The webapp renders from feature flags, not from the deployment shape](0084-the-webapp-renders-from-feature-flags.md) | accepted | 2026-09-28 |
| 0085 | [Artifacts are uploaded by built-in tasks and expire into a kept record](0085-artifacts-are-uploaded-by-tasks-and-expire-into-a-kept-record.md) | accepted | 2026-09-28 |
| 0087 | [One page header on every route, and a page inside an object is titled with its name](0087-one-page-header-and-object-names-as-titles.md) | accepted | 2026-09-29 |
| 0088 | [Home's cross-workspace rollup fans out from the route loader instead of a new summary endpoint](0088-home-rollup-fans-out-per-workspace.md) | accepted | 2026-09-29 |
| 0089 | [Insights read the runs a workspace still holds; the audit trail serves quotas only](0089-insights-read-retained-runs-audit-serves-quotas.md) | accepted | 2026-09-30 |
| 0090 | [Administration is one "Manage" link to a tabbed page](0090-manage-is-one-tabbed-area.md) | accepted | 2026-09-30 |
| 0091 | [Settings groups are named for what they control, and storage defaults stay with the dispatcher](0091-settings-groups-named-for-what-they-control.md) | accepted | 2026-09-30 |
| 0092 | [Workspace parameters are referenced as workspace.params; team.params is deprecated](0092-workspace-parameter-references.md) | accepted | 2026-09-30 |
| 0093 | [Scoped parameters resolve from their stores, and untrusted text is never expanded](0093-when-scoped-parameters-resolve.md) | accepted | 2026-10-02 |
| 0094 | [The dispatcher reconnects at once after a poll](0094-dispatcher-reconnects-at-once-after-a-poll.md) | accepted | 2026-10-02 |
| 0095 | [A dispatcher claims only what it has slots for](0095-a-dispatcher-claims-only-what-it-has-slots-for.md) | accepted | 2026-10-05 |
| 0096 | [Starting is bounded apart from running, and a task that never started is handed back](0096-a-task-that-never-started-is-handed-back.md) | accepted | 2026-10-05 |
| 0097 | [A dispatcher's poll filters by task type, task and workflow label, resolved to typed refs](0097-dispatcher-polls-filter-by-task-and-workflow-label.md) | accepted | 2026-10-06 |
| 0098 | [Migrations run inside service-core as it starts, before it serves](0098-migrations-run-inside-service-core-before-it-serves.md) | accepted | 2026-10-01 |
| 0099 | [The change-unit chain migrates an empty or a v3 database, and nothing else](0099-the-chain-migrates-an-empty-or-a-v3-database-only.md) | accepted | 2026-10-01 |
| 0100 | [One key encrypts every secret at rest; the v3 pair is read once to re-encrypt](0100-one-encryption-key-for-secrets-at-rest-and-the-v3-pair-read-once.md) | accepted | 2026-10-10 |
| 0101 | [A REST handler is denied unless it carries `@AuthCriteria` or `@AuthExempt`](0101-rest-handlers-are-denied-unless-annotated.md) | accepted | 2026-09-14 |
| 0102 | [The dispatcher protocol is a published contract, and the SDK owns its own wire models](0102-the-dispatcher-protocol-is-a-published-contract.md) | accepted | 2026-10-08 |
| 0103 | [Routes are grouped under one prefix per kind of caller](0103-routes-are-grouped-under-one-prefix-per-kind-of-caller.md) | accepted | 2026-10-13 |
| 0104 | [One workspace per run; state across runs is a workflow-scoped artifact](0104-one-workspace-per-run-and-workflow-scoped-artifacts-for-state-across-runs.md) | accepted | 2026-10-13 |
| 0105 | [A dispatcher is the contract plus an SDK, and a separate process only where a boundary exists](0105-a-dispatcher-is-a-process-only-where-a-boundary-exists.md) | accepted | 2026-10-10 |
| 0106 | [A v3 install upgrades with its chart Secret as it is, or without its pair](0106-a-v3-install-upgrades-with-its-chart-secret-or-without-its-pair.md) | accepted | 2026-10-14 |
| 0107 | [Seeds carry no ids and match on natural keys](0107-seeds-carry-no-ids-and-match-on-natural-keys.md) | accepted | 2026-10-15 |
| 0108 | [The Java images run on Ubuntu's chiseled JRE](0108-the-java-images-run-on-ubuntus-chiseled-jre.md) | accepted | 2026-10-15 |
| 0109 | [Deployment shape is properties, not a mode](0109-deployment-shape-is-properties-not-a-mode.md) | accepted | 2026-10-15 |
| 0110 | [A machine token can be bootstrapped from configuration](0110-a-machine-token-can-be-bootstrapped-from-configuration.md) | accepted | 2026-10-15 |
| 0111 | [A workspace quota of zero is unlimited; an absent quota inherits the platform default](0111-a-workspace-quota-of-zero-is-unlimited.md) | accepted | 2026-10-15 |
| 0112 | [A task log is served from its stored copy first, then from the route its dispatcher registered](0112-a-task-log-is-served-stored-first-then-from-the-dispatchers-registered-route.md) | accepted | 2026-10-15 |
| 0113 | [A run's timeout bounds execution, not waiting; a wait is indefinite unless the task says otherwise](0113-a-runs-timeout-bounds-execution-not-waiting.md) | accepted | 2026-10-16 |
| 0114 | [A retry re-runs from a scope, as a new run that copies the task runs it keeps](0114-a-retry-re-runs-from-a-scope-as-a-new-run-with-copied-task-runs.md) | accepted | 2026-10-16 |
| 0115 | [A secret is a parameter type](0115-a-secret-is-a-parameter-type.md) | accepted | 2026-10-16 |
| 0116 | [Secret values are encrypted at rest; spec references are filled in at claim](0116-secret-values-are-encrypted-at-rest-and-spec-references-are-filled-in-at-claim.md) | accepted | 2026-10-16 |

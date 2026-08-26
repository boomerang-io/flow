package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * The sweep and dispatcher-poll indexes {@code _0017__RunIndexes} left uncovered. Its {@code
 * claim_page} compounds lead with {@code status} ({@code workflow_runs: {status, phase,
 * creationDate}}) or {@code type} ({@code task_runs: {type, status, phase, creationDate}}), so a
 * query that filters on {@code phase} WITHOUT the leading key cannot seek them at all — it
 * collection-scans. Every {@code WorkflowWatcher} sweep below is in that position, as is the
 * dispatcher's teardown claim page, which runs once per second per connected dispatcher.
 *
 * <p>All non-unique, and all created via {@link MigrationUtils#ensureIndexKeys} for the same reason
 * as {@code _0036}: a v4 install ran with {@code auto-index-creation=true} and may already carry a
 * Spring-named index over the same keys.
 *
 * <ul>
 *   <li>{@code workflow_runs.phase_creation_sweep {phase:1, creationDate:1}} — three call sites,
 *       all filtering {@code phase} with no {@code status} predicate and all sorting {@code
 *       creationDate} ascending, which this index also satisfies without a blocking sort:
 *       <ul>
 *         <li>{@code WorkflowRunService.findClaimableForTeardown} ({@code WorkflowRunService.java:114-129})
 *             — {@code phase=completed}, unclaimed, has workspaces. Called every {@code
 *             MAX_SLEEP_INTERVAL} of the dispatcher long poll ({@code DispatcherService.java:139}),
 *             for every connected dispatcher; the highest-frequency unindexed query in the engine.
 *         <li>{@code WorkflowRunService.findFinalizableWithoutWorkspaces} — {@code phase=completed}
 *             (the {@code finalizeWorkspacelessRuns} sweep).
 *         <li>{@code WorkflowRunService.findInFlight} — {@code phase in (pending, queued, running)}
 *             (the {@code reapRunsWithMissingRevision} sweep).
 *       </ul>
 *   <li>{@code workflow_runs.phase_start_sweep {phase:1, startTime:1}} — {@code
 *       WorkflowRunService.findRunningStartedBefore} ({@code phase=running}, {@code startTime <=
 *       cutoff}, sorted by {@code startTime}) behind {@code WorkflowWatcher.recoverStalledRuns}.
 *       Both keys are predicates here, so the range and the sort are both served.
 *   <li>{@code workflow_runs.workflow_ref_phase {workflowRef:1, phase:1}} — {@code workflow_runs}
 *       has no {@code workflowRef} index of any kind today. Serves {@code
 *       findByWorkflowRefAndPhaseIn} ({@code WorkflowWatcher.cancelDeletedWorkflowRuns}, once per
 *       tombstoned Workflow per sweep) and {@code existsByWorkflowRefAndPhaseIn} on both keys, and
 *       {@code deleteByWorkflowRef} plus {@code WorkflowRunService.query}'s {@code workflowRef $in}
 *       by prefix. NOTE: the brief specified {@code {workflowRef:1}}; {@code phase} is appended
 *       because the two hottest callers both filter it, and a prefix match leaves the plain-{@code
 *       workflowRef} cases exactly as well served.
 *   <li>{@code task_runs.claimed_sweep {phase:1, "claim.at":1}} — {@code
 *       TaskRunService.findClaimed} ({@code phase in (queued, running)}, {@code claim.by} exists,
 *       sorted by {@code claim.at}) behind {@code WorkflowWatcher.reapClaimsFromGoneDispatchers}.
 *       {@code claim_page} leads with {@code type}, which this query does not filter.
 *   <li>{@code actions.status_sweep {status:1, creationDate:1}} — {@code
 *       findByStatusOrderByCreationDateAsc} behind {@code WorkflowWatcher.closeStrayActions}; also
 *       serves {@code countByStatus} / {@code countByStatusAndCreationDateBetween}. The only
 *       existing {@code actions} index is the unique {@code taskRunRef} one from {@code
 *       _0019__DomainIndexes}.
 * </ul>
 *
 * <p>Deliberately NOT created: {@code workflow_runs {timeoutAt}} and {@code task_runs {timeoutAt}}
 * / {@code {waitUntil}} for the deadline sweeps — {@code _0017__RunIndexes} already builds those as
 * sparse {@code timeout_sweep}/{@code wait_sweep}, and each of those queries leads with the
 * indexed deadline field.
 */
@Change(id = "0037-sweep-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0037__SweepIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    ensureIndexKeys(
        db,
        workflowRuns,
        "phase_creation_sweep",
        new Document("phase", 1).append("creationDate", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        workflowRuns,
        "phase_start_sweep",
        new Document("phase", 1).append("startTime", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        workflowRuns,
        "workflow_ref_phase",
        new Document("workflowRef", 1).append("phase", 1),
        new IndexOptions());

    ensureIndexKeys(
        db,
        names.resolve("task_runs"),
        "claimed_sweep",
        new Document("phase", 1).append("claim.at", 1),
        new IndexOptions());

    ensureIndexKeys(
        db,
        names.resolve("actions"),
        "status_sweep",
        new Document("status", 1).append("creationDate", 1),
        new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    dropIndex(db, workflowRuns, "phase_creation_sweep");
    dropIndex(db, workflowRuns, "phase_start_sweep");
    dropIndex(db, workflowRuns, "workflow_ref_phase");
    dropIndex(db, names.resolve("task_runs"), "claimed_sweep");
    dropIndex(db, names.resolve("actions"), "status_sweep");
  }
}

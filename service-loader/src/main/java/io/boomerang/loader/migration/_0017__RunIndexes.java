package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;
import static io.boomerang.loader.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All run-lifecycle indexes: task_runs (claim/sweep + uniqueness), workflow_runs (claim/sweep),
 * and the workflows status lookup the tombstone wind-down sweep pages by. Formerly four separate
 * units ({@code _0002__TaskRunClaimAndSweepIndexes}, {@code _0003__TaskRunUniqueness}, {@code
 * _0004__WorkflowRunClaimIndexes}, {@code _0010__WorkflowStatusIndex}) that ran BEFORE the v3→v5
 * migration populated these collections, forcing index maintenance during the bulk insert of
 * 18,093 runs and implicitly creating {@code task_runs}/{@code workflow_runs} empty on a v3
 * install years before any data existed. Consolidated here in Phase 4 — AFTER the migration
 * (Phase 2) and the v4 rename fixups (Phase 3) — so every index (including the uniqueness dedupe
 * passes) now runs against real, already-migrated data on every install, v3-sourced or not.
 *
 * <p>Each former unit's logic is preserved verbatim as its own private method below; only the
 * relative execution order changed.
 */
@Change(id = "0017-run-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0017__RunIndexes {

  private static final Logger LOG = LoggerFactory.getLogger(_0017__RunIndexes.class);

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("succeeded", "failed", "invalid", "skipped", "cancelled", "timedout");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    taskRunClaimAndSweepIndexes(db, names);
    taskRunUniqueness(db, names);
    workflowRunClaimIndexes(db, names);
    workflowStatusIndex(db, names);
  }

  // =====================================================================================
  // task_runs — claim page + sweep indexes (formerly _0002__TaskRunClaimAndSweepIndexes)
  // =====================================================================================

  /**
   * Non-unique task_runs indexes for atomic claiming and the reconciliation sweeps: the FIFO
   * claim page, per-run task lookup, and the sparse lease/timeout/wait sweep indexes. Sparse —
   * only claimed, guarded, or waiting runs carry the swept fields.
   */
  private void taskRunClaimAndSweepIndexes(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    ensureIndex(
        db,
        taskRuns,
        "claim_page",
        new Document("type", 1).append("status", 1).append("phase", 1).append("creationDate", 1),
        new IndexOptions());
    ensureIndex(
        db,
        taskRuns,
        "run_tasks",
        new Document("workflowRunRef", 1).append("status", 1).append("name", 1),
        new IndexOptions());
    ensureIndex(
        db,
        taskRuns,
        "lease_sweep",
        new Document("claim.leaseExpiresAt", 1),
        new IndexOptions().sparse(true));
    ensureIndex(
        db, taskRuns, "timeout_sweep", new Document("timeoutAt", 1), new IndexOptions().sparse(true));
    ensureIndex(
        db, taskRuns, "wait_sweep", new Document("waitUntil", 1), new IndexOptions().sparse(true));
  }

  // =====================================================================================
  // task_runs — uniqueness dedupe + index (formerly _0003__TaskRunUniqueness)
  // =====================================================================================

  /**
   * Guarantee one TaskRun per DAG node: delete historical {@code (workflowRunRef, name)}
   * duplicates left by the old find-then-update claim race, then create the unique {@code
   * node_uniqueness} index that stops the race from ever recreating them. Per duplicate group the
   * kept document is the one that finished (terminal status), else the earliest created; the rest
   * are deleted (they are bug artifacts with no history worth keeping).
   */
  private void taskRunUniqueness(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db,
            taskRuns,
            new Document("workflowRunRef", "$workflowRunRef").append("name", "$name"));
    long deleted = 0;
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      deleted += deleteExtras(db.getCollection(taskRuns), key);
    }
    LOG.info(
        "task_runs dedupe — {} duplicate (workflowRunRef, name) groups, {} documents deleted",
        duplicateGroups.size(),
        deleted);
    ensureIndex(
        db,
        taskRuns,
        "node_uniqueness",
        new Document("workflowRunRef", 1).append("name", 1),
        new IndexOptions().unique(true));
  }

  /** Keep the best document of one duplicate group; delete the rest. */
  private long deleteExtras(MongoCollection<Document> collection, Document key) {
    List<Document> group = new ArrayList<>();
    collection
        .find(
            Filters.and(
                Filters.eq("workflowRunRef", key.get("workflowRunRef")),
                Filters.eq("name", key.get("name"))))
        .sort(Sorts.ascending("creationDate", "_id"))
        .into(group);
    if (group.size() <= 1) {
      return 0;
    }
    Document keeper =
        group.stream()
            .filter(doc -> TERMINAL_STATUSES.contains(doc.getString("status")))
            .findFirst()
            .orElse(group.get(0));
    long deleted = 0;
    for (Document doc : group) {
      if (doc.get("_id").equals(keeper.get("_id"))) {
        continue;
      }
      collection.deleteOne(Filters.eq("_id", doc.get("_id")));
      deleted++;
    }
    return deleted;
  }

  // =====================================================================================
  // workflow_runs — claim page + sweep indexes (formerly _0004__WorkflowRunClaimIndexes)
  // =====================================================================================

  /**
   * workflow_runs indexes: the claim page and the sparse timeout/pause sweeps. Run lineage is
   * carried by {@code initiatedByRef}/{@code trigger}; there is no creation-dedup index.
   */
  private void workflowRunClaimIndexes(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    ensureIndex(
        db,
        workflowRuns,
        "claim_page",
        new Document("status", 1).append("phase", 1).append("creationDate", 1),
        new IndexOptions());
    ensureIndex(
        db,
        workflowRuns,
        "timeout_sweep",
        new Document("timeoutAt", 1),
        new IndexOptions().sparse(true));
    ensureIndex(
        db,
        workflowRuns,
        "paused_lookup",
        new Document("pauseRequestedAt", 1),
        new IndexOptions().sparse(true));
  }

  // =====================================================================================
  // workflows — status index (formerly _0010__WorkflowStatusIndex)
  // =====================================================================================

  /**
   * workflows status index: the tombstone wind-down sweep pages deleted Workflows by status, so
   * the scan stays indexed rather than walking the whole collection each cycle.
   */
  private void workflowStatusIndex(MongoDatabase db, CollectionNames names) {
    ensureIndex(
        db, names.resolve("workflows"), "status_lookup", new Document("status", 1), new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    dropIndex(db, taskRuns, "claim_page");
    dropIndex(db, taskRuns, "run_tasks");
    dropIndex(db, taskRuns, "lease_sweep");
    dropIndex(db, taskRuns, "timeout_sweep");
    dropIndex(db, taskRuns, "wait_sweep");
    dropIndex(db, taskRuns, "node_uniqueness");

    String workflowRuns = names.resolve("workflow_runs");
    dropIndex(db, workflowRuns, "claim_page");
    dropIndex(db, workflowRuns, "timeout_sweep");
    dropIndex(db, workflowRuns, "paused_lookup");

    dropIndex(db, names.resolve("workflows"), "status_lookup");
  }
}

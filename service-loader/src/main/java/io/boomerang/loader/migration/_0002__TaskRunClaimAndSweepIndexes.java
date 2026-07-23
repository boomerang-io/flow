package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * Non-unique task_runs indexes for atomic claiming and the reconciliation sweeps: the FIFO
 * claim page, per-run task lookup, and the sparse lease/timeout/wait sweep indexes. Sparse —
 * only claimed, guarded, or waiting runs carry the swept fields.
 */
@Change(id = "0002-task-run-claim-and-sweep-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0002__TaskRunClaimAndSweepIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
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

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    dropIndex(db, taskRuns, "claim_page");
    dropIndex(db, taskRuns, "run_tasks");
    dropIndex(db, taskRuns, "lease_sweep");
    dropIndex(db, taskRuns, "timeout_sweep");
    dropIndex(db, taskRuns, "wait_sweep");
  }
}

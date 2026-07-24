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
 * workflow_runs indexes: the claim page and the sparse timeout/pause sweeps. Run lineage is
 * carried by {@code initiatedByRef}/{@code trigger}; there is no creation-dedup index.
 */
@Change(id = "0004-workflow-run-claim-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0004__WorkflowRunClaimIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
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

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    dropIndex(db, workflowRuns, "claim_page");
    dropIndex(db, workflowRuns, "timeout_sweep");
    dropIndex(db, workflowRuns, "paused_lookup");
  }
}

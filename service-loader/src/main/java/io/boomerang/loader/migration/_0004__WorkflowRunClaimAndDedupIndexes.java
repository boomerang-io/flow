package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * workflow_runs indexes: the claim page and the sparse timeout/pause sweeps, plus the three
 * partial unique dedup keys — idempotencyKey (request dedup), createdByTaskRunRef (one child
 * run per creating task), and (retryOfRef, retryAttempt) (one run per retry attempt). Partial
 * filters use {@code $exists: true} on the keyed field, so runs without the field are exempt.
 */
@Change(id = "0004-workflow-run-claim-and-dedup-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0004__WorkflowRunClaimAndDedupIndexes {

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
    ensureIndex(
        db,
        workflowRuns,
        "idempotency_key",
        new Document("idempotencyKey", 1),
        new IndexOptions()
            .unique(true)
            .partialFilterExpression(Filters.exists("idempotencyKey", true)));
    ensureIndex(
        db,
        workflowRuns,
        "created_by_task_run",
        new Document("createdByTaskRunRef", 1),
        new IndexOptions()
            .unique(true)
            .partialFilterExpression(Filters.exists("createdByTaskRunRef", true)));
    ensureIndex(
        db,
        workflowRuns,
        "retry_attempt",
        new Document("retryOfRef", 1).append("retryAttempt", 1),
        new IndexOptions()
            .unique(true)
            .partialFilterExpression(Filters.exists("retryOfRef", true)));
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    dropIndex(db, workflowRuns, "claim_page");
    dropIndex(db, workflowRuns, "timeout_sweep");
    dropIndex(db, workflowRuns, "paused_lookup");
    dropIndex(db, workflowRuns, "idempotency_key");
    dropIndex(db, workflowRuns, "created_by_task_run");
    dropIndex(db, workflowRuns, "retry_attempt");
  }
}

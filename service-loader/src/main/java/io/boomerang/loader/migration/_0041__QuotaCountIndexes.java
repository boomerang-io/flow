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
 * The {@code workflow_runs} indexes behind the quota counters. Every quota evaluation ({@code
 * WorkspaceService.setCurrentQuotas}) issues two server-side counts via {@code
 * WorkflowRunService.countForQuota}, both anchored on {@code workflowRef $in} - a predicate no
 * existing index leads with ({@code _0017__RunIndexes}' {@code claim_page} leads with {@code
 * status}; {@code _0037__SweepIndexes}' {@code workflow_ref_phase} covers {@code workflowRef} but
 * pairs it with {@code phase}, which neither count filters beyond the prefix).
 *
 * <p>Both non-unique, and both created via {@link MigrationUtils#ensureIndexKeys} for the same
 * reason as {@code _0036}: a v4 install ran with {@code auto-index-creation=true} and may already
 * carry a Spring-named index over the same keys.
 *
 * <ul>
 *   <li>{@code workflow_runs.workflow_ref_creation {workflowRef:1, creationDate:1}} - the monthly
 *       quota count ({@code workflowRef $in} + the calendar-month {@code creationDate} range). Also
 *       serves the insights date-window queries ({@code WorkflowRunService.insights} and the
 *       date-bounded {@code count}), which filter {@code workflowRef $in} + {@code creationDate}.
 *   <li>{@code workflow_runs.workflow_ref_status {workflowRef:1, status:1}} - the concurrent quota
 *       count ({@code workflowRef $in} + {@code status $in (notstarted, ready, running, waiting)}).
 * </ul>
 */
@Change(id = "0041-quota-count-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0041__QuotaCountIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    ensureIndexKeys(
        db,
        workflowRuns,
        "workflow_ref_creation",
        new Document("workflowRef", 1).append("creationDate", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        workflowRuns,
        "workflow_ref_status",
        new Document("workflowRef", 1).append("status", 1),
        new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String workflowRuns = names.resolve("workflow_runs");
    dropIndex(db, workflowRuns, "workflow_ref_creation");
    dropIndex(db, workflowRuns, "workflow_ref_status");
  }
}

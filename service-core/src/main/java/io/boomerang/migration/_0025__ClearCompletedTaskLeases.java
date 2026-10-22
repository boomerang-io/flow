package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Removes {@code claim.leaseExpiresAt} from completed task runs. Completion now clears the lease, so
 * the sparse {@code lease_sweep} index holds only live claims; task runs completed before that kept
 * theirs, and the lease sweep would read every one of them. Nothing reads a completed task's lease.
 * An empty or a v3 database has none, and a second run finds none.
 */
@Change(id = "0025-clear-completed-task-leases", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0025__ClearCompletedTaskLeases {

  private static final Logger LOG = LoggerFactory.getLogger(_0025__ClearCompletedTaskLeases.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    long cleared =
        db.getCollection(CollectionNames.from(env).resolve("task_runs"))
            .updateMany(
                Filters.and(
                    Filters.eq("phase", "completed"), Filters.exists("claim.leaseExpiresAt")),
                Updates.unset("claim.leaseExpiresAt"))
            .getModifiedCount();
    LOG.info("Cleared the lease from {} completed task runs", cleared);
  }

  @Rollback
  public void rollback() {
    // A completed task run's lease is never read again; there is nothing to restore.
  }
}

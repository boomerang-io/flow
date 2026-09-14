package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites the retired {@code finalized} run phase to {@code completed} in {@code workflow_runs}
 * and {@code task_runs}.
 *
 * <p>{@code completed} is now the terminal phase and {@code RunPhase} no longer has a {@code
 * finalized} member, so a document left carrying the old string deserialises to a null phase — the
 * run would read as neither in flight nor terminal. Two populations carry it: runs an earlier v5
 * install finalised, and every v3 run migrated by {@code _0011__V3MigrateRuns} before that unit was
 * corrected to write {@code completed} directly.
 *
 * <p>Ungated and idempotent: a fresh install matches nothing, and a second run matches nothing
 * because the first left no {@code finalized} document behind.
 */
@Change(id = "0043-run-phase-finalized-is-completed", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0043__RunPhaseFinalizedIsCompleted {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0043__RunPhaseFinalizedIsCompleted.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    for (String collection : new String[] {"workflow_runs", "task_runs"}) {
      String resolved = names.resolve(collection);
      long rewritten =
          db.getCollection(resolved)
              .updateMany(Filters.eq("phase", "finalized"), Updates.set("phase", "completed"))
              .getModifiedCount();
      LOG.info("Rewrote phase finalized -> completed on {} documents in {}", rewritten, resolved);
    }
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Forward-only: the runs that held finalized are indistinguishable from runs that always held
    // completed, and no v5 code can read a finalized phase any more.
  }
}

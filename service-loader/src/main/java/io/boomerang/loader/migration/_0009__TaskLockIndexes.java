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
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/**
 * task_locks index: a TTL on expiresAt to garbage-collect a crashed holder's lock. Correctness is
 * the acquire Compare-And-Set checking expiresAt itself, so the sweep timing is not relied upon.
 */
@Change(id = "0009-task-lock-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0009__TaskLockIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String taskLocks = names.resolve("task_locks");
    ensureIndex(
        db,
        taskLocks,
        "lease_ttl",
        new Document("expiresAt", 1),
        new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("task_locks"), "lease_ttl");
  }
}

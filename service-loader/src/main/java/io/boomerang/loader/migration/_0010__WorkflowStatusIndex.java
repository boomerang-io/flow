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
 * workflows status index: the tombstone wind-down sweep pages deleted Workflows by status, so the
 * scan stays indexed rather than walking the whole collection each cycle.
 */
@Change(id = "0010-workflow-status-index", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0010__WorkflowStatusIndex {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    ensureIndex(
        db, names.resolve("workflows"), "status_lookup", new Document("status", 1), new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("workflows"), "status_lookup");
  }
}

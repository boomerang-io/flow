package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retires the v3-era storage settings nothing reads any more. A volume's size, class and access
 * mode come from the workflow's own storage spec, capped by the workspace quota
 * ({@code max.workflow.storage}, {@code max.workflowrun.storage}) and defaulted by the dispatcher
 * that creates the claim ({@code kube.workspace.storage.*}), so the {@code workflow} settings
 * document goes entirely and the {@code workflowrun} document keeps only the two engine ceilings
 * ({@code max.nesting.depth}, {@code max.foreach.items}).
 *
 * <p>Idempotent: a missing document or an already-trimmed config is left alone. Forward-only:
 * the retired values were never read, so nothing is lost by not restoring them.
 */
@Change(id = "0056-retire-storage-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0056__RetireStorageSettings {

  private static final Logger LOG = LoggerFactory.getLogger(_0056__RetireStorageSettings.class);

  static final String WORKFLOW_KEY = "workflow";
  /** The seeded {@code workflow} document's id, the same since v3 (never renamed by {@code _0005}). */
  static final ObjectId WORKFLOW_ID = new ObjectId("60245b56226920beece547e3");
  static final String WORKFLOWRUN_KEY = "workflowrun";
  static final List<String> RETIRED_ENTRIES =
      List.of("storage.size", "storage.class", "storage.accessMode", "max.storage.size");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    long removed =
        settings
            .deleteMany(Filters.or(Filters.eq("_id", WORKFLOW_ID), Filters.eq("key", WORKFLOW_KEY)))
            .getDeletedCount();
    LOG.info("Storage settings: removed {} '{}' document(s).", removed, WORKFLOW_KEY);
    long trimmed =
        settings
            .updateOne(
                Filters.eq("key", WORKFLOWRUN_KEY),
                Updates.pull("config", new Document("key", new Document("$in", RETIRED_ENTRIES))))
            .getModifiedCount();
    LOG.info(
        "Storage settings: {} the retired entries from '{}'.",
        (trimmed > 0) ? "removed" : "nothing to remove for",
        WORKFLOWRUN_KEY);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Forward-only: the removed entries had no reader.
  }
}

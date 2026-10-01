package io.boomerang.migration;

import static io.boomerang.migration.MigrationUtils.dropIndex;
import static io.boomerang.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything an existing install needs for a task that runs once per item of a list:
 *
 * <ul>
 *   <li>a sparse {@code task_runs {parentRef, index}} index. A foreach task's items carry the
 *       parent TaskRun's id and their position; the engine reads a parent's items in item order on
 *       every item end, and only items carry {@code parentRef}, so the index stays small;
 *   <li>the {@code max.foreach.items} key in the {@code workflowrun} settings document, the cap the
 *       engine checks when it fans a task out and the workflow save checks on a literal array.
 * </ul>
 *
 * <p>Idempotent on both: the index through {@code ensureIndexKeys}, the settings key only when
 * absent from the existing config list (the {@code _0046__DeclareRunWorkflowWaitParam} pattern). A
 * fresh install picks the key up from {@code seed/settings.json} instead and finds nothing to do.
 */
@Change(id = "0049-foreach-items", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0049__ForeachItems {

  private static final Logger LOG = LoggerFactory.getLogger(_0049__ForeachItems.class);

  static final String INDEX_NAME = "parent_index";
  static final String SETTING_KEY = "max.foreach.items";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    ensureIndexKeys(
        db,
        names.resolve("task_runs"),
        INDEX_NAME,
        new Document("parentRef", 1).append("index", 1),
        new IndexOptions().sparse(true));
    addForeachItemsSetting(db, names);
  }

  /** Add {@code max.foreach.items} to the existing {@code workflowrun} settings document. */
  private void addForeachItemsSetting(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document workflowRun = settings.find(Filters.eq("key", "workflowrun")).first();
    if (workflowRun == null) {
      LOG.warn("No 'workflowrun' settings document found - nothing to backfill.");
      return;
    }
    List<Document> existingConfig = workflowRun.getList("config", Document.class);
    List<Document> config =
        existingConfig == null ? new ArrayList<>() : new ArrayList<>(existingConfig);
    if (config.stream().anyMatch(c -> SETTING_KEY.equals(c.getString("key")))) {
      LOG.info("{} already present - nothing to backfill.", SETTING_KEY);
      return;
    }
    config.add(foreachItemsConfig());
    settings.updateOne(Filters.eq("_id", workflowRun.get("_id")), Updates.set("config", config));
    LOG.info("Backfilled {} onto the existing 'workflowrun' document.", SETTING_KEY);
  }

  private static Document foreachItemsConfig() {
    return new Document(
            "description",
            "How many items a for-each task may run. A task whose items resolve to more fails"
                + " instead of running any.")
        .append("key", SETTING_KEY)
        .append("label", "Maximum For-Each Items")
        .append("type", "number")
        .append("value", "256")
        .append("readOnly", false);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // The settings key is additive onto a document an operator may have edited since -
    // forward-only, matching the other settings units in this chain.
    dropIndex(db, names.resolve("task_runs"), INDEX_NAME);
  }
}

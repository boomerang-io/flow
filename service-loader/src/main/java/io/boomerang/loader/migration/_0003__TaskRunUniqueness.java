package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;
import static io.boomerang.loader.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarantee one TaskRun per DAG node: delete historical {@code (workflowRunRef, name)} duplicates
 * left by the old find-then-update claim race, then create the unique {@code node_uniqueness}
 * index that stops the race from ever recreating them. Per duplicate group the kept document is
 * the one that finished (terminal status), else the earliest created; the rest are deleted (they
 * are bug artifacts with no history worth keeping).
 */
@Change(id = "0003-task-run-uniqueness", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0003__TaskRunUniqueness {

  private static final Logger LOG = LoggerFactory.getLogger(_0003__TaskRunUniqueness.class);

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("succeeded", "failed", "invalid", "skipped", "cancelled", "timedout");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db,
            taskRuns,
            new Document("workflowRunRef", "$workflowRunRef").append("name", "$name"));
    long deleted = 0;
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      deleted += deleteExtras(db.getCollection(taskRuns), key);
    }
    LOG.info(
        "task_runs dedupe — {} duplicate (workflowRunRef, name) groups, {} documents deleted",
        duplicateGroups.size(),
        deleted);
    ensureIndex(
        db,
        taskRuns,
        "node_uniqueness",
        new Document("workflowRunRef", 1).append("name", 1),
        new IndexOptions().unique(true));
  }

  /** Keep the best document of one duplicate group; delete the rest. */
  private long deleteExtras(MongoCollection<Document> collection, Document key) {
    List<Document> group = new ArrayList<>();
    collection
        .find(
            Filters.and(
                Filters.eq("workflowRunRef", key.get("workflowRunRef")),
                Filters.eq("name", key.get("name"))))
        .sort(Sorts.ascending("creationDate", "_id"))
        .into(group);
    if (group.size() <= 1) {
      return 0;
    }
    Document keeper =
        group.stream()
            .filter(doc -> TERMINAL_STATUSES.contains(doc.getString("status")))
            .findFirst()
            .orElse(group.get(0));
    long deleted = 0;
    for (Document doc : group) {
      if (doc.get("_id").equals(keeper.get("_id"))) {
        continue;
      }
      collection.deleteOne(Filters.eq("_id", doc.get("_id")));
      deleted++;
    }
    return deleted;
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("task_runs"), "node_uniqueness");
  }
}

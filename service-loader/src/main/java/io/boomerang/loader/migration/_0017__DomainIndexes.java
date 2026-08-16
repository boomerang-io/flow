package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;
import static io.boomerang.loader.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
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
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Action (manual/approval gate record) per TaskRun. Duplicates are repeat gate records
 * for the same TaskRun: the earliest — the record that actually gated — is kept, the rest are
 * deleted, then the unique {@code taskRunRef} index enforces the invariant going forward.
 */
@Change(id = "0005-action-task-run-uniqueness", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0005__ActionTaskRunUniqueness {

  private static final Logger LOG = LoggerFactory.getLogger(_0005__ActionTaskRunUniqueness.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String actions = names.resolve("actions");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db,
            actions,
            new Document("taskRunRef", new Document("$exists", true)),
            new Document("taskRunRef", "$taskRunRef"));
    long removed = 0;
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      removed += deleteAllButEarliest(db.getCollection(actions), key.get("taskRunRef"));
    }
    LOG.info(
        "actions dedupe — {} duplicate taskRunRef groups, {} duplicate gate records removed",
        duplicateGroups.size(),
        removed);
    ensureIndex(
        db, actions, "task_run", new Document("taskRunRef", 1), new IndexOptions().unique(true));
  }

  private long deleteAllButEarliest(MongoCollection<Document> collection, Object taskRunRef) {
    List<Object> staleIds = new ArrayList<>();
    try (MongoCursor<Document> cursor =
        collection
            .find(Filters.eq("taskRunRef", taskRunRef))
            .sort(Sorts.ascending("creationDate", "_id"))
            .skip(1)
            .iterator()) {
      while (cursor.hasNext()) {
        staleIds.add(cursor.next().get("_id"));
      }
    }
    return staleIds.isEmpty()
        ? 0
        : collection.deleteMany(Filters.in("_id", staleIds)).getDeletedCount();
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Deleted duplicate gate records are not restored.
    dropIndex(db, names.resolve("actions"), "task_run");
  }
}

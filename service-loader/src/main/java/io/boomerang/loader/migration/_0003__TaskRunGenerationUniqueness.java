package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;
import static io.boomerang.loader.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarantee one live TaskRun per DAG node generation: supersede historical
 * {@code (workflowRunRef, name)} duplicates, then create the unique {@code node_generation}
 * index on {@code (workflowRunRef, name, mapIndex, attempt)} — {@code mapIndex} is null for
 * non-fan-out tasks, {@code attempt} is absent for the live generation. Per duplicate group
 * the kept document is the one that finished (terminal status), else the earliest created;
 * the rest are stamped {@code superseded.at/by} and numbered {@code attempt} 1..n.
 */
@Change(id = "0003-task-run-generation-uniqueness", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0003__TaskRunGenerationUniqueness {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0003__TaskRunGenerationUniqueness.class);

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("succeeded", "failed", "invalid", "skipped", "cancelled", "timedout");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String taskRuns = names.resolve("task_runs");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db,
            taskRuns,
            new Document("superseded.at", new Document("$exists", false)),
            new Document("workflowRunRef", "$workflowRunRef").append("name", "$name"));
    long superseded = 0;
    Date now = new Date();
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      superseded += supersedeExtras(db.getCollection(taskRuns), key, now);
    }
    LOG.info(
        "task_runs generation dedupe — {} duplicate (workflowRunRef, name) groups, {} documents superseded",
        duplicateGroups.size(),
        superseded);
    ensureIndex(
        db,
        taskRuns,
        "node_generation",
        new Document("workflowRunRef", 1).append("name", 1).append("mapIndex", 1).append("attempt", 1),
        new IndexOptions().unique(true));
  }

  /** Keep the best live document of one duplicate group; stamp and number the rest. */
  private long supersedeExtras(MongoCollection<Document> collection, Document key, Date now) {
    List<Document> group = new ArrayList<>();
    collection
        .find(
            Filters.and(
                Filters.eq("workflowRunRef", key.get("workflowRunRef")),
                Filters.eq("name", key.get("name"))))
        .sort(Sorts.ascending("creationDate", "_id"))
        .into(group);
    List<Document> live = group.stream().filter(doc -> doc.get("superseded") == null).toList();
    if (live.size() <= 1) {
      return 0;
    }
    Document keeper =
        live.stream()
            .filter(doc -> TERMINAL_STATUSES.contains(doc.getString("status")))
            .findFirst()
            .orElse(live.get(0));
    // Numbering continues after any attempts already present in the group.
    int attempt =
        group.stream().mapToInt(doc -> (doc.get("attempt") != null) ? doc.getInteger("attempt") : 0).max().orElse(0);
    long superseded = 0;
    for (Document doc : live) {
      if (doc.get("_id").equals(keeper.get("_id"))) {
        continue;
      }
      attempt++;
      superseded++;
      collection.updateOne(
          Filters.eq("_id", doc.get("_id")),
          Updates.combine(
              Updates.set("superseded.at", now),
              Updates.set("superseded.by", "migration"),
              Updates.set("attempt", attempt)));
    }
    return superseded;
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Superseded stamps are left in place — they mark documents that were never live pairs.
    dropIndex(db, names.resolve("task_runs"), "node_generation");
  }
}

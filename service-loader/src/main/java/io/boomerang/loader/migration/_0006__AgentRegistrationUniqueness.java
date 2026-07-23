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
 * One agent record per {@code (name, host)} — re-registration becomes an upsert against the
 * unique index. Duplicates keep the most recently connected record; the stale ones are deleted.
 */
@Change(id = "0006-agent-registration-uniqueness", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0006__AgentRegistrationUniqueness {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0006__AgentRegistrationUniqueness.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String agents = names.resolve("agents");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db, agents, new Document("name", "$name").append("host", "$host"));
    long removed = 0;
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      removed += deleteAllButLatestConnected(db.getCollection(agents), key);
    }
    LOG.info(
        "agents dedupe — {} duplicate (name, host) groups, {} stale registrations removed",
        duplicateGroups.size(),
        removed);
    ensureIndex(
        db,
        agents,
        "registration",
        new Document("name", 1).append("host", 1),
        new IndexOptions().unique(true));
  }

  private long deleteAllButLatestConnected(MongoCollection<Document> collection, Document key) {
    List<Object> staleIds = new ArrayList<>();
    try (MongoCursor<Document> cursor =
        collection
            .find(
                Filters.and(
                    Filters.eq("name", key.get("name")), Filters.eq("host", key.get("host"))))
            .sort(Sorts.descending("lastConnectedDate", "_id"))
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
    // Deleted stale registrations are not restored — agents re-register on connect.
    dropIndex(db, names.resolve("agents"), "registration");
  }
}

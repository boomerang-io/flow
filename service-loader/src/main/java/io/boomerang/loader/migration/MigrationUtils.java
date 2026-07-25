package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.mongodb.client.model.IndexOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared idempotent index and duplicate-detection helpers for change units. */
public abstract class MigrationUtils {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationUtils.class);

  private MigrationUtils() {}

  /**
   * Create an index if absent — identical name+keys+options is a server no-op; a conflicting
   * definition logs a warning instead of failing the run.
   */
  public static boolean ensureIndex(
      MongoDatabase db, String collection, String name, Bson keys, IndexOptions options) {
    try {
      db.getCollection(collection).createIndex(keys, options.name(name).background(true));
      LOG.info("Ensured index {} on {}", name, collection);
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Could not create index {} on {} ({})", name, collection, e.getMessage());
      return false;
    }
  }

  /** Drop an index if present — best effort, absence is not an error. */
  public static void dropIndex(MongoDatabase db, String collection, String name) {
    try {
      db.getCollection(collection).dropIndex(name);
    } catch (RuntimeException ignored) {
      // index may not exist
    }
  }

  /** Duplicate key groups (group id + count) for a candidate unique key. */
  public static List<Document> findDuplicateKeys(
      MongoDatabase db, String collection, Document groupId) {
    return findDuplicateKeys(db, collection, new Document(), groupId);
  }

  /** Duplicate key groups among the documents matching {@code filter}. */
  public static List<Document> findDuplicateKeys(
      MongoDatabase db, String collection, Document filter, Document groupId) {
    List<Document> duplicates = new ArrayList<>();
    db.getCollection(collection)
        .aggregate(
            List.of(
                new Document("$match", filter),
                new Document(
                    "$group", new Document("_id", groupId).append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1)))))
        .allowDiskUse(true)
        .into(duplicates);
    return duplicates;
  }
}

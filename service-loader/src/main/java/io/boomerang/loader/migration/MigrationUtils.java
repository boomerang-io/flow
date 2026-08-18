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
   * Create an index if absent — identical name+keys+options is a server no-op.
   *
   * <p><b>Failure posture (T6-2) depends on {@code options.isUnique()}:</b> every unique index in
   * this codebase is preceded by a dedupe step, so a unique-index build failure (almost always
   * Mongo's {@code E11000 duplicate key}) means the dedupe missed a case — a genuine
   * data-integrity signal, not noise. That case is rethrown, aborting the change unit (and thus
   * the migration/deploy) rather than reporting success with the index silently absent. A
   * non-unique/performance index is best-effort: a conflicting definition (e.g. same name,
   * different keys from an out-of-band index) logs a warning and the run continues.
   */
  public static boolean ensureIndex(
      MongoDatabase db, String collection, String name, Bson keys, IndexOptions options) {
    try {
      db.getCollection(collection).createIndex(keys, options.name(name).background(true));
      LOG.info("Ensured index {} on {}", name, collection);
      return true;
    } catch (RuntimeException e) {
      if (Boolean.TRUE.equals(options.isUnique())) {
        throw new IllegalStateException(
            "Could not create UNIQUE index "
                + name
                + " on "
                + collection
                + " — a duplicate survived the dedupe step that must precede this call ("
                + e.getMessage()
                + ")",
            e);
      }
      LOG.warn("Could not create index {} on {} ({})", name, collection, e.getMessage());
      return false;
    }
  }

  /**
   * Create an index unless one with the same key pattern already exists under ANY name. Use this
   * for indexes that Spring Data used to auto-build from entity annotations on v4 installs (named
   * after the field, e.g. {@code name}, or the annotation's own {@code name}): re-creating the same
   * keys under a loader-chosen name would fail with {@code IndexOptionsConflict}, so the existing
   * index is kept and reported instead. Delegates to {@link #ensureIndex} when absent.
   */
  public static boolean ensureIndexKeys(
      MongoDatabase db, String collection, String name, Document keys, IndexOptions options) {
    for (Document existing : db.getCollection(collection).listIndexes()) {
      Document existingKeys = existing.get("key", Document.class);
      if (existingKeys != null && existingKeys.equals(keys)) {
        LOG.info(
            "Index on {} {} already exists as '{}' - not creating '{}'",
            collection,
            keys.toJson(),
            existing.getString("name"),
            name);
        return true;
      }
    }
    return ensureIndex(db, collection, name, keys, options);
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

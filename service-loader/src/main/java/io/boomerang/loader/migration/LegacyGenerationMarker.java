package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import java.util.Date;
import org.bson.Document;

/**
 * Reads and writes the durable {@link InstallGeneration} marker {@link
 * _0019__LegacyGenerationDetect} persists, in a small loader-owned collection ({@code
 * sys_migration_state}).
 *
 * <p>{@link InstallGeneration#detect} is a live read of {@code sys_changelog_flow} — cheap today,
 * but not a stable answer forever. This consolidated v3→v5 path never writes the legacy v4 chain's
 * {@code changeId: "4000"} marker (it goes straight from v3 to v5), so a v3 install's changelog
 * keeps satisfying {@link InstallGeneration#V3}'s detection rule (changeId {@code "112"} present,
 * {@code "4000"} absent) *forever* — including on every Flamingock run after this install has
 * fully completed its v3→v5 migration. Later v3-only change units (starting with {@link
 * _0020__V3DropDeadCollections}) must read the value captured the FIRST time this chain ever ran
 * against the database, not re-derive it from the still-V3-shaped changelog on every run — hence a
 * marker recorded once and then read, rather than {@link InstallGeneration#detect} called
 * everywhere.
 */
public abstract class LegacyGenerationMarker {

  /** Loader-owned collection (unprefixed name, resolved through {@link CollectionNames}). */
  public static final String COLLECTION = "sys_migration_state";

  private static final String MARKER_ID = "legacyGeneration";
  private static final String FIELD_GENERATION = "generation";
  private static final String FIELD_DETECTED_AT = "detectedAt";

  private LegacyGenerationMarker() {}

  /**
   * Persist the generation the first time this is called for a database; every subsequent call
   * (this run or a later one) returns the already-recorded value unchanged — first-write-wins,
   * matching the other seed change units' insert-if-absent idempotency.
   */
  public static InstallGeneration recordOnce(MongoDatabase db, CollectionNames names) {
    Document existing = findMarker(db, names);
    if (existing != null) {
      return InstallGeneration.valueOf(existing.getString(FIELD_GENERATION));
    }
    InstallGeneration detected = InstallGeneration.detect(db, names);
    db.getCollection(names.resolve(COLLECTION))
        .insertOne(
            new Document("_id", MARKER_ID)
                .append(FIELD_GENERATION, detected.name())
                .append(FIELD_DETECTED_AT, new Date()));
    return detected;
  }

  /**
   * Read the recorded generation. Falls back to a live {@link InstallGeneration#detect} if no
   * marker exists yet (defensive only — every v3-only change unit runs after {@link
   * _0019__LegacyGenerationDetect} in the chain, so this path is not expected to be taken).
   */
  public static InstallGeneration read(MongoDatabase db, CollectionNames names) {
    Document existing = findMarker(db, names);
    return existing != null
        ? InstallGeneration.valueOf(existing.getString(FIELD_GENERATION))
        : InstallGeneration.detect(db, names);
  }

  private static Document findMarker(MongoDatabase db, CollectionNames names) {
    return db.getCollection(names.resolve(COLLECTION)).find(Filters.eq("_id", MARKER_ID)).first();
  }
}

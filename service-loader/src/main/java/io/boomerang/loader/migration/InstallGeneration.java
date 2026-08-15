package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;

/**
 * The install generation a database was bootstrapped under, detected from the legacy loader's
 * Mongock changelog ({@code sys_changelog_flow}) — the same collection {@link
 * _0001__BaselineExistingInstall} inspects for existing-install detection.
 *
 * <p>The legacy {@code flow.loader} runs on {@code io.mongock:mongock-springboot-v3:5.3.3}
 * ({@code mongock.migration-repository-name=${flow.mongo.collection.prefix}sys_changelog_flow} in
 * its {@code application.properties}). Mongock's {@code io.mongock.driver.api.entry.ChangeEntry}
 * carries the changeset id in a field annotated {@code @Field("changeId")} — confirmed by
 * inspecting that class in the cached {@code mongock-driver-api} jar — so every changelog document
 * stores the changeset id verbatim under the key {@code changeId}. The legacy loader's own
 * {@code @ChangeSet(id = ...)} values give the markers this class checks for: {@code "112"}
 * ({@code FlowDatabaseChangeLog}, the v3 chain) and {@code "4000"} ({@code
 * FlowDatabasev4ChangeLog}, the first changeset of the v4 migration chain — it only ever runs
 * after the v3 chain has completed).
 *
 * <ul>
 *   <li>{@link #V3} — {@code changeId: "112"} exists and {@code changeId: "4000"} does not: the
 *       install completed the v3 chain but has never run the v3→v4 migration.
 *   <li>{@link #V4} — {@code changeId: "4000"} exists: the v4 chain has run.
 *   <li>{@link #FRESH} — the changelog collection is absent or empty: no legacy loader has ever
 *       run against this database.
 * </ul>
 */
public enum InstallGeneration {
  V3,
  V4,
  FRESH;

  private static final String CHANGE_ID_FIELD = "changeId";
  private static final String V3_MARKER = "112";
  private static final String V4_MARKER = "4000";

  /** Detect the generation of {@code db} from its legacy loader changelog, if any. */
  public static InstallGeneration detect(MongoDatabase db, CollectionNames names) {
    String collection = names.resolve("sys_changelog_flow");
    // countDocuments() returns 0 for a collection that does not exist, so this covers both the
    // "absent" and "empty" fresh-install cases without a separate existence check.
    if (db.getCollection(collection).countDocuments() == 0) {
      return FRESH;
    }
    if (db.getCollection(collection).find(Filters.eq(CHANGE_ID_FIELD, V4_MARKER)).first()
        != null) {
      return V4;
    }
    if (db.getCollection(collection).find(Filters.eq(CHANGE_ID_FIELD, V3_MARKER)).first()
        != null) {
      return V3;
    }
    // Neither marker is present but the changelog is non-empty - an install that predates
    // changeset 112, or an unrecognised shape. Default to V4 (i.e. "not v3") so the seed units
    // run their normal insert-if-absent behaviour rather than silently skipping data a real v3
    // install would need reconciled by the (separate) v3->v5 migration.
    return V4;
  }
}

package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Final cleanup pass of the v3→v5 migration chain (see "v3 → v5 migration
 * consolidation" in {@code specifications/merge-execution-plan.md}): asserts/drops two different
 * classes of leftover collection a v3 install should never carry by this point.
 *
 * <p><b>Class 1 — v4-era relationship-model/lock/scheduler intermediates.</b> These have no v5
 * use whatsoever (v5's relationship model is {@code rel_nodes}/{@code rel_edges}, built fresh by
 * {@link _0029__V3BuildRelationshipGraph} for a v3 install), the same way {@link
 * _0020__V3DropDeadCollections}'s Quartz collections have none — dropped unconditionally,
 * logging how much data (if any) was discarded:
 *
 * <ul>
 *   <li>{@code relationships}, {@code relationships_v1} — the pre-{@code 4041} relationship
 *       intermediates legacy changesets {@code 4007}/{@code 4012}/{@code 4031} wrote, superseded
 *       (per the scout classification's DROP list) before {@code 4041} even introduced the
 *       current model. A v3 install that never ran ANY v4 changeset has neither — this only
 *       matters for an install that was, at some point outside this loader's own history, pushed
 *       partway through the old v4 Mongock chain (e.g. an operator-run experiment) without ever
 *       recording {@code changeId: "4000"} (the marker {@link InstallGeneration} keys V4
 *       detection on) — hence gated V3, not V4: the generation marker legitimately reads V3 in
 *       exactly this scenario.
 *   <li>{@code locks} (RESOLVED WITH THE COLLECTION PREFIX, e.g. {@code flow_locks}) — a
 *       hypothetical v4-era lock collection from that same kind of partial/experimental run. This
 *       is NOT {@code _0020}'s Quartz {@code locks} (already gone by this point — {@code _0020}
 *       runs first and drops the flow-prefixed Quartz job-store shape), NOT the genuinely
 *       UNPREFIXED {@code locks} collection {@code alturkovic/distributed-lock} writes verbatim
 *       (never touched by {@link CollectionNames#resolve}, so this unit can never collide with
 *       it), and NOT v5's own {@code task_locks} (a different literal name entirely).
 *   <li>{@code quartz} — a differently-named Quartz artifact distinct from the {@code jobs}/{@code
 *       triggers}/{@code calendars}/{@code paused_trigger_groups}/{@code locks}/{@code
 *       schedulers} job-store shape {@code _0020} already targets by their real (verified against
 *       the dump) names.
 * </ul>
 *
 * <p><b>Class 2 — v3 source collections a specific earlier unit should have fully drained AND
 * dropped as its own last step</b> ({@code task_templates} by {@link _0022__V3MigrateTasks},
 * {@code global_config} by {@link _0031__V3MigrateGlobalParameters}, {@code workflows_revisions}
 * by {@link _0023__V3MigrateWorkflows}, {@code workflows_activity}/{@code
 * workflows_activity_approval}/{@code workflows_schedules} by {@link _0025__V3MigrateRuns}) —
 * verified against the real v3 dump ({@code flowabl-live-dump-20231106}, 23 collections) to be
 * gone by this point in every one of those cases (see {@code V3DumpMigrationTest}'s per-batch
 * assertions, each of which already proves its own unit's drop). Unlike Class 1, presence WITH
 * DATA here is not a deliberate "no v5 use" classification — it would mean an earlier unit's own
 * drain logic missed something, which is a bug to investigate, not data to discard silently. So
 * this unit only drops a Class 2 name when it is present AND EMPTY (an artifact of, for example,
 * the implicit-collection-creation-via-index hazard {@link _0033__V3Indexes}'s javadoc describes,
 * or a `renameCollection` step's rename leaving a residual reference); if one is found non-empty,
 * it is logged loudly and left completely alone for investigation — "log what you drop; never
 * drop a v5 collection" extends here to "never silently drop unconsumed v3 data either."
 *
 * <p>Idempotent: every drop is a {@code MongoCollection.drop()} on an already-absent (or already
 * confirmed empty-and-dropped) collection, a no-op on a second run.
 */
@Change(id = "0035-v3-drop-intermediates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0035__V3DropIntermediates {

  private static final Logger LOG = LoggerFactory.getLogger(_0035__V3DropIntermediates.class);

  /** Class 1 — v4-era intermediates with no v5 use at all. See the class javadoc. */
  private static final List<String> DEAD_V4_INTERMEDIATES =
      List.of("relationships", "relationships_v1", "locks", "quartz");

  /** Class 2 — v3 sources a specific earlier unit should already have drained AND dropped. */
  private static final List<String> SHOULD_BE_CONSUMED_V3_SOURCES =
      List.of(
          "task_templates",
          "global_config",
          "workflows_revisions",
          "workflows_activity",
          "workflows_activity_approval",
          "workflows_schedules");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — no v3-era intermediates to clean up.");
      return;
    }

    long deadDropped = 0;
    for (String name : DEAD_V4_INTERMEDIATES) {
      deadDropped += dropDeadIntermediate(db, names.resolve(name));
    }

    int lingering = 0;
    for (String name : SHOULD_BE_CONSUMED_V3_SOURCES) {
      if (dropIfEmptyElseWarn(db, names.resolve(name))) {
        lingering++;
      }
    }

    LOG.info(
        "v3 intermediate cleanup — {} document(s) discarded from dead v4-era collections, {} v3"
            + " source collection(s) still present (empty ones dropped, non-empty ones left for"
            + " investigation — see WARN logs above for any)",
        deadDropped,
        lingering);
  }

  /**
   * Class 1: always drop (a no-op if absent — {@code MongoCollection.drop()} tolerates that,
   * matching {@link _0020__V3DropDeadCollections#dropIfPresent}), logging how much (if anything)
   * was discarded.
   */
  private long dropDeadIntermediate(MongoDatabase db, String collection) {
    long count = db.getCollection(collection).countDocuments();
    db.getCollection(collection).drop();
    if (count > 0) {
      LOG.info("Dropped v4-era intermediate {} ({} documents)", collection, count);
    }
    return count;
  }

  /**
   * Class 2: drop only when present and empty; a non-empty lingering source is left untouched and
   * logged loudly.
   *
   * @return true if the collection was present (either dropped-because-empty, or left behind
   *     non-empty) — i.e. this earlier unit did NOT already make it disappear as expected.
   */
  private boolean dropIfEmptyElseWarn(MongoDatabase db, String collection) {
    if (!collectionExists(db, collection)) {
      return false;
    }
    long count = db.getCollection(collection).countDocuments();
    if (count == 0) {
      db.getCollection(collection).drop();
      LOG.info("Dropped empty lingering v3 source collection {}", collection);
      return true;
    }
    LOG.warn(
        "v3 source collection {} still holds {} document(s) that an earlier unit should have"
            + " fully migrated away — NOT dropping; investigate before assuming this data is"
            + " safe to discard.",
        collection,
        count);
    return true;
  }

  private boolean collectionExists(MongoDatabase db, String collection) {
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    return names.contains(collection);
  }

  @Rollback
  public void rollback() {
    // Destructive drops of legacy-only/already-migrated data - not restorable, matching the
    // other forward-only v3-only online migrations in this chain (e.g. _0020, _0012).
  }
}

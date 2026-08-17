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
 * H11: drops the JobRunr collections left behind by the two now-fully-removed JobRunr instances
 * (engine timeout jobs, flow schedule firing — both retired at E5, see {@code
 * e8b56fca}/{@code 1b7a57e2}), plus the genuinely unprefixed {@code locks} collection {@code
 * alturkovic/distributed-lock} wrote verbatim (itself removed by the E4 lock-free rework).
 *
 * <p><b>The two JobRunr instances used DIFFERENT, RAW (non-{@link CollectionNames#resolve})
 * table-prefix properties</b> — confirmed against the commits that configured them:
 *
 * <ul>
 *   <li>Engine's retired timeout-job instance: {@code org.jobrunr.database.table-prefix=
 *       ${flow.mongo.collection.prefix}jr_} — e.g. collections {@code flowjr_jobs}, {@code
 *       flowjr_recurring-jobs}, {@code flowjr_metadata}, {@code flowjr_migrations}, ... (NO
 *       separator between the configured prefix and {@code jr_} - unlike {@link CollectionNames},
 *       which always inserts exactly one {@code _}).
 *   <li>Flow's retired schedule-firing instance: {@code org.jobrunr.database.table-prefix=
 *       ${flow.mongo.collection.prefix}_sch_} — e.g. {@code flow_sch_jobs}, {@code
 *       flow_sch_recurring-jobs}, ... (the separating {@code _} is baked into the literal {@code
 *       _sch_} suffix itself, not added by any prefix-joining logic).
 * </ul>
 *
 * <p>Both are RAW string concatenations of the configured {@code flow.mongo.collection.prefix}
 * (as an operator would set it, e.g. {@code flow} — no guaranteed trailing {@code _}) directly
 * with the literal suffix — reproduced here by stripping {@link CollectionNames}'s own
 * normalisation (which always adds exactly one trailing {@code _}) back off before appending
 * {@code jr_}/{@code _sch_}, rather than by using {@link CollectionNames#resolve} directly (which
 * would wrongly double the separator, e.g. {@code flow__sch_jobs} instead of the real {@code
 * flow_sch_jobs}).
 *
 * <p><b>Ungated</b> — runs on every install generation, not just V4. Real-world V3/fresh installs
 * never had JobRunr, so the scan finds nothing there; but our own pre-E5 dev/test environments
 * could carry this residue under a {@code FRESH} (no legacy loader history at all) generation
 * marker too, since generation detection only looks at the LEGACY loader's changelog, not this
 * one's development history. A prefix-scan-and-drop is cheap and a guaranteed no-op wherever
 * there is nothing to find, so gating it to V4 would only narrow coverage for no real benefit.
 *
 * <p>Does NOT touch {@code task_locks} (v5's own current lock collection, a different literal
 * name entirely) or the flow-prefixed Quartz {@code locks} classified and dropped by {@link
 * _0004__V3DropDeadCollections}/{@link _0014__V3DropIntermediates} (V3-only — Quartz predates V4,
 * see the "v3 → v5 migration consolidation" scout findings in {@code
 * specifications/merge-execution-plan.md}: a V4 install postdates the Quartz→JobRunr migration,
 * so it never carries genuine Quartz artifacts under those names).
 *
 * <p>Idempotent: every drop is a {@code MongoCollection.drop()} on an already-absent collection,
 * a no-op on a second run; logs what (if anything) it discards.
 */
@Change(id = "0027-v4-drop-residual-collections", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0027__V4DropResidualCollections {

  private static final Logger LOG = LoggerFactory.getLogger(_0027__V4DropResidualCollections.class);

  /** The genuinely unprefixed collection {@code alturkovic/distributed-lock} wrote verbatim. */
  private static final String DISTRIBUTED_LOCK_COLLECTION = "locks";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String rawPrefix = rawConfiguredPrefix(names);
    String engineJobRunrPrefix = rawPrefix + "jr_";
    String flowJobRunrPrefix = rawPrefix + "_sch_";

    List<String> existing = new ArrayList<>();
    db.listCollectionNames().into(existing);

    long jobRunrDropped = 0;
    for (String collection : existing) {
      if (collection.startsWith(engineJobRunrPrefix) || collection.startsWith(flowJobRunrPrefix)) {
        jobRunrDropped += dropAndCount(db, collection);
      }
    }

    long locksDropped = 0;
    if (existing.contains(DISTRIBUTED_LOCK_COLLECTION)) {
      locksDropped = dropAndCount(db, DISTRIBUTED_LOCK_COLLECTION);
    }

    LOG.info(
        "V4 residual collection cleanup — {} document(s) discarded from {} JobRunr collection(s)"
            + " ({}*/{}* prefix scan), distributed-lock '{}' dropped: {}",
        jobRunrDropped,
        existing.stream()
            .filter(c -> c.startsWith(engineJobRunrPrefix) || c.startsWith(flowJobRunrPrefix))
            .count(),
        engineJobRunrPrefix,
        flowJobRunrPrefix,
        DISTRIBUTED_LOCK_COLLECTION,
        locksDropped);
  }

  /**
   * Undoes {@link CollectionNames}'s own normalisation (always exactly one trailing {@code _}) to
   * recover the raw {@code flow.mongo.collection.prefix} value the JobRunr {@code table-prefix}
   * properties concatenated directly, with no separator of their own.
   */
  private String rawConfiguredPrefix(CollectionNames names) {
    String normalised = names.resolve("");
    return normalised.endsWith("_") ? normalised.substring(0, normalised.length() - 1) : normalised;
  }

  private long dropAndCount(MongoDatabase db, String collection) {
    long count = db.getCollection(collection).countDocuments();
    db.getCollection(collection).drop();
    if (count > 0) {
      LOG.info("Dropped residual collection {} ({} documents)", collection, count);
    }
    return count;
  }

  @Rollback
  public void rollback() {
    // Destructive drops of dead, code-unreferenced collections - not restorable, matching the
    // other forward-only cleanup units in this chain (e.g. _0004, _0014).
  }
}

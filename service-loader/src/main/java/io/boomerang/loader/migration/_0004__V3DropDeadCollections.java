package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Drops the legacy collections v5 has no use for at all — a no-op on v4/fresh installs,
 * where they were never populated (v4's own loader already stopped writing most of these; a
 * v4-current install has nothing in them).
 *
 * <p>Generation gated on {@link LegacyGenerationMarker#read} (the value {@link
 * _0001__BaselineAndGenerationDetect} captured before any prior unit in this chain could mutate the
 * database) rather than a live {@link InstallGeneration#detect} — see that unit's javadoc.
 *
 * <p>Classification (see "v3 → v5 migration consolidation" in {@code
 * specifications/merge-execution-plan.md}), verified against a real v3 dump (23 collections,
 * {@code flowabl-live-dump-20231106}):
 *
 * <ul>
 *   <li>{@code workflows_activity_task} — v3 task-level activity, discarded by design (legacy
 *       {@code 4001} already dropped it on the v4 path; there is no v5 equivalent).
 *   <li>{@code jobs}, {@code triggers}, {@code calendars}, {@code paused_trigger_groups}, {@code
 *       locks}, {@code schedulers} — the full Quartz MongoDB job-store schema Quartz's own
 *       {@code quartz-mongodb} library creates under these exact (unprefixed-of-"quartz",
 *       flow-prefixed) names. Confirmed against the dump's document shapes: {@code jobs} holds
 *       {@code io.boomerang.quartz.WorkflowExecuteJob} entries, {@code schedulers} holds
 *       scheduler-instance heartbeats ({@code instanceId}/{@code checkinInterval}/{@code
 *       lastCheckinTime}), and {@code locks} carries a {@code keyGroup+keyName+type} unique index
 *       — all Quartz-store shapes, none read by v5 (JobRunr since E5; Quartz fully removed). This
 *       {@code locks} is the flow-prefixed Quartz collection ({@code names.resolve("locks")}) —
 *       distinct from the unprefixed {@code locks} collection {@code
 *       alturkovic/distributed-lock} writes verbatim (present in the dump too, always empty
 *       there); that one is a v4/v5-era addition, out of scope for a v3-only unit, and is tracked
 *       separately (CLAUDE.md T6 post-merge cleanup).
 *   <li>{@code tasks_locks} — the pre-v5 name for what is now {@code task_locks} (singular
 *       "task"), and a different shape besides: v5's {@code task_locks} keys leases on {@code
 *       expiresAt} and {@link _0018__EventAndLockIndexes} builds its indexes fresh. Locks are
 *       ephemeral, so there is nothing worth carrying over even by name.
 *   <li>{@code tokens} — v5's {@code TokenEntity} is a different shape than v3's, and legacy
 *       changeset {@code 4018} never provided a migration path between them either. Dropped
 *       outright; operators re-issue tokens post-migration.
 * </ul>
 *
 * <p><b>Deliberately left alone</b> (present in the dump, not dropped here):
 *
 * <ul>
 *   <li>{@code settings}, {@code task_templates}, {@code workflows_activity_approval}, {@code
 *       global_config}, {@code teams}, {@code users}, {@code workflows}, {@code
 *       workflows_revisions}, {@code workflows_schedules}, {@code workflows_activity} — every one
 *       of these carries real v3 data a LATER consolidated unit migrates directly into its v5
 *       shape; dropping them here would destroy the source data those units need. ({@code
 *       workflows_activity} is the workflow-level activity record and is kept — only its
 *       task-level counterpart above is discarded by design.)
 *   <li>{@code extensions} — not legacy at all: this is the live v5 {@code ExtensionEntity}
 *       collection ({@code io.boomerang.integrations.SlackService}, {@code
 *       IntegrationControllerV2}), still written today under the same name.
 *   <li>{@code sys_changelog_flow} — the legacy loader's own changelog; kept permanently as the
 *       historical record {@link InstallGeneration#detect} reads.
 *   <li>{@code sys_lock_flow} — Mongock's own lock collection; not ours to drop.
 * </ul>
 *
 * <p>Idempotent: {@code MongoCollection.drop()} on an already-absent collection is a no-op.
 */
@Change(id = "0004-v3-drop-dead-collections", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0004__V3DropDeadCollections {

  private static final Logger LOG = LoggerFactory.getLogger(_0004__V3DropDeadCollections.class);

  /** The Quartz MongoDB job-store schema in full — see class javadoc. */
  private static final List<String> QUARTZ_COLLECTIONS =
      List.of("jobs", "triggers", "calendars", "paused_trigger_groups", "locks", "schedulers");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — no dead collections to drop.");
      return;
    }
    long dropped = 0;
    dropped += dropIfPresent(db, names.resolve("workflows_activity_task"));
    for (String quartzCollection : QUARTZ_COLLECTIONS) {
      dropped += dropIfPresent(db, names.resolve(quartzCollection));
    }
    dropped += dropIfPresent(db, names.resolve("tasks_locks"));
    dropped += dropIfPresent(db, names.resolve("tokens"));
    LOG.info("v3 dead-collection cleanup — {} documents discarded across dropped collections", dropped);
  }

  private long dropIfPresent(MongoDatabase db, String collection) {
    long count = db.getCollection(collection).countDocuments();
    db.getCollection(collection).drop();
    if (count > 0) {
      LOG.info("Dropped v3 dead collection {} ({} documents)", collection, count);
    }
    return count;
  }

  @Rollback
  public void rollback() {
    // Destructive drop of legacy-only data with no v5 use - not restorable, matching the other
    // forward-only online migrations in this chain (e.g. _0015__DispatcherRename,
    // _0016__WorkspaceRename).
  }
}

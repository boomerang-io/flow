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
 * Remaining domain-collection indexes: actions uniqueness, dispatcher registration uniqueness,
 * and the v3-only index-parity pass. Formerly three separate units ({@code
 * _0005__ActionTaskRunUniqueness}, {@code _0006__AgentRegistrationUniqueness}, {@code
 * _0033__V3Indexes}) — merged for the same reason as {@code _0017__RunIndexes}/{@code
 * _0018__EventAndLockIndexes}: every index unit in this program now runs in Phase 4, after the
 * v3→v5 migration (Phase 2) and the v4 rename fixups (Phase 3) have already populated/renamed the
 * collections being indexed.
 *
 * <p><b>Collection-name correction (the reason this merge is NOT purely mechanical):</b> the
 * former {@code _0006__AgentRegistrationUniqueness} targeted the {@code agents} collection because
 * it used to run BEFORE {@code _0011__DispatcherRename} (DD-06's persisted rename, {@code agents}
 * -> {@code dispatchers}) in the old numbering. In the new chain, Phase 3's {@code
 * _0015__DispatcherRename} runs BEFORE this unit (Phase 4), so the dedupe + unique-index logic
 * below targets {@code dispatchers} directly — asserted in {@code LoaderMigrationTest} under the
 * post-rename name either way, but the change unit itself must now operate on the already-renamed
 * collection rather than relying on the rename to carry a uniqueness violation forward.
 *
 * <p><b>{@code _0033__V3Indexes} no longer needs to be "the LAST unit in the whole v3→v5 chain."</b>
 * That constraint existed because creating an index on a collection that does not exist yet
 * implicitly creates it, empty — colliding with a LATER {@code renameCollection} call elsewhere in
 * the old v3-only chain. Now that every migration/rename unit (Phases 2–3) runs strictly before
 * every index unit (Phase 4), no index unit anywhere in this program can run ahead of a rename or
 * data-populating step any more; the v3-gated portion below is retained as its own private method
 * purely to preserve its generation gate, not for ordering safety.
 */
@Change(id = "0019-domain-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0019__DomainIndexes {

  private static final Logger LOG = LoggerFactory.getLogger(_0019__DomainIndexes.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    actionTaskRunUniqueness(db, names);
    dispatcherRegistrationUniqueness(db, names);
    v3IndexParity(db, names);
  }

  // =====================================================================================
  // actions (formerly _0005__ActionTaskRunUniqueness)
  // =====================================================================================

  /**
   * One Action (manual/approval gate record) per TaskRun. Duplicates are repeat gate records
   * for the same TaskRun: the earliest — the record that actually gated — is kept, the rest are
   * deleted, then the unique {@code taskRunRef} index enforces the invariant going forward.
   */
  private void actionTaskRunUniqueness(MongoDatabase db, CollectionNames names) {
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

  // =====================================================================================
  // dispatchers (formerly _0006__AgentRegistrationUniqueness, targeting "agents" pre-rename)
  // =====================================================================================

  /**
   * One dispatcher record per {@code (name, host)} — re-registration becomes an upsert against
   * the unique index. Duplicates keep the most recently connected record; the stale ones are
   * deleted. Targets {@code dispatchers} directly — see the class javadoc's collection-name
   * correction note.
   */
  private void dispatcherRegistrationUniqueness(MongoDatabase db, CollectionNames names) {
    String dispatchers = names.resolve("dispatchers");
    List<Document> duplicateGroups =
        findDuplicateKeys(
            db, dispatchers, new Document("name", "$name").append("host", "$host"));
    long removed = 0;
    for (Document group : duplicateGroups) {
      Document key = group.get("_id", Document.class);
      removed += deleteAllButLatestConnected(db.getCollection(dispatchers), key);
    }
    LOG.info(
        "dispatchers dedupe — {} duplicate (name, host) groups, {} stale registrations removed",
        duplicateGroups.size(),
        removed);
    ensureIndex(
        db,
        dispatchers,
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

  // =====================================================================================
  // v3-only index parity (formerly _0033__V3Indexes)
  // =====================================================================================

  /**
   * V3-only. Creates the indexes a v3-sourced install needs that neither the general, ungated
   * index units above (nor {@code _0017__RunIndexes}/{@code _0018__EventAndLockIndexes}) nor the
   * v5 entities' own {@code @Indexed}/{@code @CompoundIndex} annotations (built by {@code
   * spring.data.mongodb.auto-index-creation=true} at every {@code service-core} boot) already
   * cover.
   *
   * <p><b>Overlap analysis</b> (inspected against the other index units and every entity's {@code
   * @Indexed}/{@code @CompoundIndex} annotations before writing anything below):
   *
   * <ul>
   *   <li><b>{@code users.email}</b> — {@code UserEntity.email} is only {@code @Indexed}
   *       (non-unique). GENUINELY MISSING: created here as a unique index. {@link
   *       MigrationUtils#ensureIndex} already swallows a build failure and logs a warning rather
   *       than aborting the run (see its javadoc) — the safe behaviour if a real install somehow
   *       carries two users sharing an email; the duplicate-count pre-check below exists only to
   *       make that situation loud, not to delete either user (deleting a real account to satisfy
   *       an index is out of scope and far riskier than the {@code task_runs}/{@code actions}/
   *       {@code dispatchers} bug-artifact dedupes above perform).
   *   <li><b>{@code workflows.creationDate}</b> — {@code WorkflowEntity} indexes only {@code
   *       name}. {@code WorkflowService.query} both sorts and range-filters on {@code
   *       creationDate}. GENUINELY MISSING: created here.
   *   <li><b>{@code workflow_revisions.(workflowRef, version)}</b> — ALREADY COVERED:
   *       {@code WorkflowRevisionEntity} declares {@code workflow_ref_version_idx} on exactly this
   *       pair. Skipped.
   *   <li><b>{@code workflow_revisions.version}</b> (standalone) — the compound index above has
   *       {@code version} as its SECOND key, so it cannot serve a version-only query (no current
   *       call site issues one, but the pair is not a substitute for the standalone case).
   *       GENUINELY MISSING: created here.
   *   <li><b>{@code tasks.name}</b> — ALREADY COVERED: {@code TaskEntity.name} is {@code
   *       @Indexed}, and {@code TaskService.query} filters on it directly. Skipped.
   *   <li><b>{@code tasks.creationDate}</b> — not indexed anywhere; {@code TaskService.query}'s
   *       default sort is {@code creationDate}. GENUINELY MISSING: created here.
   *   <li><b>{@code task_runs}/{@code workflow_runs}: {@code status}/{@code phase}</b> — ALREADY
   *       COVERED on both collections ({@code @Indexed} on the entity fields, plus {@code
   *       status_phase_type_idx}/{@code status_phase_idx} compounds and {@code
   *       _0017__RunIndexes}'s {@code claim_page} compounds). Skipped.
   *   <li><b>{@code task_runs.workflowRunRef}</b> — ALREADY COVERED: {@code @Indexed} on the
   *       entity plus {@code _0017__RunIndexes}'s {@code run_tasks} compound ({@code
   *       workflowRunRef, status, name}). {@code workflow_runs} has no {@code workflowRunRef}
   *       field (it IS the run) — not applicable there. Skipped.
   *   <li><b>{@code task_runs.name}</b> — the only real query pattern ({@code
   *       TaskRunRepository.findFirstByNameAndWorkflowRunRef}) is always scoped by {@code
   *       workflowRunRef} first, which {@code run_tasks} already serves as a prefix match; no
   *       standalone-name query exists. {@code workflow_runs} has no {@code name} field at all —
   *       not applicable. Skipped on both.
   *   <li><b>{@code task_runs}/{@code workflow_runs}: {@code labels}</b> — GENUINELY MISSING on
   *       both. {@code TaskRunService.query}/{@code WorkflowRunService.query} filter on {@code
   *       labels.<dynamic-key>} (one Mongo field path per label key), which a plain single-field
   *       index cannot serve. A MongoDB wildcard index ({@code labels.$**}) is the standard tool
   *       for indexing an arbitrary, caller-chosen set of sub-fields — created here on both
   *       collections.
   *   <li><b>{@code rel_edges.from} / {@code rel_edges.to}</b> — ALREADY COVERED:
   *       {@code RelationshipEdgeEntity} declares {@code from_to_idx} ({@code from: -1, to: -1}),
   *       {@code from_to_label_idx} ({@code from: -1, to: -1, label: -1}) and {@code
   *       to_label_idx} ({@code to: -1, label: -1}) — {@code from} leads the first two, {@code
   *       to} leads the third, so equality lookups on either field alone are already served by a
   *       compound-index prefix (MongoDB does not need a field to be the index's ONLY key to use
   *       it for an equality match on that field). Legacy {@code 4041} never created these, but
   *       v5's own entity does. Skipped — no new index added.
   * </ul>
   *
   * <p>Idempotent: every {@code ensureIndex} call is a no-op if the identically-named/keyed index
   * already exists (see {@link MigrationUtils#ensureIndex}).
   */
  private void v3IndexParity(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — the indexes below are unrelated to v3 migration parity.");
      return;
    }

    checkDuplicateEmails(db, names);
    ensureIndex(
        db, names.resolve("users"), "email_unique", new Document("email", 1), new IndexOptions().unique(true));

    ensureIndex(
        db,
        names.resolve("workflows"),
        "creation_date_sort",
        new Document("creationDate", 1),
        new IndexOptions());

    ensureIndex(
        db,
        names.resolve("workflow_revisions"),
        "version_lookup",
        new Document("version", 1),
        new IndexOptions());

    ensureIndex(
        db,
        names.resolve("tasks"),
        "creation_date_sort",
        new Document("creationDate", 1),
        new IndexOptions());

    ensureIndex(
        db,
        names.resolve("task_runs"),
        "label_wildcard",
        new Document("labels.$**", 1),
        new IndexOptions());
    ensureIndex(
        db,
        names.resolve("workflow_runs"),
        "label_wildcard",
        new Document("labels.$**", 1),
        new IndexOptions());

    LOG.info("v3 index parity complete.");
  }

  /**
   * Loud, non-destructive warning only — see {@link #v3IndexParity}'s {@code users.email} bullet.
   * {@link MigrationUtils#ensureIndex} already tolerates the unique-index build failing outright;
   * this just gives operators a clearer signal than its generic "could not create index" log.
   */
  private void checkDuplicateEmails(MongoDatabase db, CollectionNames names) {
    List<Document> duplicates =
        findDuplicateKeys(db, names.resolve("users"), new Document("email", "$email"));
    if (!duplicates.isEmpty()) {
      LOG.warn(
          "{} duplicate email value(s) found across users — the unique email_unique index will"
              + " fail to build until these are resolved manually; no user document is deleted by"
              + " this migration.",
          duplicates.size());
    }
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("actions"), "task_run");
    dropIndex(db, names.resolve("dispatchers"), "registration");

    dropIndex(db, names.resolve("users"), "email_unique");
    dropIndex(db, names.resolve("workflows"), "creation_date_sort");
    dropIndex(db, names.resolve("workflow_revisions"), "version_lookup");
    dropIndex(db, names.resolve("tasks"), "creation_date_sort");
    dropIndex(db, names.resolve("task_runs"), "label_wildcard");
    dropIndex(db, names.resolve("workflow_runs"), "label_wildcard");
  }
}

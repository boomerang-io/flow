package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;
import static io.boomerang.loader.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Creates the indexes a v3-sourced install needs that neither {@link
 * _0002__TaskRunClaimAndSweepIndexes}–{@link _0010__WorkflowStatusIndex} (the general, ungated
 * index units every install gets) nor the v5 entities' own {@code @Indexed}/{@code
 * @CompoundIndex} annotations (built by {@code spring.data.mongodb.auto-index-creation=true} at
 * every {@code service-core} boot) already cover.
 *
 * <p><b>Why v3-only, and why this must stay the LAST unit in the whole v3→v5 chain</b> (see "v3 →
 * v5 migration consolidation" in {@code specifications/merge-execution-plan.md}): creating an
 * index on a collection that does not exist yet implicitly CREATES that collection, empty.
 * Several v3-only units earlier in this chain ({@link _0023__V3MigrateWorkflows} renaming {@code
 * workflows_revisions} -> {@code workflow_revisions}, {@link _0025__V3MigrateRuns} renaming
 * {@code workflows_activity} -> {@code workflow_runs}/{@code workflows_activity_approval} ->
 * {@code actions}/{@code workflows_schedules} -> {@code workflow_schedules}) still have to
 * {@code renameCollection} a v3 source into one of these target names. If this unit ran EARLIER
 * in the chain than one of those renames, its {@code ensureIndex} call would create the empty
 * target collection first, and the later {@code renameCollection} would then collide with an
 * already-existing (if empty) target. On a fresh/v4 install none of the gated v3 units above ever
 * run at all, so this hazard is specific to the v3 migration path — hence this unit is gated the
 * same way as the rest of the v3-only chain, not left ungated like {@link
 * _0002__TaskRunClaimAndSweepIndexes} and friends. Do not move it earlier in the numbering.
 *
 * <p><b>Overlap analysis</b> (inspected against {@code _0002}–{@code _0010} and every entity's
 * {@code @Indexed}/{@code @CompoundIndex} annotations before writing anything below):
 *
 * <ul>
 *   <li><b>{@code users.email}</b> — {@code UserEntity.email} is only {@code @Indexed}
 *       (non-unique). GENUINELY MISSING: created here as a unique index. {@link
 *       MigrationUtils#ensureIndex} already swallows a build failure and logs a warning rather
 *       than aborting the run (see its javadoc) — the safe behaviour if a real install somehow
 *       carries two users sharing an email; the duplicate-count pre-check below exists only to
 *       make that situation loud, not to delete either user (deleting a real account to satisfy
 *       an index is out of scope and far riskier than the {@code task_runs}/{@code actions}/
 *       {@code agents} bug-artifact dedupes {@code _0003}/{@code _0005}/{@code _0006} perform).
 *   <li><b>{@code workflows.creationDate}</b> — {@code WorkflowEntity} indexes only {@code name}.
 *       {@code WorkflowService.query} both sorts and range-filters on {@code creationDate}.
 *       GENUINELY MISSING: created here.
 *   <li><b>{@code workflow_revisions.(workflowRef, version)}</b> — ALREADY COVERED:
 *       {@code WorkflowRevisionEntity} declares {@code workflow_ref_version_idx} on exactly this
 *       pair. Skipped.
 *   <li><b>{@code workflow_revisions.version}</b> (standalone) — the compound index above has
 *       {@code version} as its SECOND key, so it cannot serve a version-only query (no current
 *       call site issues one, but the pair is not a substitute for the standalone case).
 *       GENUINELY MISSING: created here.
 *   <li><b>{@code tasks.name}</b> — ALREADY COVERED: {@code TaskEntity.name} is {@code @Indexed},
 *       and {@code TaskService.query} filters on it directly. Skipped.
 *   <li><b>{@code tasks.creationDate}</b> — not indexed anywhere; {@code TaskService.query}'s
 *       default sort is {@code creationDate}. GENUINELY MISSING: created here.
 *   <li><b>{@code task_runs}/{@code workflow_runs}: {@code status}/{@code phase}</b> — ALREADY
 *       COVERED on both collections ({@code @Indexed} on the entity fields, plus {@code
 *       status_phase_type_idx}/{@code status_phase_idx} compounds and {@code _0002}/{@code
 *       _0004}'s {@code claim_page} compounds). Skipped.
 *   <li><b>{@code task_runs.workflowRunRef}</b> — ALREADY COVERED: {@code @Indexed} on the entity
 *       plus {@code _0002}'s {@code run_tasks} compound ({@code workflowRunRef, status, name}).
 *       {@code workflow_runs} has no {@code workflowRunRef} field (it IS the run) — not
 *       applicable there. Skipped.
 *   <li><b>{@code task_runs.name}</b> — the only real query pattern ({@code
 *       TaskRunRepository.findFirstByNameAndWorkflowRunRef}) is always scoped by {@code
 *       workflowRunRef} first, which {@code run_tasks} already serves as a prefix match; no
 *       standalone-name query exists. {@code workflow_runs} has no {@code name} field at all —
 *       not applicable. Skipped on both.
 *   <li><b>{@code task_runs}/{@code workflow_runs}: {@code labels}</b> — GENUINELY MISSING on
 *       both. {@code TaskRunService.query}/{@code WorkflowRunService.query} filter on {@code
 *       labels.<dynamic-key>} (one Mongo field path per label key), which a plain single-field
 *       index cannot serve. A MongoDB wildcard index ({@code labels.$**}) is the standard tool for
 *       indexing an arbitrary, caller-chosen set of sub-fields — created here on both collections.
 *   <li><b>{@code rel_edges.from} / {@code rel_edges.to}</b> — ALREADY COVERED:
 *       {@code RelationshipEdgeEntity} declares {@code from_to_idx} ({@code from: -1, to: -1}),
 *       {@code from_to_label_idx} ({@code from: -1, to: -1, label: -1}) and {@code to_label_idx}
 *       ({@code to: -1, label: -1}) — {@code from} leads the first two, {@code to} leads the
 *       third, so equality lookups on either field alone are already served by a compound-index
 *       prefix (MongoDB does not need a field to be the index's ONLY key to use it for an
 *       equality match on that field). Legacy {@code 4041} never created these, but v5's own
 *       entity does. Skipped — no new index added.
 * </ul>
 *
 * <p>Idempotent: every {@code ensureIndex} call is a no-op if the identically-named/keyed index
 * already exists (see {@link MigrationUtils#ensureIndex}).
 */
@Change(id = "0033-v3-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0033__V3Indexes {

  private static final Logger LOG = LoggerFactory.getLogger(_0033__V3Indexes.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
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
   * Loud, non-destructive warning only — see the class javadoc's {@code users.email} bullet.
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
    dropIndex(db, names.resolve("users"), "email_unique");
    dropIndex(db, names.resolve("workflows"), "creation_date_sort");
    dropIndex(db, names.resolve("workflow_revisions"), "version_lookup");
    dropIndex(db, names.resolve("tasks"), "creation_date_sort");
    dropIndex(db, names.resolve("task_runs"), "label_wildcard");
    dropIndex(db, names.resolve("workflow_runs"), "label_wildcard");
  }
}

package io.boomerang.loader.migration;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
 * V4-only (maintainer ruling M-2 — "best-effort v4 repair units", see "v3 → v5 migration
 * consolidation" in {@code specifications/merge-execution-plan.md}). Re-derives {@code
 * taskVersion} on v4 installs (never on v3 — a v3-sourced install gets {@code taskVersion}
 * correct at source, via {@link _0023__V3MigrateWorkflows}/{@link _0026__V3MigrateTaskRunRefs}).
 *
 * <p><b>The v4 bug, confirmed against the live loader/entity code:</b> legacy {@code 4005} does
 * {@code Document.replace} on a freshly-constructed, still-empty {@code Document} — a no-op — and
 * {@code 4033}/{@code 4034}/{@code 4035} (the units that were supposed to introduce {@code
 * taskVersion} for {@code task_runs}/{@code workflow_revisions}/{@code workflow_templates}
 * respectively) each read the NEW key ({@code taskVersion}, not yet written by anything) instead
 * of the OLD one ({@code templateVersion}) they were migrating away from — see {@link
 * _0026__V3MigrateTaskRunRefs}'s javadoc, which fixes the identical mistake for a v3-sourced
 * install. Net effect on every real v4 install: {@code taskVersion} is {@code null} everywhere a
 * {@code taskRef} is set, and — unlike the v3 path — there is no surviving {@code
 * templateVersion}/{@code config} source field left to read it back from; the original version
 * number is gone from the document entirely.
 *
 * <p><b>What IS recoverable, and how</b> (per M-2): for a given {@code taskRef}, if {@code
 * task_revisions} holds EXACTLY ONE revision whose {@code parentRef} matches it, that revision's
 * {@code version} is unambiguously the version that {@code taskRef} step must have been pinned to
 * — there was never a second version for it to have meant. Where a task has been revised more
 * than once, which specific version a given step originally referenced is genuinely unrecoverable
 * (the same information loss {@code 4033}/{@code 4034}/{@code 4035} caused, with no other field
 * anywhere carrying it) — those are left {@code null} and only counted/logged, never guessed.
 *
 * <p>Scanned collections, matching the three places {@code taskVersion} lives: {@code
 * workflow_revisions.tasks[]}, {@code workflow_templates.tasks[]} (both DAG-task-array shapes —
 * see the real-dump spot checks in {@code V3DumpMigrationTest#assertWorkflowsMigrated}/{@code
 * #assertTemplatesExtracted} for the field shape, which v4 shares), and {@code task_runs}
 * (top-level {@code taskRef}/{@code taskVersion} fields).
 *
 * <p><b>Approver groups are NOT repairable on v4 — a separate, unrelated data-loss finding worth
 * flagging here too</b> (per M-2): legacy {@code 4011} stripped {@code teams.approverGroups[]}
 * during the v3→v4 migration and nothing ever wrote a v4-side {@code approver_groups} collection
 * to replace it, so the source data is gone on every v4 install with no repair path — unlike
 * {@code taskVersion} above, there is no surviving fragment anywhere to re-derive it from. This
 * unit does not attempt it; {@link _0037__V4RepairWorkflowAudit} carries the same note since it is
 * this program's other v4 repair unit.
 *
 * <p>Idempotent: only touches entries where {@code taskVersion} is currently null/absent; a
 * second run finds nothing left to change for the entries it already repaired (they are no longer
 * null), and the genuinely-ambiguous/unresolved ones are re-counted identically every run since
 * they are deliberately never written.
 */
@Change(id = "0036-v4-repair-task-versions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0036__V4RepairTaskVersions {

  private static final Logger LOG = LoggerFactory.getLogger(_0036__V4RepairTaskVersions.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V4) {
      LOG.info("Not a v4 install — taskVersion is either already correct (v3-sourced) or n/a.");
      return;
    }

    MongoCollection<Document> taskRevisions = db.getCollection(names.resolve("task_revisions"));
    RepairCounts revisions = repairTaskArrayCollection(db, names.resolve("workflow_revisions"), taskRevisions);
    RepairCounts templates = repairTaskArrayCollection(db, names.resolve("workflow_templates"), taskRevisions);
    RepairCounts runs = repairTaskRuns(db, names.resolve("task_runs"), taskRevisions);

    LOG.info(
        "v4 taskVersion repair — workflow_revisions: {} repaired/{} left null (ambiguous)/{} left"
            + " null (unresolved); workflow_templates: {} repaired/{} ambiguous/{} unresolved;"
            + " task_runs: {} repaired/{} ambiguous/{} unresolved",
        revisions.repaired, revisions.ambiguous, revisions.unresolved,
        templates.repaired, templates.ambiguous, templates.unresolved,
        runs.repaired, runs.ambiguous, runs.unresolved);
  }

  /** {@code workflow_revisions}/{@code workflow_templates}: a {@code tasks[]} array per document. */
  @SuppressWarnings("unchecked")
  private RepairCounts repairTaskArrayCollection(
      MongoDatabase db, String collectionName, MongoCollection<Document> taskRevisions) {
    MongoCollection<Document> collection = db.getCollection(collectionName);
    RepairCounts counts = new RepairCounts();

    for (Document doc : collection.find()) {
      List<Document> tasks = (List<Document>) doc.get("tasks");
      if (tasks == null || tasks.isEmpty()) {
        continue;
      }
      boolean changed = false;
      for (Document task : tasks) {
        if (task.get("taskVersion") != null) {
          continue;
        }
        String taskRef = task.getString("taskRef");
        if (taskRef == null || taskRef.isBlank()) {
          continue;
        }
        Integer resolved = resolveUnambiguousVersion(taskRevisions, taskRef, counts);
        if (resolved != null) {
          task.put("taskVersion", resolved);
          changed = true;
          counts.repaired++;
        }
      }
      if (changed) {
        collection.updateOne(Filters.eq("_id", doc.get("_id")), Updates.set("tasks", tasks));
      }
    }
    return counts;
  }

  /** {@code task_runs}: {@code taskRef}/{@code taskVersion} live directly on the document. */
  private RepairCounts repairTaskRuns(
      MongoDatabase db, String collectionName, MongoCollection<Document> taskRevisions) {
    MongoCollection<Document> taskRuns = db.getCollection(collectionName);
    RepairCounts counts = new RepairCounts();

    FindIterable<Document> candidates =
        taskRuns.find(
            Filters.and(
                Filters.or(Filters.exists("taskVersion", false), Filters.eq("taskVersion", null)),
                Filters.exists("taskRef", true),
                Filters.ne("taskRef", null)));
    for (Document run : candidates) {
      String taskRef = run.getString("taskRef");
      if (taskRef == null || taskRef.isBlank()) {
        continue;
      }
      Integer resolved = resolveUnambiguousVersion(taskRevisions, taskRef, counts);
      if (resolved != null) {
        taskRuns.updateOne(Filters.eq("_id", run.get("_id")), Updates.set("taskVersion", resolved));
        counts.repaired++;
      }
    }
    return counts;
  }

  /**
   * @return the version to repair with if {@code task_revisions} holds exactly one revision for
   *     {@code taskRef}; otherwise {@code null} (bumping the ambiguous/unresolved counters as it
   *     goes — see the class javadoc's "What IS recoverable" section).
   */
  private Integer resolveUnambiguousVersion(
      MongoCollection<Document> taskRevisions, String taskRef, RepairCounts counts) {
    List<Document> matches = new ArrayList<>();
    taskRevisions.find(Filters.eq("parentRef", taskRef)).into(matches);
    if (matches.isEmpty()) {
      counts.unresolved++;
      return null;
    }
    if (matches.size() > 1) {
      counts.ambiguous++;
      return null;
    }
    return matches.get(0).getInteger("version");
  }

  private static final class RepairCounts {
    long repaired;
    long ambiguous;
    long unresolved;
  }

  @Rollback
  public void rollback() {
    // Best-effort repair of already-lost data - not restorable (there is nothing to roll back
    // TO), matching the other forward-only v3/v4-only online migrations in this chain.
  }
}

package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Migrates {@code task_runs.templateRef} (a task NAME) -> {@code taskRef} (the v5 task
 * {@code _id}, resolved against the {@code tasks} collection {@link _0022__V3MigrateTasks} has
 * already written) and {@code templateVersion} -> {@code taskVersion}, matching legacy changeset
 * {@code 4033} ({@code v4ConvertTRTemplateRefToTaskRef}) - <b>with its bug fixed</b>.
 *
 * <p><b>The v4 bug:</b> {@code 4033} does {@code entity.put("taskVersion",
 * entity.get("taskVersion"))} - it reads the NEW key it is in the middle of introducing (which does
 * not exist yet on any pre-migration document) instead of the old {@code templateVersion} key, so
 * {@code taskVersion} was written as {@code null} on every real v4 install (also confirmed by
 * {@code 4034}/{@code 4035} repeating the identical mistake for {@code workflow_revisions}/{@code
 * workflow_templates} task steps - out of scope here, those collections belong to a later batch).
 * This unit reads {@code templateVersion} correctly.
 *
 * <p>The {@code templateRef} -> {@code taskRef} resolution reproduces {@code 4033} exactly: match
 * by NAME against {@code tasks} (post-{@code _0022}, i.e. already-slugified v5 names) - {@code
 * task_runs.templateRef} was already written in that slugified form on a real v3/v4 install, the
 * same convention every other legacy task-name reference (workflow revision task steps, etc.) uses.
 *
 * <p><b>On a real v3 dump this unit is a no-op</b>: v3's task activity lives entirely in {@code
 * workflows_activity_task}, which {@link _0020__V3DropDeadCollections} drops by design (no v5
 * equivalent) - {@code task_runs} does not exist pre-migration on a v3 install (verified: absent
 * from the 23-collection real dump). It is implemented correctly regardless, for any v3 install
 * that somehow does carry a {@code task_runs} collection (e.g. one that was partially, then rolled
 * back from, a v4 upgrade attempt).
 *
 * <p>Idempotent: both renames are gated on the OLD key still being present, so a second run (or a
 * fresh install where {@code task_runs} never had these fields at all) touches nothing.
 */
@Change(id = "0026-v3-migrate-task-run-refs", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0026__V3MigrateTaskRunRefs {

  private static final Logger LOG = LoggerFactory.getLogger(_0026__V3MigrateTaskRunRefs.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — task_runs already in v5 shape (or never existed).");
      return;
    }

    MongoCollection<Document> taskRuns = db.getCollection(names.resolve("task_runs"));
    if (taskRuns.countDocuments() == 0) {
      LOG.info(
          "No task_runs documents to migrate — v3 task activity lives in"
              + " workflows_activity_task (dropped by _0020), never in task_runs.");
      return;
    }

    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));
    long refsMigrated = 0;
    long versionsFixed = 0;
    long unresolvedRefs = 0;

    for (Document run : taskRuns.find()) {
      boolean changed = false;

      Object templateRef = run.get("templateRef");
      if (templateRef != null) {
        Document task = tasks.find(Filters.eq("name", templateRef)).first();
        if (task != null) {
          run.put("taskRef", task.get("_id").toString());
          run.remove("templateRef");
          changed = true;
          refsMigrated++;
        } else {
          unresolvedRefs++;
          LOG.warn("Unable to resolve task_runs.templateRef '{}' against tasks", templateRef);
        }
      }

      // FIX for legacy 4033: read the OLD templateVersion key, not the (absent) new taskVersion.
      Object templateVersion = run.get("templateVersion");
      if (templateVersion != null) {
        run.put("taskVersion", templateVersion);
        run.remove("templateVersion");
        changed = true;
        versionsFixed++;
      }

      if (changed) {
        taskRuns.replaceOne(Filters.eq("_id", run.getObjectId("_id")), run);
      }
    }

    LOG.info(
        "v3 task_runs refs migrated — {} taskRef resolved, {} taskVersion fixed, {} unresolved refs",
        refsMigrated,
        versionsFixed,
        unresolvedRefs);
  }

  @Rollback
  public void rollback() {
    // Forward-only, matching the other v3-only online migrations in this chain.
  }
}

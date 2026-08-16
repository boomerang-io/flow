package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.LinkedList;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Merges the task-catalogue migration with the task-run reference fix (formerly separate
 * units {@code _0022__V3MigrateTasks} and {@code _0026__V3MigrateTaskRunRefs}) — the second always
 * runs immediately after the first and depends on nothing else, since it only needs the {@code
 * tasks} collection this same unit has just populated.
 *
 * <p>The former THIRD sibling in this area, {@code _0034__V3ReconcileCatalogue} (which topped up a
 * v3 install's migrated catalogue with any of the 87 out-of-the-box seed tasks/revisions it was
 * missing), is DROPPED entirely rather than folded in here — now that the seed change units run
 * AFTER this whole migration chain (Phase 5, {@code _0022__SeedTaskCatalogue}), that unit's own
 * insert-if-absent logic (match existing tasks by name, insert missing ones under the seed's own
 * ids, insert missing revisions by {@code (parentRef, version)}) already performs the exact same
 * reconciliation over the exact same data — verified against the real v3 dump (still lands on 89
 * tasks / 132 revisions, the one true gap being the seed catalogue's {@code Manual Approval} v2)
 * and against {@code LoaderMigrationTest}'s synthetic v3 fixture (89 tasks / 131 revisions, no v2
 * gap there). {@code _0034}'s own global-task-graph step is likewise subsumed: {@code
 * _0012__V3BuildRelationshipGraph} (Phase 2, runs before the Phase 5 seed) already writes a {@code
 * task:<id>} node + {@code root--hasTask-->} edge for every row this unit leaves in {@code tasks},
 * and the seed's own graph step covers anything it inserts afterwards. See "Post-G consolidation
 * review" in {@code specifications/merge-execution-plan.md} for the redundancy analysis.
 *
 * <h2>Task catalogue</h2>
 *
 * Single pass: v3 {@code task_templates} (89 documents on the verified real dump, each
 * with an embedded {@code revisions[]} array, 131 elements total) -> v5 {@code tasks} + {@code
 * task_revisions}, written directly in the v5 shape.
 *
 * <p>This SQUASHES legacy changesets {@code 4004} (the {@code task_templates} split + nodetype ->
 * type mapping + config -> naive params), {@code 4030} (parent name -> {@code parentRef}, resolved
 * here directly against the id this unit is already writing — never the intermediate name-based
 * reference 4030 had to repair), {@code 4032} (the {@code task_templates}/{@code
 * task_template_revisions} -> {@code tasks}/{@code task_revisions} rename, folded in since we write
 * the final collection names from the start) and {@code 4043} (the config/params merge — see {@link
 * #mergeParams}, which reproduces its algorithm literally, including the unmatched-param fallback
 * branch that never actually fires on real data because {@code 4004} always derives its param list
 * FROM the config array 1:1).
 *
 * <p><b>Field mapping, verified against a real v3 dump and against {@code
 * service-loader/src/main/resources/seed/tasks.json}/{@code task-revisions.json} (the 87-task/
 * 130-revision catalogue those files ship, which is the known-correct final shape):</b>
 *
 * <ul>
 *   <li>{@code tasks._id} <- {@code task_templates._id}, preserved verbatim (every downstream
 *       reference — {@code task_revisions.parentRef}, the Phase 5 seed's reconciliation match, a
 *       later unit's {@code rel_nodes} {@code task:<id>} node — depends on this).
 *   <li>{@code tasks.name} <- {@code task_templates.name} (a DISPLAY name), slugified with {@code
 *       4004}'s exact algorithm: {@code trim().toLowerCase().replace(' ', '-')}. Verified against
 *       all 87 matched seed names — none need anything smarter (no punctuation beyond spaces).
 *   <li>{@code tasks.type} <- {@code task_templates.nodetype}: {@code templateTask} -> {@code
 *       template}, {@code customTask} -> {@code custom}, every native type ({@code acquirelock},
 *       {@code releaselock}, {@code decision}, {@code eventwait}, {@code manual}, {@code
 *       runworkflow}, {@code runscheduledworkflow}, {@code script}, {@code approval}, {@code
 *       setwfproperty}, {@code setwfstatus}) passed through unchanged — this is {@code 4004}'s
 *       mapping, and matches {@link io.boomerang.common.enums.TaskType} exactly. <b>One documented
 *       exception</b>: the well-known {@code sleep} system task (see {@link #SLEEP_TASK_ID}) is
 *       templated ({@code nodetype=templateTask}) on a genuine v3 install (verified in the dump —
 *       {@code category=Utilities}, {@code arguments=["system","sleep"]}), but legacy changeset
 *       {@code 4010} (order 4010, between {@code 4004} and this batch's other squashed units)
 *       hardcoded it to a native {@code sleep} system task on every real v4 upgrade, overwriting the
 *       {@code task_templates} document wholesale from a bundled JSON resource before {@code 4030}+
 *       ever ran. That hardcode is what the seed catalogue's {@code sleep} entry reflects ({@code
 *       type=sleep}, revision {@code category=Workflow}, {@code spec.arguments=[]}) — reproduced
 *       here as the minimal three-field override in {@link #migrateTask} / {@link
 *       #migrateRevision} rather than replacing the whole document, since every other field (name,
 *       description, icon, the single {@code duration} config/param) already matches the v3 data
 *       byte-for-byte.
 *   <li>{@code tasks.status}/{@code verified} <- passed through as-is.
 *   <li>{@code tasks.labels} <- {@code {}}, {@code tasks.annotations} <- {@code
 *       {"boomerang#io/generation":"3","boomerang#io/kind":"Task"}} (the {@code #}-for-{@code .}
 *       escaping {@code MongoConfiguration.setMapKeyDotReplacement("#")} applies — written already
 *       escaped, matching every seed annotation map) — v3 has neither field; {@code 4004} always
 *       set these two literal maps.
 *   <li>{@code tasks.creationDate} <- {@code task_templates.createdDate}.
 *   <li>{@code task_revisions._id} <- a fresh {@link ObjectId} (matching {@code 4004}, which minted
 *       one per revision rather than reusing anything from v3).
 *   <li>{@code task_revisions.parentRef} <- the v5 task's {@code _id}, as a STRING (matches the
 *       seed's shape and {@code TaskRevisionEntity.parentRef : String} — never the parent's name,
 *       which is what pre-{@code 4030} intermediate state used and this unit never writes).
 *   <li>{@code task_revisions.version/displayName/description/category/icon} <- the v3 task-level
 *       fields ({@code name}, {@code description}, {@code category}, {@code icon}) repeated onto
 *       EVERY revision (matches {@code 4004} exactly — these are task-level in v3, revision-level in
 *       v5) plus the revision-level {@code version}.
 *   <li>{@code task_revisions.changelog} <- {@code {author: revision.changelog.userId, reason:
 *       revision.changelog.reason, date: revision.changelog.date}} — {@code userName} is DROPPED
 *       (PII, per the batch instructions). Two of the 131 real revisions (the oldest revision of
 *       tasks whose changelog tracking predates the field) have no {@code changelog} at all;
 *       verified in the dump and handled by omitting the key rather than inventing one.
 *   <li>{@code task_revisions.spec.{arguments,command,envs,image,results,script,workingDir}} <-
 *       passed straight through from the v3 revision element via {@code Document.get} (matching
 *       {@code 4004} literally, including writing an explicit {@code null} when the v3 revision
 *       lacks the key — verified against the seed: 54/130 revisions carry {@code null} for all four
 *       of {@code envs/results/script/workingDir} in lockstep, because they all trace back to the
 *       same {@code .get()}-on-an-absent-key call).
 *   <li>{@code task_revisions.spec.params[]} <- {@link #mergeParams}.
 * </ul>
 *
 * <p>Idempotent: tasks are matched (and skipped if already migrated) by their preserved {@code
 * _id}; revisions by {@code (parentRef, version)}. {@code task_templates} is dropped once every
 * document has been processed, so a second run finds nothing left to do and both collections come
 * out byte-for-byte identical.
 */
@Change(id = "0006-v3-migrate-task-catalogue", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0006__V3MigrateTaskCatalogue {

  private static final Logger LOG = LoggerFactory.getLogger(_0006__V3MigrateTaskCatalogue.class);

  /**
   * The well-known "Sleep" system task id — verified identical across the real v3 dump, the legacy
   * {@code 4010} hardcode resource, and the v5 seed catalogue. See the class javadoc.
   */
  private static final ObjectId SLEEP_TASK_ID = new ObjectId("5bd97bea5a5df954ad592c06");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — task_templates already migrated (or never existed) in v5 shape.");
      return;
    }
    migrateTaskCatalogue(db, names);
    migrateTaskRunRefs(db, names);
  }

  // =====================================================================================
  // task_templates -> tasks + task_revisions
  // =====================================================================================

  private void migrateTaskCatalogue(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> taskTemplates = db.getCollection(names.resolve("task_templates"));
    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));
    MongoCollection<Document> taskRevisions = db.getCollection(names.resolve("task_revisions"));

    long tasksMigrated = 0;
    long revisionsMigrated = 0;
    long sourceCount = taskTemplates.countDocuments();

    for (Document source : taskTemplates.find()) {
      ObjectId taskId = source.getObjectId("_id");
      boolean isSleep = SLEEP_TASK_ID.equals(taskId);

      if (tasks.find(Filters.eq("_id", taskId)).first() == null) {
        tasks.insertOne(migrateTask(source, taskId, isSleep));
        tasksMigrated++;
      }

      @SuppressWarnings("unchecked")
      List<Document> revisions = (List<Document>) source.get("revisions");
      if (revisions == null) {
        continue;
      }
      String parentRef = taskId.toString();
      for (Document revision : revisions) {
        Object version = revision.get("version");
        boolean exists =
            taskRevisions
                    .find(Filters.and(Filters.eq("parentRef", parentRef), Filters.eq("version", version)))
                    .first()
                != null;
        if (!exists) {
          taskRevisions.insertOne(migrateRevision(source, parentRef, revision, isSleep));
          revisionsMigrated++;
        }
      }
    }

    taskTemplates.drop();
    LOG.info(
        "v3 task catalogue migrated — {} source documents, {} tasks migrated, {} revisions"
            + " migrated, task_templates dropped",
        sourceCount,
        tasksMigrated,
        revisionsMigrated);
  }

  private Document migrateTask(Document source, ObjectId taskId, boolean isSleep) {
    Document task = new Document();
    task.put("_id", taskId);
    task.put("name", slugify(source.getString("name")));
    task.put("type", isSleep ? "sleep" : resolveType(source.getString("nodetype")));
    task.put("status", source.getString("status"));
    task.put("verified", source.getBoolean("verified", Boolean.FALSE));
    task.put("labels", new Document());
    task.put(
        "annotations",
        new Document("boomerang#io/generation", "3").append("boomerang#io/kind", "Task"));
    task.put("creationDate", source.getDate("createdDate"));
    return task;
  }

  /** {@code 4004}'s slugification: {@code trim().toLowerCase().replace(' ', '-')} - verbatim. */
  private String slugify(String displayName) {
    return displayName.trim().toLowerCase().replace(' ', '-');
  }

  /** {@code 4004}'s nodetype -> type mapping - verbatim (the {@code sleep} override lives in the caller). */
  private String resolveType(String nodetype) {
    if ("templateTask".equals(nodetype)) {
      return "template";
    }
    if ("customTask".equals(nodetype)) {
      return "custom";
    }
    return nodetype;
  }

  private Document migrateRevision(Document source, String parentRef, Document revision, boolean isSleep) {
    Document rev = new Document();
    rev.put("_id", new ObjectId());
    rev.put("parentRef", parentRef);
    rev.put("displayName", source.getString("name"));
    rev.put("description", source.getString("description"));
    rev.put("category", isSleep ? "Workflow" : source.getString("category"));
    rev.put("icon", source.get("icon"));
    rev.put("version", revision.get("version"));

    Document changelogSource = (Document) revision.get("changelog");
    if (changelogSource != null) {
      rev.put(
          "changelog",
          new Document("author", changelogSource.getString("userId"))
              .append("reason", changelogSource.getString("reason"))
              .append("date", changelogSource.getDate("date")));
    }

    Document spec = new Document();
    spec.put("params", mergeParams(revision));
    spec.put("arguments", isSleep ? List.of() : revision.get("arguments"));
    spec.put("command", revision.get("command"));
    spec.put("envs", revision.get("envs"));
    spec.put("image", revision.get("image"));
    spec.put("results", revision.get("results"));
    spec.put("script", revision.get("script"));
    spec.put("workingDir", revision.get("workingDir"));
    rev.put("spec", spec);
    return rev;
  }

  /**
   * Reproduces {@code 4004}'s naive params-from-config step followed by {@code 4043}'s
   * config/params merge, literally - including the unmatched-param fallback branch ({@code 4043}'s
   * final loop) that never actually fires on real v3 data (every param {@code 4004} creates is
   * derived FROM a config item by name, so {@code 4043}'s by-name match always succeeds) but is
   * kept for a config/version mismatch we haven't seen.
   */
  @SuppressWarnings("unchecked")
  private List<Document> mergeParams(Document revision) {
    List<Document> configs = (List<Document>) revision.get("config");

    // 4004: one naive param per config item (name<-key, type always "string", description/
    // defaultValue copied straight from config).
    List<Document> params = new LinkedList<>();
    if (configs != null) {
      for (Document config : configs) {
        Document param = new Document();
        param.put("name", config.get("key"));
        param.put("type", "string");
        param.put("description", config.get("description"));
        param.put("defaultValue", config.get("defaultValue"));
        params.add(param);
      }
    }

    // 4043: config is the base document (so it keeps label/placeholder/readOnly/options/
    // required/helpertext/defaultValue - everything config carries beyond what 4004 copied),
    // key->name, values->value if present, then merge in the naive param's defaultValue/
    // description if those are non-empty.
    List<Document> merged = new LinkedList<>();
    if (configs != null) {
      for (Document config : configs) {
        Document param = new Document(config);
        param.put("name", param.get("key"));
        param.remove("key");
        Object values = param.get("values");
        if (values != null && !values.toString().isEmpty()) {
          param.put("value", values);
          param.remove("values");
        }
        Document match = removeByName(params, param.get("name"));
        if (match != null) {
          Object defaultValue = match.get("defaultValue");
          if (defaultValue != null && !defaultValue.toString().isEmpty()) {
            param.put("defaultValue", defaultValue);
          }
          Object description = match.get("description");
          if (description != null && !description.toString().isEmpty()) {
            param.put("description", description);
          }
        }
        merged.add(param);
      }
    }

    // 4043: any naive param left unmatched (no config item shared its name) survives as a
    // bare string-typed param.
    for (Document leftover : params) {
      Document param = new Document();
      param.put("name", leftover.get("name"));
      param.put("label", leftover.get("name"));
      param.put("type", "string");
      param.put("description", leftover.get("description"));
      param.put("defaultValue", leftover.get("defaultValue"));
      merged.add(param);
    }
    return merged;
  }

  private Document removeByName(List<Document> params, Object name) {
    for (Document param : params) {
      if (param.get("name") != null && param.get("name").toString().equals(String.valueOf(name))) {
        params.remove(param);
        return param;
      }
    }
    return null;
  }

  // =====================================================================================
  // task_runs.templateRef/templateVersion -> taskRef/taskVersion
  // =====================================================================================

  /**
   * Migrates {@code task_runs.templateRef} (a task NAME) -> {@code taskRef} (the v5 task {@code
   * _id}, resolved against the {@code tasks} collection {@link #migrateTaskCatalogue} has already
   * written earlier in this SAME unit) and {@code templateVersion} -> {@code taskVersion},
   * matching legacy changeset {@code 4033} ({@code v4ConvertTRTemplateRefToTaskRef}) - <b>with its
   * bug fixed</b>.
   *
   * <p><b>The v4 bug:</b> {@code 4033} does {@code entity.put("taskVersion",
   * entity.get("taskVersion"))} - it reads the NEW key it is in the middle of introducing (which
   * does not exist yet on any pre-migration document) instead of the old {@code templateVersion}
   * key, so {@code taskVersion} was written as {@code null} on every real v4 install (also
   * confirmed by {@code 4034}/{@code 4035} repeating the identical mistake for {@code
   * workflow_revisions}/{@code workflow_templates} task steps - out of scope here, those
   * collections belong to a different unit). This method reads {@code templateVersion} correctly.
   *
   * <p>The {@code templateRef} -> {@code taskRef} resolution reproduces {@code 4033} exactly:
   * match by NAME against {@code tasks} (post-catalogue-migration, i.e. already-slugified v5
   * names) - {@code task_runs.templateRef} was already written in that slugified form on a real
   * v3/v4 install, the same convention every other legacy task-name reference (workflow revision
   * task steps, etc.) uses.
   *
   * <p><b>On a real v3 dump this method is a no-op</b>: v3's task activity lives entirely in
   * {@code workflows_activity_task}, which {@link _0004__V3DropDeadCollections} drops by design
   * (no v5 equivalent) - {@code task_runs} does not exist pre-migration on a v3 install (verified:
   * absent from the 23-collection real dump), and no unit in this chain ever populates {@code
   * task_runs} from v3 source data either (v3 has no per-task execution record at all). Positioned
   * here - immediately after the task catalogue migrates, rather than after the run migration
   * elsewhere in the chain - deliberately: since {@code task_runs} never holds real v3 data at any
   * point in this pipeline, its position relative to the run migration is immaterial: it is
   * implemented correctly regardless, for any v3 install that somehow does carry a {@code
   * task_runs} collection (e.g. one that was partially, then rolled back from, a v4 upgrade
   * attempt) - the same fixture {@code LoaderMigrationTest} exercises.
   *
   * <p>Idempotent: both renames are gated on the OLD key still being present, so a second run (or
   * a fresh install where {@code task_runs} never had these fields at all) touches nothing.
   */
  private void migrateTaskRunRefs(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> taskRuns = db.getCollection(names.resolve("task_runs"));
    if (taskRuns.countDocuments() == 0) {
      LOG.info(
          "No task_runs documents to migrate — v3 task activity lives in"
              + " workflows_activity_task (dropped by _0002), never in task_runs.");
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
    // task_templates is dropped once migrated, and workflows/task_runs reference tasks by the ids
    // this unit preserves - not restorable, matching the other forward-only v3-only units in this
    // chain.
  }
}

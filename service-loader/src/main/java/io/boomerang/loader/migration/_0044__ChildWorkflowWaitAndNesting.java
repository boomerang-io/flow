package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything an existing install needs for child workflow composition:
 *
 * <ul>
 *   <li>the {@code wait} param on the {@code run-workflow} catalogue task, so a workflow author can
 *       ask the parent task to take the child run's terminal status instead of ending immediately;
 *   <li>the {@code max.nesting.depth} key in the {@code workflowrun} settings document, the cap the
 *       engine checks before submitting a child;
 *   <li>a {@code workflow_runs {initiatedByRef, phase}} index, which the cascade cancel of a
 *       cancelled or timed out run pages to find its still in-flight children. {@code
 *       workflow_runs} has no index leading with {@code initiatedByRef} today, so without this the
 *       cascade collection-scans once per completed run.
 * </ul>
 *
 * <p>Idempotent on all three: the param is appended only when absent from the latest revision (the
 * {@code _0040__DeclareRunWorkflowParams} pattern, and like it the revision version is NOT bumped),
 * the settings key only when absent from the existing config list (the {@code
 * _0029__AddGitHubOAuthSettings} pattern), and the index through {@code ensureIndexKeys}. A fresh
 * install picks all of it up from {@code seed/task-revisions.json} and {@code seed/settings.json}
 * instead and finds nothing to do here.
 */
@Change(id = "0044-child-workflow-wait-and-nesting", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0044__ChildWorkflowWaitAndNesting {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0044__ChildWorkflowWaitAndNesting.class);

  static final String RUN_WORKFLOW_PARENT_REF = "603591f5c267b8ce33782571";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    declareWaitParam(db, names);
    addNestingDepthSetting(db, names);
    ensureIndexKeys(
        db,
        names.resolve("workflow_runs"),
        "initiated_by_phase",
        new Document("initiatedByRef", 1).append("phase", 1),
        new IndexOptions());
  }

  /** Append {@code wait} to the latest {@code run-workflow} revision's declared params. */
  private void declareWaitParam(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    Document latest =
        revisions
            .find(Filters.eq("parentRef", RUN_WORKFLOW_PARENT_REF))
            .sort(Sorts.descending("version"))
            .limit(1)
            .first();
    if (latest == null) {
      LOG.warn("No run-workflow task revision found - skipping the wait param");
      return;
    }
    Document spec = latest.get("spec", Document.class);
    List<Document> existingParams =
        spec != null && spec.get("params") != null
            ? (List<Document>) spec.get("params")
            : List.of();
    Set<String> existingNames = new LinkedHashSet<>();
    existingParams.forEach(p -> existingNames.add(p.getString("name")));
    if (existingNames.contains("wait")) {
      LOG.info("Task revision {} already declares the wait param - skipping", latest.get("_id"));
      return;
    }
    revisions.updateOne(
        Filters.eq("_id", latest.get("_id")), Updates.push("spec.params", waitParam()));
    LOG.info("Declared the wait param on run-workflow task revision {}", latest.get("_id"));
  }

  private static Document waitParam() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("description", "");
    fields.put("label", "Wait for the child run to finish");
    fields.put("type", "boolean");
    fields.put("required", false);
    fields.put("placeholder", "");
    fields.put(
        "helpertext",
        "When enabled, this task waits for the child run and takes its terminal status: the task"
            + " succeeds only if the child run succeeds.");
    fields.put("defaultValue", "false");
    fields.put("readOnly", false);
    fields.put("name", "wait");
    return new Document(fields);
  }

  /** Add {@code max.nesting.depth} to the existing {@code workflowrun} settings document. */
  private void addNestingDepthSetting(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document workflowRun = settings.find(Filters.eq("key", "workflowrun")).first();
    if (workflowRun == null) {
      LOG.warn("No 'workflowrun' settings document found - nothing to backfill.");
      return;
    }
    List<Document> existingConfig = workflowRun.getList("config", Document.class);
    List<Document> config =
        existingConfig == null ? new ArrayList<>() : new ArrayList<>(existingConfig);
    if (config.stream().anyMatch(c -> "max.nesting.depth".equals(c.getString("key")))) {
      LOG.info("max.nesting.depth already present - nothing to backfill.");
      return;
    }
    config.add(nestingDepthConfig());
    settings.updateOne(
        Filters.eq("_id", workflowRun.get("_id")), Updates.set("config", config));
    LOG.info("Backfilled max.nesting.depth onto the existing 'workflowrun' document.");
  }

  private static Document nestingDepthConfig() {
    return new Document("description",
            "How many levels of child workflows a run may nest. A runworkflow task at the cap"
                + " fails instead of submitting another child.")
        .append("key", "max.nesting.depth")
        .append("label", "Maximum Child Workflow Nesting Depth")
        .append("type", "number")
        .append("value", "5")
        .append("readOnly", false);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // The param declaration and the settings key are additive onto documents an operator may have
    // edited since - forward-only, matching the other catalogue and settings units in this chain.
    dropIndex(db, names.resolve("workflow_runs"), "initiated_by_phase");
  }
}

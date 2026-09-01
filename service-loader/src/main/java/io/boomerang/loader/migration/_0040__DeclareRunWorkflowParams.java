package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declares the node params {@code run-workflow} and {@code run-scheduled-workflow} actually read
 * ({@code TaskExecutionService.runWorkflow}/{@code runScheduledWorkflow}), on the latest revision
 * of each catalogue Task.
 *
 * <p><b>Why.</b> Both catalogue Tasks previously declared an empty {@code spec.params}, which
 * {@code WorkflowService.validateDeclaredParams} treats as "not modelled here" rather than "no
 * params allowed" — a workflow task node could carry any param name at all, undeclared and
 * unchecked. Combined with the save path never having rejected a param with no value, a caller
 * that supplied {@code {"name": "workflowRef"}} with no value saved exactly that: a name-only
 * placeholder. At run time the engine read the (now-declared, always-null) value back as empty
 * and failed the task. Declaring the params these two task types actually consume closes that gap
 * going forward: an undeclared name is now rejected outright, and (paired with the new
 * WORKFLOW_TASK_PARAM_MISSING_VALUE check) a declared-but-value-less one is too.
 *
 * <p><b>Idempotency.</b> For each parentRef, only the single highest-{@code version} revision is
 * touched, and only params not already present by name are appended — a rerun against an
 * already-declared (or freshly-seeded, since {@code seed/task-revisions.json} carries the same
 * params) database modifies nothing.
 */
@Change(id = "0040-declare-run-workflow-params", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0040__DeclareRunWorkflowParams {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0040__DeclareRunWorkflowParams.class);

  static final String RUN_WORKFLOW_PARENT_REF = "603591f5c267b8ce33782571";
  static final String RUN_SCHEDULED_WORKFLOW_PARENT_REF = "61dcb509c570b75ec2c432f8";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));

    declareParams(
        revisions,
        RUN_WORKFLOW_PARENT_REF,
        List.of(workflowRefParam("The reference (or slug) of the Workflow to run.")));

    declareParams(
        revisions,
        RUN_SCHEDULED_WORKFLOW_PARENT_REF,
        List.of(
            workflowRefParam("The reference (or slug) of the Workflow to schedule."),
            param(
                "futureIn",
                "Future In",
                "number",
                "How many futurePeriod units from now the Workflow should run."),
            param(
                "futurePeriod",
                "Future Period",
                "text",
                "The unit futureIn is measured in: minutes, hours, days, weeks, or months."),
            param(
                "timezone",
                "Timezone",
                "text",
                "The timezone time is set in (e.g. America/New_York). Only applied for days,"
                    + " weeks, and months."),
            param(
                "time",
                "Time",
                "text",
                "The 24-hour clock time (e.g. 14:30) to run at. Only applied for days, weeks,"
                    + " and months.")));
  }

  private void declareParams(
      MongoCollection<Document> revisions, String parentRef, List<Document> declaredParams) {
    Document latest =
        revisions
            .find(Filters.eq("parentRef", parentRef))
            .sort(Sorts.descending("version"))
            .limit(1)
            .first();
    if (latest == null) {
      LOG.warn("No task revision found for parentRef {} - skipping", parentRef);
      return;
    }

    Document spec = latest.get("spec", Document.class);
    List<Document> existingParams =
        spec != null && spec.get("params") != null
            ? (List<Document>) spec.get("params")
            : List.of();
    Set<String> existingNames = new LinkedHashSet<>();
    existingParams.forEach(p -> existingNames.add(p.getString("name")));

    List<Document> missingParams =
        declaredParams.stream().filter(p -> !existingNames.contains(p.getString("name"))).toList();
    if (missingParams.isEmpty()) {
      LOG.info("Task revision {} already declares all required params - skipping", latest.get("_id"));
      return;
    }

    revisions.updateOne(
        Filters.eq("_id", latest.get("_id")),
        Updates.pushEach("spec.params", missingParams));
    LOG.info(
        "Declared {} param(s) on task revision {}: {}",
        missingParams.size(),
        latest.get("_id"),
        missingParams.stream().map(p -> p.getString("name")).toList());
  }

  private static Document workflowRefParam(String helperText) {
    return param("workflowRef", "Workflow Reference", "text", helperText);
  }

  private static Document param(String name, String label, String type, String helperText) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("description", "");
    fields.put("label", label);
    fields.put("type", type);
    fields.put("required", true);
    fields.put("placeholder", "");
    fields.put("helpertext", helperText);
    fields.put("readOnly", false);
    fields.put("name", name);
    return new Document(fields);
  }

  @Rollback
  public void rollback() {
    // Not reversible in place - a rerun of a prior deploy would need the params removed again,
    // but nothing downstream reads their absence, so there is no forward need to strip them back
    // out. An operator can $pull the named entries from spec.params manually if ever required.
  }
}

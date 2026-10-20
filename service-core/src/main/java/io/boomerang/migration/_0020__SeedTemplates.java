package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.springframework.core.env.Environment;

/**
 * Seed the remaining catalogue documents a fresh install needs: the starter workflow templates
 * users clone from, and the integration templates the integrations screen lists.
 *
 * <ul>
 *   <li>{@code workflow_templates} — {@code looking-through-planets-with-http-call} and {@code
 *       mongodb-email-query-results}, the two starters legacy changeset 4016 ({@code
 *       v4MigrateTemplateWorkflows}) produced by folding a workflow and its revision into one
 *       {@code WorkflowTemplateEntity}. Legacy 4046 had already merged the v4 {@code config[]}
 *       into {@code params[]}, matching {@code WorkflowTemplateEntity.params}.
 *   <li>{@code integration_templates} — GitHub ({@code active}) and Slack ({@code inactive}),
 *       matching {@code IntegrationTemplateEntity}. The credentials themselves live in the {@code
 *       integration} setting built by {@link _0016__BuildSettingsFromSeed}.
 * </ul>
 *
 * <p>Workflow templates are guarded on {@code name} + {@code version} (the entity's two indexed
 * fields) and integration templates on {@code name}, so an install that already holds either keeps
 * its own — including an operator's enable/disable state on an integration. The seed carries no
 * ids: each template takes a new one, and a template task's {@code taskRef} names its catalogue
 * task, resolved to the live id on insert.
 *
 * <p>Not generation-gated. The v3 units run before this seed, so on a v3-sourced install
 * {@link _0011__V3ExtractWorkflowTemplates} has already extracted the source template workflows
 * into {@code workflow_templates}, and the name-and-version guard makes those two documents a
 * no-op. {@code integration_templates} has no v3 counterpart at all -
 * integrations are a v5 feature - so an upgraded install receives the out-of-the-box GitHub and
 * Slack integration templates exactly as a fresh install does; leaving it empty would have left
 * every upgraded install unable to configure an integration.
 */
@Change(id = "0020-seed-templates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0020__SeedTemplates {

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<Document> workflowTemplates = SeedResources.load("seed/workflow-templates.json");
    int workflowsInserted = 0;
    for (Document template : workflowTemplates) {
      resolveTaskRefs(db, names, template);
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("workflow_templates"),
          Filters.and(
              Filters.eq("name", template.getString("name")),
              Filters.eq("version", template.getInteger("version"))),
          template)) {
        workflowsInserted++;
      }
    }
    SeedResources.logSeeded("workflow templates", workflowsInserted, workflowTemplates.size());

    List<Document> integrationTemplates = SeedResources.load("seed/integration-templates.json");
    int integrationsInserted = 0;
    for (Document template : integrationTemplates) {
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("integration_templates"),
          Filters.eq("name", template.getString("name")),
          template)) {
        integrationsInserted++;
      }
    }
    SeedResources.logSeeded(
        "integration templates", integrationsInserted, integrationTemplates.size());
  }

  /**
   * In the seed a template task's {@code taskRef} names the catalogue task; stored, it is the task's
   * id. The catalogue is seeded first, so every name resolves.
   */
  static void resolveTaskRefs(MongoDatabase db, CollectionNames names, Document template) {
    for (Document task : template.getList("tasks", Document.class, List.of())) {
      String taskName = task.getString("taskRef");
      if (taskName == null) {
        continue;
      }
      Document live =
          db.getCollection(names.resolve("tasks")).find(Filters.eq("name", taskName)).first();
      if (live == null) {
        throw new IllegalStateException(
            "Workflow template " + template.getString("name") + " names unknown task " + taskName);
      }
      task.put("taskRef", live.get("_id").toString());
    }
  }

  @Rollback
  public void rollback() {
    // Templates may have been cloned or configured by the time a rollback runs - forward-only.
  }
}

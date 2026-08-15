package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;

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
 *       integration} setting seeded by {@link _0016__SeedSettings}.
 * </ul>
 *
 * <p>Workflow templates are guarded on {@code name} + {@code version} (the entity's two indexed
 * fields) and integration templates on {@code name}, so an install that already holds either keeps
 * its own — including an operator's enable/disable state on an integration.
 */
@Change(id = "0018-seed-templates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0018__SeedTemplates {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    List<Document> workflowTemplates = SeedResources.load("seed/workflow-templates.json");
    int workflowsInserted = 0;
    for (Document template : workflowTemplates) {
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

  @Rollback
  public void rollback() {
    // Templates may have been cloned or configured by the time a rollback runs - forward-only.
  }
}

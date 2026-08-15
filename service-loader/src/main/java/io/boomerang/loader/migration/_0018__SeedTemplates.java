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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>Workflow templates are guarded on {@code _id} OR ({@code name} + {@code version}, the
 * entity's two indexed fields) and integration templates on {@code _id} OR {@code name}, so an
 * install that already holds either keeps its own — including an operator's enable/disable state
 * on an integration. The {@code _id} half of each guard is defence-in-depth: it skips a template
 * whose {@code _id} already exists even if its natural key has since changed.
 *
 * <p><b>Skipped entirely on a v3 install</b> ({@link InstallGeneration#V3}): {@code
 * workflow_templates} is empty there, so both seed documents would insert, while their source
 * workflows ({@code _id} {@code 62be6a32…e7} and {@code 62be6a3e…e9}, {@code scope="template"})
 * still live in {@code workflows} — a later v3→v5 unit extracts those into {@code
 * workflow_templates} too, duplicating the content. The (separate) v3→v5 migration is what
 * populates this collection for a v3 install.
 */
@Change(id = "0018-seed-templates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0018__SeedTemplates {

  private static final Logger LOG = LoggerFactory.getLogger(_0018__SeedTemplates.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (InstallGeneration.detect(db, names) == InstallGeneration.V3) {
      LOG.info(
          "v3 install detected — skipping template seed; the v3->v5 migration extracts the "
              + "source template workflows into workflow_templates for this install.");
      return;
    }
    List<Document> workflowTemplates = SeedResources.load("seed/workflow-templates.json");
    int workflowsInserted = 0;
    for (Document template : workflowTemplates) {
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("workflow_templates"),
          Filters.or(
              Filters.eq("_id", template.get("_id")),
              Filters.and(
                  Filters.eq("name", template.getString("name")),
                  Filters.eq("version", template.getInteger("version")))),
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
          Filters.or(
              Filters.eq("_id", template.get("_id")), Filters.eq("name", template.getString("name"))),
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

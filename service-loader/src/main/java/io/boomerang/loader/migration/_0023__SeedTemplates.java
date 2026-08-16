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
 *       integration} setting seeded by {@link _0021__SeedSettings}.
 * </ul>
 *
 * <p>Workflow templates are guarded on {@code _id} OR ({@code name} + {@code version}, the
 * entity's two indexed fields) and integration templates on {@code _id} OR {@code name}, so an
 * install that already holds either keeps its own — including an operator's enable/disable state
 * on an integration. The {@code _id} half of each guard is defence-in-depth: it skips a template
 * whose {@code _id} already exists even if its natural key has since changed.
 *
 * <p><b>Still generation-gated — deliberately NOT relaxed like the other Phase 5 seeds.</b>
 * {@code _0021__SeedSettings}/{@code _0022__SeedTaskCatalogue} dropped their v3 skip because the
 * v3 migration ALWAYS pre-populates their target collections under matching keys, making the
 * guard's non-{@code _id} half redundant. This unit is different for {@code
 * integration_templates}: NO v3→v5 unit migrates anything into it at all (no v3 dump collection
 * maps to integration templates), so on a v3-sourced install with no source workflows to extract
 * (verified: {@code LoaderMigrationTest}'s synthetic v3 fixture has no {@code workflows} data
 * whatsoever), an unconditional seed would insert the two out-of-the-box GitHub/Slack templates
 * where today's chain leaves this collection empty — a genuine behaviour change, not a no-op.
 * {@code workflow_templates} on its own would have been safe to relax (its two seed documents
 * collide by {@code _id} with what {@code _0010__V3ExtractWorkflowTemplates} always produces on
 * the real dump), but the two halves share one guard clause, and splitting them is not worth the
 * complexity for a change unit this small. Kept, whole, gated on {@link InstallGeneration#V3}: on
 * v3, {@code workflow_templates} is empty at the point this seed used to matter, so both seed
 * documents would insert, while their source workflows ({@code _id} {@code 62be6a32…e7} and
 * {@code 62be6a3e…e9}, {@code scope="template"}) still live in {@code workflows} — {@code
 * _0010__V3ExtractWorkflowTemplates} (Phase 2) extracts those into {@code workflow_templates}
 * too, duplicating the content. See "Post-G consolidation review" in {@code
 * specifications/merge-execution-plan.md}.
 *
 * <p>Gate reads the durable marker ({@link LegacyGenerationMarker#read}), not a live {@link
 * InstallGeneration#detect} — consistent with every other generation-gated unit in this chain now
 * that {@code _0001__BaselineAndGenerationDetect} always runs first and records it once.
 */
@Change(id = "0023-seed-templates", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0023__SeedTemplates {

  private static final Logger LOG = LoggerFactory.getLogger(_0023__SeedTemplates.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) == InstallGeneration.V3) {
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

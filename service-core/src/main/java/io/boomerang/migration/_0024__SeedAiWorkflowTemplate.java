package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the {@code summarise-a-web-page-with-ai} workflow template to a database that was seeded
 * before it joined {@code seed/workflow-templates.json}. An empty database receives it from {@link
 * _0020__SeedTemplates}, which seeds the whole file, and this unit then finds it present and inserts
 * nothing.
 *
 * <p>Runs after {@link _0023__SeedAiTaskFamily}, so the {@code ai-prompt} task the template names is
 * always in the catalogue. Idempotent on {@code name} + {@code version}, the guard the template seed
 * uses, so an install that already holds a template of that name keeps its own.
 */
@Change(id = "0024-seed-ai-workflow-template", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0024__SeedAiWorkflowTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(_0024__SeedAiWorkflowTemplate.class);

  static final String TEMPLATE = "summarise-a-web-page-with-ai";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    for (Document template : SeedResources.load("seed/workflow-templates.json")) {
      if (!TEMPLATE.equals(template.getString("name"))) {
        continue;
      }
      _0020__SeedTemplates.resolveTaskRefs(db, names, template);
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("workflow_templates"),
          Filters.and(
              Filters.eq("name", TEMPLATE), Filters.eq("version", template.getInteger("version"))),
          template)) {
        LOG.info("Inserted the {} workflow template", TEMPLATE);
      }
    }
  }

  @Rollback
  public void rollback() {
    // Workflows may already have been created from the template - forward-only, like every other
    // seed unit in this chain.
  }
}

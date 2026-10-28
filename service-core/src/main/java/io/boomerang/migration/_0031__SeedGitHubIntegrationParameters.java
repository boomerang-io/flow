package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Lets the GitHub integration share its credentials with tasks as workspace parameters. The GitHub
 * integration template gains the {@code fields} it writes into a workspace ({@code github-token},
 * {@code github-url}) where it has none, and each GitHub task gains the revision whose {@code
 * token} and {@code url} default to those parameters. Each task's newest revision in {@code
 * seed/task-revisions.json} is inserted where the install holds the task but not that revision;
 * earlier revisions, which workflows may pin, are never touched, and a task the install does not
 * hold gets nothing.
 *
 * <p>An empty database receives the template from {@link _0020__SeedTemplates} and the revisions
 * from {@link _0017__SeedTaskCatalogue}, which seed the whole files, and this unit then finds
 * nothing to add.
 */
@Change(id = "0031-seed-github-integration-parameters", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0031__SeedGitHubIntegrationParameters {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0031__SeedGitHubIntegrationParameters.class);

  static final String TEMPLATE_TYPE = "github_app";

  static final Set<String> TASKS =
      Set.of(
          "add-issue-to-project",
          "create-team-in-organization",
          "delete-team-from-organization",
          "find-issues-and-label",
          "find-issues-and-remove",
          "find-repositories-in-org",
          "get-all-organizations",
          "get-all-teams-in-organization",
          "get-github-repository",
          "get-organization-info",
          "get-team-in-organization",
          "invite-collaborator-to-project",
          "invite-member-to-team",
          "invite-user-to-organization",
          "make-repositories-private",
          "remove-collaborator-from-project",
          "remove-member-from-team");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    seedTemplateFields(db, names);
    seedTaskRevisions(db, names);
  }

  private void seedTemplateFields(MongoDatabase db, CollectionNames names) {
    Object fields =
        SeedResources.load("seed/integration-templates.json").stream()
            .filter(template -> TEMPLATE_TYPE.equals(template.getString("type")))
            .findFirst()
            .orElseThrow()
            .get("fields");
    long updated =
        db.getCollection(names.resolve("integration_templates"))
            .updateMany(
                Filters.and(Filters.eq("type", TEMPLATE_TYPE), Filters.exists("fields", false)),
                Updates.set("fields", fields))
            .getModifiedCount();
    LOG.info("Added the parameter fields to {} GitHub integration template(s)", updated);
  }

  private void seedTaskRevisions(MongoDatabase db, CollectionNames names) {
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : TASKS) {
      Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
      if (task == null) {
        LOG.info("No {} task on this install; its integration revision is not added", name);
        continue;
      }
      Document revision =
          revisions.stream()
              .filter(candidate -> name.equals(candidate.getString("parentRef")))
              .max(Comparator.comparingInt(candidate -> candidate.getInteger("version", 0)))
              .orElseThrow();
      String taskId = task.get("_id").toString();
      int version = revision.getInteger("version");
      // In the seed, parentRef names the task; stored, it is the task's id.
      revision.put("parentRef", taskId);
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("task_revisions"),
          Filters.and(Filters.eq("parentRef", taskId), Filters.eq("version", version)),
          revision)) {
        LOG.info("Inserted the {} task revision {}", name, version);
      }
    }
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already pin the new revisions, and workspaces may hold the
    // managed parameters - forward-only, like every other catalogue unit in this chain.
  }
}

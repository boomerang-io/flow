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
 * Makes Slack a workspace integration that shares its bot token with tasks. The Slack integration
 * template, seeded inactive and typed {@code slack} while its installations are stored as {@code
 * slack_auth}, becomes the active {@code slack_auth} template of {@code seed/integration-templates.json},
 * with its install link, instructions and the {@code slack-token} field it writes into a workspace.
 * Each Slack task that takes a bot token gains the revision whose {@code token} defaults to that
 * parameter: each of the task's revisions in {@code seed/task-revisions.json} is inserted where the
 * install holds the task but not that revision, as {@link _0030__SeedSlackCredentialRevisions}
 * does; revisions the install holds, which workflows may pin, are never touched, and a task the
 * install does not hold gets nothing.
 *
 * <p>An empty database receives the template from {@link _0020__SeedTemplates} and the revisions
 * from {@link _0017__SeedTaskCatalogue}, which seed the whole files, and this unit then finds
 * nothing to change.
 */
@Change(id = "0032-seed-slack-integration-parameters", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0032__SeedSlackIntegrationParameters {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0032__SeedSlackIntegrationParameters.class);

  static final String SEEDED_TYPE = "slack";
  static final String TEMPLATE_TYPE = "slack_auth";

  static final Set<String> TASKS =
      Set.of(
          "create-slack-channel",
          "delete-slack-channel",
          "find-slack-member-by-email",
          "find-slack-member-by-id",
          "get-slack-channel-info",
          "get-slack-channel-members",
          "get-slack-channels",
          "invite-members-to-channel",
          "remove-member-from-channel",
          "upload-slack-file-with-message");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    seedTemplate(db, names);
    seedTaskRevisions(db, names);
  }

  private void seedTemplate(MongoDatabase db, CollectionNames names) {
    Document template =
        SeedResources.load("seed/integration-templates.json").stream()
            .filter(candidate -> TEMPLATE_TYPE.equals(candidate.getString("type")))
            .findFirst()
            .orElseThrow();
    long updated =
        db.getCollection(names.resolve("integration_templates"))
            .updateMany(
                Filters.eq("type", SEEDED_TYPE),
                Updates.combine(
                    Updates.set("type", TEMPLATE_TYPE),
                    Updates.set("status", template.getString("status")),
                    Updates.set("description", template.getString("description")),
                    Updates.set("instructions", template.getString("instructions")),
                    Updates.set("link", template.getString("link")),
                    Updates.set("fields", template.get("fields"))))
            .getModifiedCount();
    LOG.info("Made {} Slack integration template(s) active with their parameter fields", updated);
  }

  private void seedTaskRevisions(MongoDatabase db, CollectionNames names) {
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : TASKS) {
      Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
      if (task == null) {
        LOG.info("No {} task on this install; its integration revision is not added", name);
        continue;
      }
      String taskId = task.get("_id").toString();
      revisions.stream()
          .filter(candidate -> name.equals(candidate.getString("parentRef")))
          .sorted(Comparator.comparingInt(candidate -> candidate.getInteger("version", 0)))
          .forEach(
              revision -> {
                int version = revision.getInteger("version");
                // In the seed, parentRef names the task; stored, it is the task's id.
                Document stored = new Document(revision).append("parentRef", taskId);
                if (SeedResources.insertIfAbsent(
                    db,
                    names.resolve("task_revisions"),
                    Filters.and(Filters.eq("parentRef", taskId), Filters.eq("version", version)),
                    stored)) {
                  LOG.info("Inserted the {} task revision {}", name, version);
                }
              });
    }
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already pin the new revisions, and workspaces may hold the
    // managed parameter - forward-only, like every other catalogue unit in this chain.
  }
}

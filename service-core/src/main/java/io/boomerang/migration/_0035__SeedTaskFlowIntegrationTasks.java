package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
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
 * Adds the catalogue tasks and revisions of {@code boomerangio/task-flow:3.3.0}, copied from the
 * tasks repository's {@code tasks/flow/catalog} definition files: 16 new GitHub, Microsoft Teams,
 * SharePoint and Google Drive tasks, and new revisions of the Google Sheets, Google Drive and
 * Artifactory tasks. A new task the install lacks gets its head, graph node and root edge as {@link
 * _0023__SeedAiTaskFamily} adds them; every seed revision of these tasks the install lacks is
 * inserted, as {@link _0033__SeedSendGridTemplateRevision} does. Revisions the install holds, which
 * workflows may pin, are never touched, and a seeded task the install no longer holds gets nothing.
 * The GitHub integration template's instructions gain the App permissions the GitHub tasks need.
 *
 * <p>An empty database receives the tasks and revisions from {@link _0017__SeedTaskCatalogue} and
 * the template from {@link _0020__SeedTemplates}, which seed the whole files, and this unit then
 * finds nothing to insert.
 */
@Change(id = "0035-seed-task-flow-integration-tasks", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0035__SeedTaskFlowIntegrationTasks {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0035__SeedTaskFlowIntegrationTasks.class);

  static final Set<String> NEW_TASKS =
      Set.of(
          "create-github-issue",
          "comment-on-github-issue",
          "create-github-pull-request",
          "set-github-commit-status",
          "dispatch-github-workflow",
          "create-github-release",
          "get-github-file-contents",
          "send-teams-message",
          "create-teams-channel",
          "add-teams-member",
          "upload-sharepoint-file",
          "download-sharepoint-file",
          "list-sharepoint-files",
          "upload-google-drive-file",
          "download-google-drive-file",
          "share-google-drive-file");

  static final Set<String> REVISED_TASKS =
      Set.of(
          "create-spreadsheet",
          "add-sheet",
          "delete-sheet",
          "copy-sheet-to-another-spreadsheet",
          "get-data",
          "clear-data",
          "append-data",
          "update-data",
          "copy-file",
          "list-files",
          "list-file-permissions",
          "delete-file",
          "artifactory-file-download",
          "artifactory-file-upload");

  static final String GITHUB_TEMPLATE_TYPE = "github_app";
  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> liveTasks = db.getCollection(names.resolve("tasks"));
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (Document task : SeedResources.load("seed/tasks.json")) {
      String name = task.getString("name");
      boolean added = NEW_TASKS.contains(name);
      if (!added && !REVISED_TASKS.contains(name)) {
        continue;
      }
      Document existing = liveTasks.find(Filters.eq("name", name)).first();
      if (existing == null && !added) {
        LOG.info("No {} task on this install; its new revision is not added", name);
        continue;
      }
      if (existing == null) {
        liveTasks.insertOne(task);
        existing = task;
        LOG.info("Inserted the {} catalogue task", name);
      }
      String taskId = existing.get("_id").toString();
      insertRevisions(db, names, revisions, name, taskId);
      if (added) {
        linkToRoot(db, names, taskId, name);
      }
    }
    long updated =
        db.getCollection(names.resolve("integration_templates"))
            .updateMany(
                Filters.eq("type", GITHUB_TEMPLATE_TYPE),
                Updates.set("instructions", githubInstructions()))
            .getModifiedCount();
    LOG.info("Gave {} GitHub integration template(s) the GitHub tasks' App permissions", updated);
  }

  private static void insertRevisions(
      MongoDatabase db, CollectionNames names, List<Document> revisions, String name, String taskId) {
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

  // The catalogue is reached from the root node, as the catalogue seed links every task.
  private static void linkToRoot(MongoDatabase db, CollectionNames names, String taskId, String name) {
    String nodeId = "task:" + taskId;
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_nodes"),
        Filters.eq("_id", nodeId),
        SeedResources.node("task", taskId, name));
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_edges"),
        Filters.and(
            Filters.eq("from", ROOT_NODE_ID),
            Filters.eq("label", "hasTask"),
            Filters.eq("to", nodeId)),
        SeedResources.edge(ROOT_NODE_ID, "hasTask", nodeId, new Document()));
  }

  private static String githubInstructions() {
    return SeedResources.load("seed/integration-templates.json").stream()
        .filter(template -> GITHUB_TEMPLATE_TYPE.equals(template.getString("type")))
        .findFirst()
        .orElseThrow()
        .getString("instructions");
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already reference the tasks and pin the new revisions -
    // forward-only, like every other catalogue unit in this chain.
  }
}

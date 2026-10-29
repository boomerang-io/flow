package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
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
 * Adds the revision of each Google Sheets and Drive task whose {@code serviceAccountKey} defaults
 * to {@code $(workspace.params.google-service-account-key)}, the parameter the Google integration
 * writes into a workspace. Each of the task's revisions in {@code seed/task-revisions.json} is
 * inserted where the install holds the task but not that revision, as {@link
 * _0035__SeedTaskFlowIntegrationTasks} does; revisions the install holds, which workflows may pin,
 * are never touched, and a task the install does not hold gets nothing.
 *
 * <p>An empty database receives the revisions from {@link _0017__SeedTaskCatalogue}, which seeds
 * the whole file, and this unit then finds nothing to insert.
 */
@Change(id = "0037-seed-google-key-default-revisions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0037__SeedGoogleKeyDefaultRevisions {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0037__SeedGoogleKeyDefaultRevisions.class);

  static final Set<String> TASKS =
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
          "delete-file");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : TASKS) {
      Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
      if (task == null) {
        LOG.info("No {} task on this install; its revision is not added", name);
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
    // Workflows on a live install may already pin the new revisions - forward-only, like every
    // other catalogue unit in this chain.
  }
}

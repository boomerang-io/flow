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
 * Adds the catalogue revisions of {@code boomerangio/task-flow:3.4.0}, copied from the tasks
 * repository's {@code tasks/flow/catalog} definition files: HTTP Call (basic revision 2, advanced
 * revision 5), the three Slack webhook tasks (revision 3), JSONPath to Parameter (revision 4),
 * File JSONPath to Parameter (revision 3) and Execute Shell (revision 3). They declare the flags
 * their commands always needed as required, and Execute Shell gains {@code failOnError}. Every
 * seed revision of these tasks the install lacks is inserted, as {@link
 * _0035__SeedTaskFlowIntegrationTasks} does; revisions the install holds, which workflows may pin,
 * are never touched, and an install without a task gets nothing for it. The revisions carry no
 * {@code spec.image}: they run on the task settings' {@code default.image}.
 *
 * <p>An empty database receives the revisions from {@link _0017__SeedTaskCatalogue}, which seeds
 * the whole file, and this unit then finds nothing to insert.
 */
@Change(id = "0049-seed-task-flow-3-4-revisions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0049__SeedTaskFlow34Revisions {

  private static final Logger LOG = LoggerFactory.getLogger(_0049__SeedTaskFlow34Revisions.class);

  static final Set<String> REVISED_TASKS =
      Set.of(
          "execute-basic-http-call",
          "execute-advanced-http-call",
          "send-simple-slack-message",
          "send-slack-message-with-file-contents",
          "send-custom-slack-message",
          "jsonpath-to-parameter",
          "file-jsonpath-to-parameter",
          "execute-shell");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : REVISED_TASKS) {
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

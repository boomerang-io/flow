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
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the revision of the SendGrid template task whose {@code apiKey} has no placeholder default
 * and whose {@code templateId} is a plain text field: revision 2 defaulted both to a placeholder,
 * and typed the template ID, which is no secret, as a password. Each of the task's revisions in
 * {@code seed/task-revisions.json} is inserted where the install holds the task but not that
 * revision, as {@link _0032__SeedSlackIntegrationParameters} does; revisions the install holds,
 * which workflows may pin, are never touched, and an install without the task gets nothing.
 *
 * <p>An empty database receives the revision from {@link _0017__SeedTaskCatalogue}, which seeds
 * the whole file, and this unit then finds nothing to insert.
 */
@Change(id = "0033-seed-sendgrid-template-revision", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0033__SeedSendGridTemplateRevision {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0033__SeedSendGridTemplateRevision.class);

  static final String TASK = "send-email-with-sendgrid-template";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", TASK)).first();
    if (task == null) {
      LOG.info("No {} task on this install; its revision is not added", TASK);
      return;
    }
    String taskId = task.get("_id").toString();
    SeedResources.load("seed/task-revisions.json").stream()
        .filter(candidate -> TASK.equals(candidate.getString("parentRef")))
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
                LOG.info("Inserted the {} task revision {}", TASK, version);
              }
            });
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already pin the new revision - forward-only, like every
    // other catalogue unit in this chain.
  }
}

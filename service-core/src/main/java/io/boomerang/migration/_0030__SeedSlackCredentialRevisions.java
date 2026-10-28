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
 * Adds the revision of each Slack task whose bot token or webhook URL is a password field, so the
 * credential is stored encrypted and read back masked. Each task's newest revision in {@code
 * seed/task-revisions.json} is inserted where the install holds the task but not that revision;
 * earlier revisions, which workflows may pin, are never touched, and a task the install does not
 * hold gets nothing.
 *
 * <p>An empty database receives these revisions from {@link _0017__SeedTaskCatalogue}, which seeds
 * every revision in the file, and this unit then finds nothing to add.
 */
@Change(id = "0030-seed-slack-credential-revisions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0030__SeedSlackCredentialRevisions {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0030__SeedSlackCredentialRevisions.class);

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
          "upload-slack-file-with-message",
          "send-custom-slack-message",
          "send-simple-slack-message",
          "send-slack-message-with-file-contents");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : TASKS) {
      Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
      if (task == null) {
        LOG.info("No {} task on this install; its credential revision is not added", name);
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
    // Workflows on a live install may already pin the new revisions - forward-only, like every
    // other catalogue unit in this chain.
  }
}

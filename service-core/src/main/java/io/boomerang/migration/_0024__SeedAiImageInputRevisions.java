package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds revision 2 of {@code ai-prompt} and {@code ai-agent} (image input) to a database that
 * already holds the tasks, inserting it only where absent and never touching revision 1 or a task
 * the install does not hold.
 *
 * <p>An empty database receives revision 2 from {@link _0017__SeedTaskCatalogue}, which seeds every
 * revision in {@code seed/task-revisions.json}, and a database seeded before the tasks existed
 * receives it from {@link _0023__SeedAiTaskFamily}; both then leave nothing for this unit. A
 * revision is matched by {@code parentRef} + {@code version}, as the catalogue seed does, so
 * workflows pinned to revision 1 keep running it.
 */
@Change(id = "0024-seed-ai-image-input-revisions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0024__SeedAiImageInputRevisions {

  private static final Logger LOG = LoggerFactory.getLogger(_0024__SeedAiImageInputRevisions.class);

  static final int VERSION = 2;

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (String name : _0023__SeedAiTaskFamily.TASKS) {
      Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
      if (task == null) {
        LOG.info("No {} task on this install; its revision {} is not added", name, VERSION);
        continue;
      }
      String taskId = task.get("_id").toString();
      for (Document revision : revisions) {
        if (!name.equals(revision.getString("parentRef"))
            || revision.getInteger("version", 0) != VERSION) {
          continue;
        }
        // In the seed, parentRef names the task; stored, it is the task's id.
        revision.put("parentRef", taskId);
        if (SeedResources.insertIfAbsent(
            db,
            names.resolve("task_revisions"),
            Filters.and(Filters.eq("parentRef", taskId), Filters.eq("version", VERSION)),
            revision)) {
          LOG.info("Inserted the {} task revision {}", name, VERSION);
        }
      }
    }
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already pin revision 2 - forward-only, like every other
    // catalogue unit in this chain.
  }
}

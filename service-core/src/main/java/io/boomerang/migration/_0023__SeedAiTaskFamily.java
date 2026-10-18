package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the {@code ai-prompt} and {@code ai-agent} catalogue tasks to a database that was seeded
 * before they joined {@code seed/tasks.json} and {@code seed/task-revisions.json}. An empty
 * database receives them from {@link _0017__SeedTaskCatalogue}, which seeds the whole file, and
 * this unit then finds them present and inserts nothing.
 *
 * <p>The two entries are copied from the tasks repository's {@code tasks/ai/catalog} definition
 * files, which are the source of their param and result contract. Idempotent: a task is matched by
 * {@code name}, its revision by {@code parentRef} + {@code version}, and its graph node and root
 * edge by id, exactly as the catalogue seed does. The earlier seeded {@code ai} task no longer
 * ships, but an install that holds it keeps it, its revisions, node and edges untouched, because
 * its workflows may reference it and what the catalogue ships says nothing about stored definitions.
 */
@Change(id = "0023-seed-ai-task-family", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0023__SeedAiTaskFamily {

  private static final Logger LOG = LoggerFactory.getLogger(_0023__SeedAiTaskFamily.class);

  static final Set<String> TASKS = Set.of("ai-prompt", "ai-agent");
  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> liveTasks = db.getCollection(names.resolve("tasks"));
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (Document task : SeedResources.load("seed/tasks.json")) {
      String name = task.getString("name");
      if (!TASKS.contains(name)) {
        continue;
      }
      Document existing = liveTasks.find(Filters.eq("name", name)).first();
      if (existing == null) {
        liveTasks.insertOne(task);
        existing = task;
        LOG.info("Inserted the {} catalogue task", name);
      }
      String taskId = existing.get("_id").toString();
      for (Document revision : revisions) {
        if (!name.equals(revision.getString("parentRef"))) {
          continue;
        }
        // In the seed, parentRef names the task; stored, it is the task's id.
        revision.put("parentRef", taskId);
        if (SeedResources.insertIfAbsent(
            db,
            names.resolve("task_revisions"),
            Filters.and(
                Filters.eq("parentRef", taskId),
                Filters.eq("version", revision.getInteger("version"))),
            revision)) {
          LOG.info("Inserted the {} task revision {}", name, revision.getInteger("version"));
        }
      }
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
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already reference the tasks by name - forward-only, like
    // every other catalogue unit in this chain.
  }
}

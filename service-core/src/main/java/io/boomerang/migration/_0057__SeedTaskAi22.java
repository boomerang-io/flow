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
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the catalogue entries of task-ai 2.2.0, copied from the tasks repository's {@code
 * tasks/ai/catalog} definition files: {@code ai-agent} revision 3 ({@code
 * boomerangio/task-ai:2.2.0}, with the workspace tools, a working directory, a command time limit
 * and a time limit for the loop) and the new {@code ai-coding-agent} task, revision 1 ({@code
 * boomerangio/task-ai-coding:2.2.0}, the same params on an image with build toolchains).
 *
 * <p>The {@code ai-coding-agent} task is inserted with its graph node and root edge where the
 * install lacks it, as {@link _0023__SeedAiTaskFamily} does. {@code ai-agent} revision 3 is
 * inserted where the install holds the task but not that revision, as {@link
 * _0024__SeedAiImageInputRevisions} does; an install without {@code ai-agent} gets nothing for it,
 * and revisions it holds, which workflows may pin, are never touched. A task stores no latest
 * version: the newest revision is the latest. Idempotent: a task is matched by {@code name}, a
 * revision by {@code parentRef} + {@code version}, a node and edge by id.
 *
 * <p>An empty database receives both from {@link _0017__SeedTaskCatalogue}, which seeds the whole
 * file, and this unit then finds nothing to insert.
 */
@Change(id = "0057-seed-task-ai-2-2", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0057__SeedTaskAi22 {

  private static final Logger LOG = LoggerFactory.getLogger(_0057__SeedTaskAi22.class);

  /** The task this unit inserts where it is absent. */
  static final String NEW_TASK = "ai-coding-agent";

  /** The revision of each task this unit adds. */
  static final Map<String, Integer> REVISIONS = Map.of("ai-agent", 3, NEW_TASK, 1);

  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> liveTasks = db.getCollection(names.resolve("tasks"));
    insertNewTask(db, names, liveTasks);
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    REVISIONS.forEach(
        (name, version) -> {
          Document task = liveTasks.find(Filters.eq("name", name)).first();
          if (task == null) {
            LOG.info("No {} task on this install; its revision {} is not added", name, version);
            return;
          }
          String taskId = task.get("_id").toString();
          Document revision =
              revisions.stream()
                  .filter(candidate -> name.equals(candidate.getString("parentRef")))
                  .filter(candidate -> version.equals(candidate.getInteger("version")))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "The seed has no " + name + " revision " + version));
          // In the seed, parentRef names the task; stored, it is the task's id.
          revision.put("parentRef", taskId);
          if (SeedResources.insertIfAbsent(
              db,
              names.resolve("task_revisions"),
              Filters.and(Filters.eq("parentRef", taskId), Filters.eq("version", version)),
              revision)) {
            LOG.info("Inserted the {} task revision {}", name, version);
          }
        });
  }

  private static void insertNewTask(
      MongoDatabase db, CollectionNames names, MongoCollection<Document> liveTasks) {
    Document existing = liveTasks.find(Filters.eq("name", NEW_TASK)).first();
    if (existing == null) {
      existing =
          SeedResources.load("seed/tasks.json").stream()
              .filter(task -> NEW_TASK.equals(task.getString("name")))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("The seed has no " + NEW_TASK + " task"));
      liveTasks.insertOne(existing);
      LOG.info("Inserted the {} catalogue task", NEW_TASK);
    }
    String taskId = existing.get("_id").toString();
    String nodeId = "task:" + taskId;
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_nodes"),
        Filters.eq("_id", nodeId),
        SeedResources.node("task", taskId, NEW_TASK));
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_edges"),
        Filters.and(
            Filters.eq("from", ROOT_NODE_ID),
            Filters.eq("label", "hasTask"),
            Filters.eq("to", nodeId)),
        SeedResources.edge(ROOT_NODE_ID, "hasTask", nodeId, new Document()));
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already reference the new task or pin the new revision -
    // forward-only, like every other catalogue unit in this chain.
  }
}

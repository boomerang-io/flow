package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Put the {@code ai} catalogue task on an existing install.
 *
 * <p>A fresh install gets it from {@code seed/tasks.json} and {@code seed/task-revisions.json} via
 * {@code _0022__SeedTaskCatalogue}, which reconciles by name and is therefore already correct for
 * a new entry — but Flamingock records {@code _0022} as applied, so it never runs again and an
 * upgraded install would never see the new task. This unit reads the same two seed documents (one
 * definition, not a second copy) and inserts whatever is absent: the task, its version 1 revision,
 * and the {@code root:root --hasTask--> task:<id>} edge that makes a global catalogue task
 * reachable. On a fresh install everything is already present and this finds nothing to do.
 *
 * <p>Ungated: the {@code ai} type is v5-native, so there is no generation for which it is wrong.
 */
@Change(id = "0047-seed-ai-task", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0047__SeedAiTask {

  private static final Logger LOG = LoggerFactory.getLogger(_0047__SeedAiTask.class);

  private static final String TASK_NAME = "ai";
  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    Document seededTask =
        SeedResources.load("seed/tasks.json").stream()
            .filter(task -> TASK_NAME.equals(task.getString("name")))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("seed/tasks.json no longer carries the ai task"));
    String seededId = seededTask.get("_id").toString();

    // Match by name, the same natural key _0022 uses, so an install that already has an ai task
    // (this unit's own earlier run, or a fresh install's seed) keeps its own id.
    Document existing =
        db.getCollection(names.resolve("tasks")).find(Filters.eq("name", TASK_NAME)).first();
    String taskId = seededId;
    if (existing != null) {
      taskId = existing.get("_id").toString();
    } else {
      db.getCollection(names.resolve("tasks")).insertOne(seededTask);
      LOG.info("Inserted the ai catalogue task as {}", taskId);
    }

    seedRevision(db, names, seededId, taskId);
    seedGraph(db, names, taskId, seededTask.getString("name"));
  }

  /** Insert the version 1 revision, re-pointed at whichever task id actually won. */
  private void seedRevision(
      MongoDatabase db, CollectionNames names, String seededId, String taskId) {
    Document revision =
        SeedResources.load("seed/task-revisions.json").stream()
            .filter(r -> seededId.equals(r.getString("parentRef")))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "seed/task-revisions.json no longer carries the ai task revision"));
    revision.put("parentRef", taskId);
    if (SeedResources.insertIfAbsent(
        db,
        names.resolve("task_revisions"),
        Filters.and(
            Filters.eq("parentRef", taskId), Filters.eq("version", revision.getInteger("version"))),
        revision)) {
      LOG.info("Inserted the ai task revision {} for task {}", revision.get("_id"), taskId);
    }
  }

  /** The {@code task:<id>} node and its root edge — how RelationshipService reaches the task. */
  private void seedGraph(MongoDatabase db, CollectionNames names, String taskId, String name) {
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

  @Rollback
  public void rollback() {
    // Workflows on a live install may already reference the task by name - forward-only, like
    // every other catalogue unit in this chain.
  }
}

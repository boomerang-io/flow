package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed the out-of-the-box task catalogue — 87 tasks and their 130 revisions, the set the legacy
 * loader ends up with after its full {@code flow_task_templates} chain. Without it a fresh install
 * has an empty task palette and no workflow can be composed.
 *
 * <p>v5 splits what v4 called a task template across two collections, and the legacy loader's own
 * v4 changesets already produced that split: {@code tasks} holds the stable identity ({@code
 * name}, {@code type}, {@code status}, {@code verified}, {@code labels}, {@code annotations}) and
 * {@code task_revisions} holds every versioned field keyed by {@code parentRef} + {@code version}
 * ({@code displayName}, {@code description}, {@code category}, {@code icon}, {@code changelog},
 * {@code spec}). Legacy changeset 4043 had already folded the v4 {@code config[]} array into
 * {@code spec.params[]}, which is exactly {@code TaskRevisionEntity.spec.params} — so no v4-shape
 * translation is left to do here, and the seeded revisions carry the merged UI metadata ({@code
 * label}, {@code type}, {@code placeholder}, {@code options}) on each param.
 *
 * <p>All four revision generations are kept rather than collapsed to the latest, so {@code
 * TaskRevisionRepository.findByParentRefAndLatestVersion} resolves the same version number a
 * migrated v4 install resolves.
 *
 * <p><b>Graph shape — a deliberate divergence from the legacy end state.</b> The legacy loader
 * leaves these tasks as orphaned {@code teamtask:<ref>} nodes: changeset 4031 created them as
 * {@code task} nodes with an empty {@code connections} list, and 4041 then re-typed them to {@code
 * teamtask} and, finding no connections, wrote no edges at all — so nothing in the graph reaches
 * them. v5's model for the out-of-the-box catalogue is unambiguous: {@code
 * WorkspaceTaskService.create(Task)} writes a global task as {@code root:root --hasTask-->
 * task:<id>}, and {@code RelationshipService.filter} anchors the {@code TASK} walk at the root
 * node precisely because "tasks are a global catalogue: every principal sees every task". This
 * seeds that shape. Existing {@code teamtask} nodes on an upgraded install are left alone — the
 * new nodes and edges are purely additive.
 *
 * <p>Idempotent: tasks are matched by {@code name} and revisions by {@code parentRef} + {@code
 * version}, and the revisions are re-pointed at whichever task id actually won (an upgraded
 * install's own, or the seeded one), so a re-run inserts nothing.
 */
@Change(id = "0017-seed-task-catalogue", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0017__SeedTaskCatalogue {

  private static final Logger LOG = LoggerFactory.getLogger(_0017__SeedTaskCatalogue.class);

  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    Map<String, String> resolvedIds = seedTasks(db, names);
    seedRevisions(db, names, resolvedIds);
    seedGraph(db, names, resolvedIds);
  }

  /**
   * Insert every absent task and return seeded-id -> live-id, so the revisions and graph attach to
   * an upgraded install's own task documents rather than duplicating them.
   */
  private Map<String, String> seedTasks(MongoDatabase db, CollectionNames names) {
    List<Document> tasks = SeedResources.load("seed/tasks.json");
    Map<String, String> resolvedIds = new HashMap<>();
    int inserted = 0;
    for (Document task : tasks) {
      String seededId = task.get("_id").toString();
      Document existing =
          db.getCollection(names.resolve("tasks"))
              .find(Filters.eq("name", task.getString("name")))
              .first();
      if (existing != null) {
        resolvedIds.put(seededId, existing.get("_id").toString());
        continue;
      }
      db.getCollection(names.resolve("tasks")).insertOne(task);
      resolvedIds.put(seededId, seededId);
      inserted++;
    }
    SeedResources.logSeeded("tasks", inserted, tasks.size());
    return resolvedIds;
  }

  private void seedRevisions(
      MongoDatabase db, CollectionNames names, Map<String, String> resolvedIds) {
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    int inserted = 0;
    for (Document revision : revisions) {
      String parentRef = resolvedIds.get(revision.getString("parentRef"));
      if (parentRef == null) {
        LOG.warn("Skipping task revision with unresolved parent {}", revision.getString("parentRef"));
        continue;
      }
      revision.put("parentRef", parentRef);
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("task_revisions"),
          Filters.and(
              Filters.eq("parentRef", parentRef),
              Filters.eq("version", revision.getInteger("version"))),
          revision)) {
        inserted++;
      }
    }
    SeedResources.logSeeded("task revisions", inserted, revisions.size());
  }

  /** One {@code task:<id>} node per task, each reachable from the root by a {@code hasTask} edge. */
  private void seedGraph(MongoDatabase db, CollectionNames names, Map<String, String> resolvedIds) {
    int nodes = 0;
    int edges = 0;
    for (Document task : SeedResources.load("seed/tasks.json")) {
      String taskId = resolvedIds.get(task.get("_id").toString());
      if (taskId == null) {
        continue;
      }
      String nodeId = "task:" + taskId;
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_nodes"),
          Filters.eq("_id", nodeId),
          SeedResources.node("task", taskId, task.getString("name")))) {
        nodes++;
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(
              Filters.eq("from", ROOT_NODE_ID),
              Filters.eq("label", "hasTask"),
              Filters.eq("to", nodeId)),
          SeedResources.edge(ROOT_NODE_ID, "hasTask", nodeId, new Document()))) {
        edges++;
      }
    }
    LOG.info("Task catalogue graph — {} nodes inserted, {} root edges inserted", nodes, edges);
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install reference these tasks by name - forward-only.
  }
}

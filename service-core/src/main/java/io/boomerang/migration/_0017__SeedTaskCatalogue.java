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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Seed the out-of-the-box task catalogue — 90 tasks and their 133 revisions: the 87 tasks / 130
 * revisions the legacy loader ends up with after its full {@code flow_task_templates} chain, plus
 * the v5-native {@code ai}, {@code ai-prompt} and {@code ai-agent} tasks, which have no legacy
 * counterpart. Without it an empty database has an empty task palette and no workflow can be
 * composed.
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
 * TaskRevisionRepository.findByParentRefAndLatestVersion} resolves the same version number the
 * legacy loader's catalogue resolves.
 *
 * <p><b>Graph shape — a deliberate divergence from the legacy end state.</b> The legacy loader
 * leaves these tasks as orphaned {@code teamtask:<ref>} nodes: changeset 4031 created them as
 * {@code task} nodes with an empty {@code connections} list, and 4041 then re-typed them to {@code
 * teamtask} and, finding no connections, wrote no edges at all — so nothing in the graph reaches
 * them. v5's model for the out-of-the-box catalogue is unambiguous: {@code
 * WorkspaceTaskService.create(Task)} writes a global task as {@code root:root --hasTask-->
 * task:<id>}, and {@code RelationshipService.filter} anchors the {@code TASK} walk at the root
 * node precisely because "tasks are a global catalogue: every principal sees every task". This
 * seeds that shape; the nodes and edges are inserted only where absent.
 *
 * <p>Idempotent: tasks are matched by {@code name} and revisions by {@code parentRef} + {@code
 * version}, and the revisions are re-pointed at whichever task id actually won (a v3 install's
 * migrated task, or the seeded one), so a re-run inserts nothing.
 *
 * <p><b>Not generation-gated.</b> On a v3 install this runs after {@link
 * _0006__V3MigrateTaskCatalogue}, so {@code tasks} already holds the migrated catalogue under its
 * v3 {@code _id}s. {@link #seedTasks}'s name match finds every one of those tasks (verified 87/87
 * on the real v3 dump) and inserts only what the install genuinely lacks: on that dump, the seed's
 * {@code Manual Approval} v2 revision and the v5-native {@code ai} task, which no v3 install can
 * hold. The seed carries no ids: a task it inserts takes a new one, and its revisions name their
 * task in {@code parentRef}, resolved to the live id here. A v3 catalogue task an install renamed
 * is not recognised, so the seed's own copy is added beside it.
 */
@Change(id = "0017-seed-task-catalogue", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0017__SeedTaskCatalogue {

  private static final Logger LOG = LoggerFactory.getLogger(_0017__SeedTaskCatalogue.class);

  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    Map<String, String> resolvedIds = seedTasks(db, names);
    seedRevisions(db, names, resolvedIds);
    seedGraph(db, names, resolvedIds);
  }

  /**
   * Insert every absent task and return task name -> live id, so the revisions and graph attach to
   * a v3 install's migrated task documents rather than duplicating them.
   */
  private Map<String, String> seedTasks(MongoDatabase db, CollectionNames names) {
    List<Document> tasks = SeedResources.load("seed/tasks.json");
    MongoCollection<Document> liveTasks = db.getCollection(names.resolve("tasks"));
    Map<String, String> resolvedIds = new HashMap<>();
    int inserted = 0;
    for (Document task : tasks) {
      String name = task.getString("name");
      Document existing = liveTasks.find(Filters.eq("name", name)).first();
      if (existing == null) {
        liveTasks.insertOne(task);
        existing = task;
        inserted++;
      }
      resolvedIds.put(name, existing.get("_id").toString());
    }
    SeedResources.logSeeded("tasks", inserted, tasks.size());
    return resolvedIds;
  }

  private void seedRevisions(
      MongoDatabase db, CollectionNames names, Map<String, String> resolvedIds) {
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    int inserted = 0;
    for (Document revision : revisions) {
      // In the seed, parentRef names the task; stored, it is the task's id.
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
      String taskId = resolvedIds.get(task.getString("name"));
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

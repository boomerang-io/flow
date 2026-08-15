package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Reconciles a migrated v3 install's own task catalogue ({@link _0022__V3MigrateTasks}
 * output — every {@code task_templates} document the install actually had) against the 87-task/
 * 130-revision out-of-the-box catalogue {@link _0017__SeedTaskCatalogue} ships on a fresh/v4
 * install — which SKIPS entirely on v3 (see that unit's javadoc), so none of those 87 tasks exist
 * yet purely from seeding.
 *
 * <p>Because {@code _0022} preserves every legacy {@code _id} verbatim, "does this install already
 * have seeded task X" is a direct {@code _id} lookup — no name-based resolution/remapping is needed
 * the way {@code _0017} needs it on a fresh install (where the seeded ids are arbitrary).
 *
 * <p><b>Two real-world gaps this unit closes</b> (found comparing the real v3 dump against the
 * seed):
 *
 * <ul>
 *   <li><b>Missing tasks.</b> An install may simply not have every task in the current catalogue
 *       (a task added to the out-of-the-box set after that install's data was captured, or one an
 *       operator deleted). For each of the 87 seeded tasks absent by {@code _id}, insert the seeded
 *       task and every one of its seeded revisions.
 *   <li><b>Missing revisions on an existing task.</b> Verified on the real dump: {@code Manual
 *       Approval} ({@code _id 5f6379c974f51934044cbbd6}) exists with only its {@code v1} revision —
 *       the seed catalogue carries a {@code v2} that post-dates this install's snapshot. For EVERY
 *       seeded task (not just the ones missing outright), each seed revision is inserted only if
 *       absent by {@code (parentRef, version)} — exactly {@code _0017}'s own idempotency key —
 *       leaving the install's own revisions (including ones the seed doesn't have at all, e.g. a
 *       team-authored {@code v4}) completely untouched.
 * </ul>
 *
 * <p><b>Global-task graph edges</b> ({@code task:<id>} node + {@code root:root --hasTask--> task:
 * <id>} edge, the same shape {@code _0017} seeds) are attached for every seeded task ONLY if {@code
 * rel_nodes}/{@code rel_edges} already exist as collections — they always do in practice ({@link
 * _0013__SeedRelationshipRoot}/{@link _0014__SeedSystemWorkspace} create them unconditionally, long
 * before this unit runs), but the check is defensive: the FULL relationship graph (workspace
 * ownership edges for team-scoped tasks, user/workflow/run nodes, ...) is Batch E's job ({@code
 * _0029__V3BuildRelationshipGraph}), not this one's. If either collection is absent, this unit
 * skips the graph step entirely and logs that Batch E will handle it.
 *
 * <p>Idempotent throughout: every write is insert-if-absent, matching {@link
 * _0017__SeedTaskCatalogue}'s own idempotency keys.
 */
@Change(id = "0034-v3-reconcile-catalogue", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0034__V3ReconcileCatalogue {

  private static final Logger LOG = LoggerFactory.getLogger(_0034__V3ReconcileCatalogue.class);

  private static final String ROOT_NODE_ID = "root:root";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — the task catalogue is seeded directly by _0017, nothing to reconcile.");
      return;
    }

    int tasksInserted = reconcileTasks(db, names);
    int revisionsInserted = reconcileRevisions(db, names);
    reconcileGraph(db, names);

    LOG.info(
        "v3 task catalogue reconciled — {} missing tasks inserted, {} missing revisions inserted",
        tasksInserted,
        revisionsInserted);
  }

  private int reconcileTasks(MongoDatabase db, CollectionNames names) {
    List<Document> seedTasks = SeedResources.load("seed/tasks.json");
    int inserted = 0;
    for (Document task : seedTasks) {
      if (SeedResources.insertIfAbsent(
          db, names.resolve("tasks"), Filters.eq("_id", task.get("_id")), task)) {
        inserted++;
      }
    }
    SeedResources.logSeeded("reconciled tasks", inserted, seedTasks.size());
    return inserted;
  }

  private int reconcileRevisions(MongoDatabase db, CollectionNames names) {
    int inserted = 0;
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    for (Document revision : revisions) {
      String parentRef = revision.getString("parentRef");
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
    SeedResources.logSeeded("reconciled task revisions", inserted, revisions.size());
    return inserted;
  }

  /** One {@code task:<id>} node per seeded task, reachable from the root — mirrors {@code _0017}. */
  private void reconcileGraph(MongoDatabase db, CollectionNames names) {
    List<String> collectionNames = new ArrayList<>();
    db.listCollectionNames().into(collectionNames);
    if (!collectionNames.contains(names.resolve("rel_nodes"))
        || !collectionNames.contains(names.resolve("rel_edges"))) {
      LOG.info(
          "rel_nodes/rel_edges not present yet — skipping global-task graph edges here; Batch E"
              + " (_0029__V3BuildRelationshipGraph) will attach them.");
      return;
    }

    int nodes = 0;
    int edges = 0;
    for (Document task : SeedResources.load("seed/tasks.json")) {
      String taskId = task.get("_id").toString();
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
    LOG.info("v3 task catalogue graph reconciled — {} nodes inserted, {} root edges inserted", nodes, edges);
  }

  @Rollback
  public void rollback() {
    // Additive-only reconciliation against a live catalogue - forward-only, matching the other
    // v3-only online migrations in this chain.
  }
}

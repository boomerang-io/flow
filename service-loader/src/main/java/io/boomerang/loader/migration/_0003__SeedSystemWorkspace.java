package io.boomerang.loader.migration;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed the {@code system} workspace and its graph, mirroring what {@code WorkspaceService.create}
 * writes: the {@code teams} document (the workspace entity's collection kept its pre-DD-01 name),
 * a {@code workspace:<id>} node whose slug is the workspace name, and a {@code root:root
 * --contains--> workspace:<id>} edge.
 *
 * <p>This is the successor to legacy changeset 4015 ({@code v4MigrateSystemToATeam}). Three
 * deliberate differences from the legacy document, all forced by the v5 entity:
 *
 * <ul>
 *   <li>quotas use the v5 {@code Quotas} field names — legacy {@code maxWorkflowExecutionMonthly}/
 *       {@code maxWorkflowExecutionTime}/{@code maxConcurrentWorkflows} became {@code
 *       maxWorkflowRunMonthly}/{@code maxWorkflowRunDuration}/{@code maxConcurrentRuns}, and v5
 *       added {@code maxWorkflowRunStorage}. All stay at {@code Integer.MAX_VALUE} — the system
 *       workspace is unlimited.
 *   <li>{@code type} is set to {@code system}; v4's team document had no type at all, v5's
 *       {@code WorkspaceEntity} carries a {@code WorkspaceType}.
 *   <li>{@code labels}/{@code annotations} are empty maps rather than absent, matching the
 *       entity's field defaults.
 * </ul>
 *
 * <p>The name {@code system} is in {@code WorkspaceService.RESERVED_WORKSPACE_NAMES}, so it can only
 * ever come from here.
 *
 * <p>Also replicates legacy 4015's admin bootstrap: every existing {@code admin} user becomes a
 * member of the system workspace, as a {@code user:<id> --memberOf--> workspace:<id>} edge
 * carrying {@code data.role}, which is the member shape {@code
 * WorkspaceService.createOrUpdateUserRelationships} writes. A fresh install has no users, so this
 * is a no-op there.
 *
 * <p>Idempotent throughout: the workspace is matched by name (an upgraded v4 install keeps its own
 * document and id, and the graph is then built against *that* id), and every node and edge is
 * insert-if-absent.
 *
 * <p><b>Positioned early, right after generation detection, ahead of the whole v3 migration.</b>
 * The other Phase 5 seeds ({@code _0020__SeedRoles}/{@code _0021__SeedSettings}/{@code
 * _0022__SeedTaskCatalogue}/{@code _0023__SeedTemplates}) run AFTER the v3 migration and index
 * phases, but {@code _0012__V3BuildRelationshipGraph} resolves {@code scope=system} workflow/run
 * ownership via {@code teams} where {@code type=system} - it needs THIS unit's {@code teams}
 * document to already exist, or every system-scoped workflow/run silently loses its graph node and
 * edge (found the hard way: {@code V3DumpMigrationTest} went from 65 to 55 {@code workflow} rel_nodes
 * when this seed was still positioned late). Running early means this unit's OWN admin-bootstrap
 * step (see {@link #addAdminMembers}) instead finds NO {@code user:<id>} nodes yet on a v3 install
 * (they do not exist until the relationship graph builds them, later) - {@code
 * _0012__V3BuildRelationshipGraph} re-attempts it once those nodes exist, folding in what was
 * previously the standalone {@code _0030__V3SystemWorkspaceMembers} unit (see that unit's own
 * javadoc). {@code _0002__SeedRelationshipRoot} moves alongside this unit for the same graph-anchor
 * reasoning, though nothing strictly requires it (Mongo does not enforce the edge-to-node reference).
 */
@Change(id = "0003-seed-system-workspace", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0003__SeedSystemWorkspace {

  private static final Logger LOG = LoggerFactory.getLogger(_0003__SeedSystemWorkspace.class);

  private static final String WORKSPACE_NAME = "system";
  private static final String ROOT_NODE_ID = "root:root";
  private static final String ADMIN_ROLE = "owner";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String workspaceId = seedWorkspace(db, names);
    String workspaceNodeId = "workspace:" + workspaceId;

    boolean nodeInserted =
        SeedResources.insertIfAbsent(
            db,
            names.resolve("rel_nodes"),
            Filters.eq("_id", workspaceNodeId),
            SeedResources.node("workspace", workspaceId, WORKSPACE_NAME));
    boolean edgeInserted =
        SeedResources.insertIfAbsent(
            db,
            names.resolve("rel_edges"),
            Filters.and(
                Filters.eq("from", ROOT_NODE_ID),
                Filters.eq("label", "contains"),
                Filters.eq("to", workspaceNodeId)),
            SeedResources.edge(ROOT_NODE_ID, "contains", workspaceNodeId, new Document()));
    LOG.info(
        "System workspace graph — node inserted: {}, root edge inserted: {}",
        nodeInserted,
        edgeInserted);

    addAdminMembers(db, names, workspaceNodeId);
  }

  /**
   * Insert the workspace document if no {@code system} workspace exists, and return the id to
   * build the graph against — the existing document's id on an upgrade, the seeded one otherwise.
   */
  private String seedWorkspace(MongoDatabase db, CollectionNames names) {
    String collection = names.resolve("teams");
    Document existing =
        db.getCollection(collection).find(Filters.eq("name", WORKSPACE_NAME)).first();
    if (existing != null) {
      LOG.info("System workspace already present — building the graph against its id");
      return existing.get("_id").toString();
    }
    Document workspace = SeedResources.load("seed/workspace.json").get(0);
    db.getCollection(collection).insertOne(workspace);
    LOG.info("Seeded system workspace");
    return workspace.get("_id").toString();
  }

  /**
   * Every {@code admin} user joins the system workspace. Users whose relationship node is missing
   * are skipped rather than given a dangling edge — {@code RelationshipService.createEdge} equally
   * requires both endpoints to resolve.
   */
  private void addAdminMembers(MongoDatabase db, CollectionNames names, String workspaceNodeId) {
    List<Document> admins =
        db.getCollection(names.resolve("users"))
            .find(Filters.eq("type", "admin"))
            .into(new java.util.ArrayList<>());
    int added = 0;
    int skipped = 0;
    for (Document admin : admins) {
      String userNodeId = "user:" + admin.get("_id").toString();
      try (MongoCursor<Document> node =
          db.getCollection(names.resolve("rel_nodes"))
              .find(Filters.eq("_id", userNodeId))
              .iterator()) {
        if (!node.hasNext()) {
          skipped++;
          continue;
        }
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(
              Filters.eq("from", userNodeId),
              Filters.eq("label", "memberOf"),
              Filters.eq("to", workspaceNodeId)),
          SeedResources.edge(
              userNodeId, "memberOf", workspaceNodeId, new Document("role", ADMIN_ROLE)))) {
        added++;
      }
    }
    LOG.info(
        "System workspace admin members — {} admins, {} edges added, {} skipped (no user node)",
        admins.size(),
        added,
        skipped);
    if (skipped > 0) {
      LOG.info(
          "{} admin user(s) have no relationship node yet (e.g. a v3 install, whose users predate "
              + "the relationship graph) — their system-workspace membership edges will be "
              + "attached once the v3->v5 migration creates those nodes.",
          skipped);
    }
  }

  @Rollback
  public void rollback() {
    // The system workspace may already own workflows and members by the time a rollback runs -
    // dropping it would take those with it. Matches the other seed units' forward-only scope.
  }
}

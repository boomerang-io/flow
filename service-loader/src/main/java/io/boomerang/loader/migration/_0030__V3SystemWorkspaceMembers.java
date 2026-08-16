package io.boomerang.loader.migration;

import com.mongodb.client.MongoCursor;
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
 * V3-only. Finishes the admin-bootstrap half of {@code _0014__SeedSystemWorkspace} that a v3
 * install could never complete on its own.
 *
 * <p>{@code _0014} runs BEFORE any v3-only unit (order 0014 vs 0019+) — on a v3 install it tries to
 * add every {@code type=admin} user as a {@code system} workspace member, but {@code user:<id>}
 * relationship nodes do not exist yet at that point (v3 has no relationship graph at all; {@link
 * _0029__V3BuildRelationshipGraph} is what creates them), so every admin is skipped and {@code
 * _0014} logs "0 admins ... 0 skipped" — exactly the gap {@code _0014}'s own javadoc anticipates
 * ("their system-workspace membership edges will be attached once the v3->v5 migration creates
 * those nodes"). This unit is that attachment, run after {@link _0029__V3BuildRelationshipGraph}
 * has created the user nodes.
 *
 * <p>Reproduces {@code _0014#addAdminMembers} verbatim (same edge shape - {@code data.role=owner},
 * same skip-if-no-node defensiveness, though by this point every user SHOULD have a node from
 * {@code _0029}) rather than depending on that private method directly, matching this program's
 * established pattern of small, self-contained change units.
 *
 * <p>Idempotent: every edge is insert-if-absent on {@code (from, label, to)} - a second run (or a
 * v4/fresh install where {@code _0014} already added these edges directly) inserts nothing new.
 */
@Change(id = "0030-v3-system-workspace-members", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0030__V3SystemWorkspaceMembers {

  private static final Logger LOG = LoggerFactory.getLogger(_0030__V3SystemWorkspaceMembers.class);

  private static final String WORKSPACE_NAME = "system";
  private static final String ADMIN_ROLE = "owner";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — _0014 already attached admin members directly.");
      return;
    }

    Document system =
        db.getCollection(names.resolve("teams")).find(Filters.eq("name", WORKSPACE_NAME)).first();
    if (system == null) {
      LOG.warn("No 'system' workspace found — _0014 should have seeded one; skipping admin membership");
      return;
    }
    String workspaceNodeId = "workspace:" + system.get("_id").toString();

    List<Document> admins =
        db.getCollection(names.resolve("users")).find(Filters.eq("type", "admin")).into(new ArrayList<>());
    int added = 0;
    int skipped = 0;
    for (Document admin : admins) {
      String userNodeId = "user:" + admin.get("_id").toString();
      try (MongoCursor<Document> node =
          db.getCollection(names.resolve("rel_nodes")).find(Filters.eq("_id", userNodeId)).iterator()) {
        if (!node.hasNext()) {
          skipped++;
          continue;
        }
      }
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(Filters.eq("from", userNodeId), Filters.eq("label", "memberOf"), Filters.eq("to", workspaceNodeId)),
          SeedResources.edge(userNodeId, "memberOf", workspaceNodeId, new Document("role", ADMIN_ROLE)))) {
        added++;
      }
    }
    LOG.info(
        "v3 system workspace admin members — {} admins, {} edges added, {} skipped (no user node)",
        admins.size(),
        added,
        skipped);
  }

  @Rollback
  public void rollback() {
    // Membership edges may already be relied on for authz by the time a rollback runs - not
    // restorable, matching the other forward-only v3-only units in this chain.
  }
}

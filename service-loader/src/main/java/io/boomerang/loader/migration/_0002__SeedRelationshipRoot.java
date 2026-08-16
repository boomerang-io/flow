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
 * Seed the relationship graph's root node — the anchor every other node hangs off.
 *
 * <p>Nothing in the application creates it: {@code RelationshipService.createNodeAndEdge} resolves
 * its parent through {@code resolveNodeOrThrow}, so user creation ({@code UserService}), workspace
 * creation ({@code WorkspaceService}) and global-task creation ({@code WorkspaceTaskService}) all
 * anchor on {@code (ROOT, "root")} and throw {@code IllegalArgumentException} if it is missing.
 * {@code RelationshipService.filter} likewise walks from it for the global task catalogue and for
 * global-scoped tokens. Without this document a fresh install cannot create its first user.
 *
 * <p>The legacy loader created the same node in changeset 4041; this change unit is the
 * fresh-install equivalent and is skipped on an install that already has it.
 */
@Change(id = "0002-seed-relationship-root", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0002__SeedRelationshipRoot {

  private static final Logger LOG = LoggerFactory.getLogger(_0002__SeedRelationshipRoot.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    Document root = SeedResources.node("root", "root", "root");
    boolean inserted =
        SeedResources.insertIfAbsent(
            db, names.resolve("rel_nodes"), Filters.eq("_id", root.getString("_id")), root);
    LOG.info(inserted ? "Seeded root relationship node" : "Root relationship node already present");
  }

  @Rollback
  public void rollback() {
    // The root node anchors every other node - removing it would orphan the whole graph.
  }
}

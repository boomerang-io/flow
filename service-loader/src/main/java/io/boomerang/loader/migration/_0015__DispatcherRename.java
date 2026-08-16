package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.HashSet;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-06 worker-tier rename ({@code agent} -> {@code dispatcher}), the persisted half. The
 * code-level rename (DispatcherService/DispatcherControllerV1/api paths) shipped earlier; this
 * migrates the data still using the old names: the {@code agents} collection becomes {@code
 * dispatchers} (Mongo's {@code renameCollection} carries indexes over, including the {@code
 * (name, host)} unique index {@code _0005} created — re-asserted here regardless, since asserting
 * is cheap and covers a database where the rename happened out-of-band), and the {@code agentRef}
 * claim-owner field on {@code task_runs}/{@code workflow_runs} becomes {@code dispatcherRef}.
 *
 * <p>Idempotent: the collection rename is skipped once {@code dispatchers} already exists (or
 * {@code agents} no longer does), and the field {@code $rename}s only touch documents that still
 * carry the old field name.
 */
@Change(id = "0015-dispatcher-rename", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0015__DispatcherRename {

  private static final Logger LOG = LoggerFactory.getLogger(_0015__DispatcherRename.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    renameAgentsCollection(db, names);
    renameAgentRefField(db, names.resolve("task_runs"));
    renameAgentRefField(db, names.resolve("workflow_runs"));
  }

  private void renameAgentsCollection(MongoDatabase db, CollectionNames names) {
    String agents = names.resolve("agents");
    String dispatchers = names.resolve("dispatchers");
    Set<String> existing = new HashSet<>();
    db.listCollectionNames().into(existing);

    if (existing.contains(agents) && !existing.contains(dispatchers)) {
      db.getCollection(agents).renameCollection(new MongoNamespace(db.getName(), dispatchers));
      LOG.info("Renamed collection {} -> {}", agents, dispatchers);
    } else {
      LOG.info(
          "Skipping {} -> {} rename (agents present={}, dispatchers present={})",
          agents,
          dispatchers,
          existing.contains(agents),
          existing.contains(dispatchers));
    }

    // renameCollection carries indexes, but assert/ensure regardless - cheap and covers a
    // database where the rename already happened out-of-band without this changeunit's help.
    ensureIndex(
        db,
        dispatchers,
        "registration",
        new Document("name", 1).append("host", 1),
        new IndexOptions().unique(true));
  }

  private void renameAgentRefField(MongoDatabase db, String collection) {
    long renamed =
        db.getCollection(collection)
            .updateMany(Filters.exists("agentRef"), Updates.rename("agentRef", "dispatcherRef"))
            .getModifiedCount();
    LOG.info("Renamed agentRef -> dispatcherRef on {} document(s) in {}", renamed, collection);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Field renames are not restored - dispatcherRef is authoritative going forward, matching the
    // other online migrations' rollback scope. Only the collection rename is reversed.
    String agents = names.resolve("agents");
    String dispatchers = names.resolve("dispatchers");
    Set<String> existing = new HashSet<>();
    db.listCollectionNames().into(existing);
    if (existing.contains(dispatchers) && !existing.contains(agents)) {
      db.getCollection(dispatchers).renameCollection(new MongoNamespace(db.getName(), agents));
    }
  }
}

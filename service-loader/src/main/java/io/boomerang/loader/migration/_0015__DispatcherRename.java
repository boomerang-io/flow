package io.boomerang.loader.migration;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-06 worker-tier rename ({@code agent} -> {@code dispatcher}), the persisted half. The
 * code-level rename (DispatcherService/DispatcherControllerV1/api paths) shipped earlier; this
 * migrates the data still using the old names: the {@code agents} collection becomes {@code
 * dispatchers} (Mongo's {@code renameCollection} carries any existing indexes over — a fresh v4
 * install never had one, since the old {@code (name, host)} uniqueness unit historically ran
 * BEFORE the rename in a different chain position), and the {@code agentRef} claim-owner field on
 * {@code task_runs}/{@code workflow_runs} becomes {@code dispatcherRef}.
 *
 * <p><b>Deliberately does NOT (re-)create the {@code registration} unique index here.</b> A real
 * upgrade's {@code agents} collection can carry duplicate {@code (name, host)} registrations (the
 * exact case the unique index guards against), and this unit runs BEFORE {@code
 * _0019__DomainIndexes.dispatcherRegistrationUniqueness}, which dedupes {@code dispatchers} before
 * building that index. An earlier revision of this unit asserted the index immediately after the
 * rename as a "cheap, belt-and-braces" no-op — but under T6-2's fail-loud-on-unique posture that
 * premature, unguarded attempt would throw on real duplicate data and abort the migration before
 * the dedupe it depends on ever runs. {@code _0019} unconditionally targets {@code dispatchers}
 * (not {@code agents}), so it covers both the normal case and a database where the rename already
 * happened out-of-band — no coverage is lost by leaving index creation solely to {@code _0019}.
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
    // Unique "registration" index intentionally NOT (re-)asserted here - see the class javadoc:
    // it must run AFTER _0019's dedupe, not immediately after this rename.
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

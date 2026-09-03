package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restructures the {@code audit} collection from per-object records (one document per workspace or
 * workflow carrying an unbounded {@code events} list) to flat per-event documents written by
 * {@code io.boomerang.core.audit.AuditEventWriter}.
 *
 * <p><b>The old records are dropped, not migrated</b> (maintainer-ruled): they recorded only that
 * an action occurred, keyed by object rather than by event, and nothing reads them any more. Every
 * document is deleted and every old index is dropped — the three {@code scope_*} compounds from
 * {@code _0036__RelationshipAndAuditIndexes} plus any single-field leftovers a v4 install's
 * auto-index-creation built.
 *
 * <p>The new-shape indexes are exactly five, because the collection is insert-only and every index
 * amplifies write cost: a TTL on {@code createdAt} (365-day default; the {@code
 * audit.retentionDays} setting is applied at service startup via collMod, floored at 60 days),
 * {@code time} descending for the default listing, and the {@code (workspaceId, time)}, {@code
 * (actorId, time)} and {@code (resourceType, resourceId, time)} compounds that serve the audit
 * query filters. Low-cardinality fields (action, outcome) deliberately get no index of their own.
 *
 * <p>Also seeds the {@code audit} settings document ({@code enabled}, {@code level}, {@code
 * retentionDays}) the capture gate reads — backfilled here for installs that ran {@code
 * _0021__SeedSettings} before the document existed, exactly as {@code _0035__AddAuthSettings}
 * backfilled the auth settings. Fresh installs pick it up from {@code seed/settings.json} via
 * {@code _0021} as well; the insert-if-absent guard makes both paths meet idempotently.
 *
 * <p>Idempotent: a second run deletes nothing (no old-shape documents match), re-dropping absent
 * indexes is a no-op, {@code ensureIndex} is a no-op on identical indexes, and the settings seed is
 * guarded on {@code _id} OR {@code key}.
 */
@Change(id = "0042-audit-event-restructure", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0042__AuditEventRestructure {

  private static final Logger LOG = LoggerFactory.getLogger(_0042__AuditEventRestructure.class);

  private static final long RETENTION_DAYS_DEFAULT = 365;

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String audit = names.resolve("audit");
    MongoCollection<Document> collection = db.getCollection(audit);

    // Old shape carries a "scope"; new flat events never do. Deleting by that discriminator is
    // what makes a re-run (or a run after events exist) leave the new trail untouched.
    long dropped = collection.deleteMany(Filters.exists("scope")).getDeletedCount();
    LOG.info("Dropped {} old per-object audit records from {}", dropped, audit);

    // Drop every pre-restructure index: the _0036 scope compounds by name, and any v4-era
    // single-field auto-built indexes over old-shape fields by inspection.
    for (String name : List.of("scope_self_ref", "scope_self_name", "scope_parent")) {
      dropIndex(db, audit, name);
    }
    List<String> oldShapeFields = List.of("scope", "selfRef", "selfName", "parent");
    for (Document index : collection.listIndexes().into(new ArrayList<>())) {
      Document key = index.get("key", Document.class);
      if (key != null && key.keySet().stream().anyMatch(oldShapeFields::contains)) {
        dropIndex(db, audit, index.getString("name"));
      }
    }

    ensureIndex(
        db,
        audit,
        "createdAt_ttl",
        new Document("createdAt", 1),
        new IndexOptions().expireAfter(RETENTION_DAYS_DEFAULT, TimeUnit.DAYS));
    ensureIndex(db, audit, "time_desc", new Document("time", -1), new IndexOptions());
    ensureIndex(
        db,
        audit,
        "workspace_time",
        new Document("workspaceId", 1).append("time", 1),
        new IndexOptions());
    ensureIndex(
        db, audit, "actor_time", new Document("actorId", 1).append("time", 1), new IndexOptions());
    ensureIndex(
        db,
        audit,
        "resource_time",
        new Document("resourceType", 1).append("resourceId", 1).append("time", 1),
        new IndexOptions());

    List<Document> settings = SeedResources.load("seed/settings.json");
    Document auditSetting =
        settings.stream()
            .filter(document -> "audit".equals(document.getString("key")))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException("seed/settings.json is missing the 'audit' document"));
    boolean inserted =
        SeedResources.insertIfAbsent(
            db,
            names.resolve("settings"),
            Filters.or(Filters.eq("_id", auditSetting.get("_id")), Filters.eq("key", "audit")),
            auditSetting);
    SeedResources.logSeeded("settings(audit)", inserted ? 1 : 0, 1);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Indexes only - never drop the audit trail itself, and the deleted old-shape records are
    // deliberately unrecoverable (maintainer-ruled drop, not migrate).
    String audit = names.resolve("audit");
    for (String name :
        List.of("createdAt_ttl", "time_desc", "workspace_time", "actor_time", "resource_time")) {
      dropIndex(db, audit, name);
    }
  }
}

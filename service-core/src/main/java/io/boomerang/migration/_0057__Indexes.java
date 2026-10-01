package io.boomerang.migration;

import static io.boomerang.migration.MigrationUtils.dropIndex;
import static io.boomerang.migration.MigrationUtils.ensureIndex;
import static io.boomerang.migration.MigrationUtils.findDuplicateKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.BsonType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build every index the application reads through, the same set on an empty and an upgraded
 * database. Runs after every data unit and seed so the dedupes that precede the unique indexes see
 * final data. An existing index that holds an inventory index's keys under another name, or its
 * name with other keys or options, is dropped first; any other index (an operator's own) is kept.
 */
@Change(id = "0057-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0057__Indexes {

  private static final Logger LOG = LoggerFactory.getLogger(_0057__Indexes.class);

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("succeeded", "failed", "invalid", "skipped", "cancelled", "timedout");

  /** Audit events expire after a year; {@code AuditRetentionService} re-tunes it at startup. */
  static final long AUDIT_RETENTION_DAYS = 365;

  record Index(String collection, String name, Document keys, IndexOptions options) {}

  static final List<Index> INVENTORY =
      List.of(
          // Claiming, the watcher sweeps and one task run per DAG node.
          new Index(
              "task_runs",
              "claim_page",
              keys("type", "status", "phase", "creationDate"),
              new IndexOptions()),
          new Index("task_runs", "run_tasks", keys("workflowRunRef", "status", "name"), new IndexOptions()),
          new Index("task_runs", "lease_sweep", keys("claim.leaseExpiresAt"), sparse()),
          new Index("task_runs", "timeout_sweep", keys("timeoutAt"), sparse()),
          new Index("task_runs", "wait_sweep", keys("waitUntil"), sparse()),
          new Index("task_runs", "claimed_sweep", keys("phase", "claim.at"), new IndexOptions()),
          new Index(
              "task_runs",
              "node_uniqueness",
              keys("workflowRunRef", "name"),
              new IndexOptions().unique(true)),
          // A foreach parent's items, in order; only items carry parentRef.
          new Index("task_runs", "parent_index", keys("parentRef", "index"), sparse()),
          new Index("task_runs", "label_wildcard", keys("labels.$**"), new IndexOptions()),
          new Index(
              "workflow_runs", "claim_page", keys("status", "phase", "creationDate"), new IndexOptions()),
          new Index("workflow_runs", "timeout_sweep", keys("timeoutAt"), sparse()),
          new Index("workflow_runs", "paused_lookup", keys("pauseRequestedAt"), sparse()),
          new Index(
              "workflow_runs", "phase_creation_sweep", keys("phase", "creationDate"), new IndexOptions()),
          new Index("workflow_runs", "phase_start_sweep", keys("phase", "startTime"), new IndexOptions()),
          new Index("workflow_runs", "workflow_ref_phase", keys("workflowRef", "phase"), new IndexOptions()),
          new Index(
              "workflow_runs",
              "workflow_ref_creation",
              keys("workflowRef", "creationDate"),
              new IndexOptions()),
          new Index(
              "workflow_runs", "workflow_ref_status", keys("workflowRef", "status"), new IndexOptions()),
          // The child runs a cascade cancel pages through.
          new Index(
              "workflow_runs", "initiated_by_phase", keys("initiatedByRef", "phase"), new IndexOptions()),
          new Index("workflow_runs", "label_wildcard", keys("labels.$**"), new IndexOptions()),
          // Definitions.
          new Index("workflows", "status_lookup", keys("status"), new IndexOptions()),
          new Index("workflows", "name_lookup", keys("name"), new IndexOptions()),
          new Index("workflows", "creation_date_sort", keys("creationDate"), new IndexOptions()),
          new Index(
              "workflow_revisions",
              "workflow_ref_version",
              keys("workflowRef", "version"),
              new IndexOptions()),
          new Index("workflow_templates", "name_version", keys("name", "version"), new IndexOptions()),
          new Index("workflow_schedules", "fire_sweep", keys("status", "nextFireAt"), new IndexOptions()),
          new Index("workflow_schedules", "workflow_lookup", keys("workflowRef"), new IndexOptions()),
          new Index("tasks", "name_lookup", keys("name"), new IndexOptions()),
          new Index("tasks", "creation_date_sort", keys("creationDate"), new IndexOptions()),
          new Index(
              "task_revisions", "parent_ref_version", keys("parentRef", "version"), new IndexOptions()),
          // The outbox, the inbox's dedup ledger and task locks; sent and received rows expire.
          new Index("events_outbox", "dispatch_page", keys("status", "occurredAt"), new IndexOptions()),
          new Index(
              "events_outbox",
              "sent_ttl",
              keys("sentAt"),
              new IndexOptions().expireAfter(7L, TimeUnit.DAYS)),
          new Index(
              "events_inbox",
              "received_ttl",
              keys("receivedAt"),
              new IndexOptions().expireAfter(7L, TimeUnit.DAYS)),
          new Index("events_inbox", "redrive_page", keys("status", "receivedAt"), new IndexOptions()),
          new Index(
              "task_locks",
              "lease_ttl",
              keys("expiresAt"),
              new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)),
          // One gate record per task run; a record without a task run is not indexed.
          new Index(
              "actions",
              "task_run",
              keys("taskRunRef"),
              new IndexOptions()
                  .unique(true)
                  .partialFilterExpression(
                      new Document("taskRunRef", new Document("$exists", true)))),
          new Index("actions", "status_sweep", keys("status", "creationDate"), new IndexOptions()),
          new Index(
              "dispatchers", "registration", keys("name", "host"), new IndexOptions().unique(true)),
          // Identity, workspaces and the relationship graph.
          new Index("users", "email_unique", keys("email"), new IndexOptions().unique(true)),
          new Index("workspaces", "name_lookup", keys("name"), new IndexOptions()),
          new Index("workspaces", "display_name_lookup", keys("displayName"), new IndexOptions()),
          new Index("tokens", "token_hash_lookup", keys("token"), new IndexOptions()),
          new Index(
              "rel_nodes",
              "type_slug",
              new Document("type", -1).append("slug", -1),
              new IndexOptions()),
          new Index(
              "rel_nodes", "type_ref", new Document("type", -1).append("ref", -1), new IndexOptions()),
          new Index(
              "rel_edges",
              "from_label",
              new Document("from", -1).append("label", -1),
              new IndexOptions()),
          new Index(
              "rel_edges", "to_label", new Document("to", -1).append("label", -1), new IndexOptions()),
          // Audit events.
          new Index(
              "audit",
              "createdAt_ttl",
              keys("createdAt"),
              new IndexOptions().expireAfter(AUDIT_RETENTION_DAYS, TimeUnit.DAYS)),
          new Index("audit", "time_desc", new Document("time", -1), new IndexOptions()),
          new Index("audit", "workspace_time", keys("workspaceId", "time"), new IndexOptions()),
          new Index("audit", "actor_time", keys("actorId", "time"), new IndexOptions()),
          new Index(
              "audit",
              "resource_time",
              keys("resourceType", "resourceId", "time"),
              new IndexOptions()),
          // Artifacts: one name per run, the expiry and stale-upload sweeps, the workspace list.
          new Index(
              "artifacts",
              "run_name_idx",
              keys("workflowRunRef", "name"),
              new IndexOptions().unique(true)),
          new Index(
              "artifacts", "status_expiration_idx", keys("status", "expirationDate"), new IndexOptions()),
          new Index("artifacts", "workflow_status_idx", keys("workflowRef", "status"), new IndexOptions()));

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    dedupeTaskRuns(db, names.resolve("task_runs"));
    dedupeActions(db, names.resolve("actions"));
    dedupeDispatchers(db, names.resolve("dispatchers"));
    warnOnDuplicateEmails(db, names.resolve("users"));
    for (Index index : INVENTORY) {
      String collection = names.resolve(index.collection());
      dropConflicting(db.getCollection(collection), index);
      ensureIndex(db, collection, index.name(), index.keys(), index.options());
    }
    LOG.info("{} indexes ensured", INVENTORY.size());
  }

  // ===================================================================================
  // Dedupes that must precede the unique indexes
  // ===================================================================================

  /** One task run per DAG node: keep the one that finished, else the earliest created. */
  private void dedupeTaskRuns(MongoDatabase db, String collection) {
    MongoCollection<Document> taskRuns = db.getCollection(collection);
    long deleted = 0;
    for (Document group :
        findDuplicateKeys(
            db,
            collection,
            new Document("workflowRunRef", "$workflowRunRef").append("name", "$name"))) {
      Document key = group.get("_id", Document.class);
      List<Document> runs = new ArrayList<>();
      taskRuns
          .find(
              Filters.and(
                  Filters.eq("workflowRunRef", key.get("workflowRunRef")),
                  Filters.eq("name", key.get("name"))))
          .sort(Sorts.ascending("creationDate", "_id"))
          .into(runs);
      Document keeper =
          runs.stream()
              .filter(run -> TERMINAL_STATUSES.contains(run.getString("status")))
              .findFirst()
              .orElse(runs.get(0));
      deleted +=
          taskRuns
              .deleteMany(
                  Filters.and(
                      Filters.in("_id", runs.stream().map(run -> run.get("_id")).toList()),
                      Filters.ne("_id", keeper.get("_id"))))
              .getDeletedCount();
    }
    LOG.info("task_runs dedupe removed {} duplicate node runs", deleted);
  }

  /** One gate record per task run, keeping the earliest; a null reference is removed first. */
  private void dedupeActions(MongoDatabase db, String collection) {
    MongoCollection<Document> actions = db.getCollection(collection);
    actions.updateMany(Filters.type("taskRunRef", BsonType.NULL), Updates.unset("taskRunRef"));
    long deleted = 0;
    for (Document group :
        findDuplicateKeys(
            db,
            collection,
            new Document("taskRunRef", new Document("$type", "string")),
            new Document("taskRunRef", "$taskRunRef"))) {
      deleted +=
          deleteAllButFirst(
              actions,
              Filters.eq("taskRunRef", group.get("_id", Document.class).get("taskRunRef")),
              Sorts.ascending("creationDate", "_id"));
    }
    LOG.info("actions dedupe removed {} duplicate gate records", deleted);
  }

  /** One registration per dispatcher name and host, keeping the most recently connected. */
  private void dedupeDispatchers(MongoDatabase db, String collection) {
    MongoCollection<Document> dispatchers = db.getCollection(collection);
    long deleted = 0;
    for (Document group :
        findDuplicateKeys(db, collection, new Document("name", "$name").append("host", "$host"))) {
      Document key = group.get("_id", Document.class);
      deleted +=
          deleteAllButFirst(
              dispatchers,
              Filters.and(Filters.eq("name", key.get("name")), Filters.eq("host", key.get("host"))),
              Sorts.descending("lastConnectedDate", "_id"));
    }
    LOG.info("dispatchers dedupe removed {} stale registrations", deleted);
  }

  /** Accounts are never deleted to satisfy an index: a shared email fails the unique build. */
  private void warnOnDuplicateEmails(MongoDatabase db, String users) {
    int duplicates = findDuplicateKeys(db, users, new Document("email", "$email")).size();
    if (duplicates > 0) {
      LOG.warn(
          "{} email value(s) are shared by more than one user; email_unique cannot be built until"
              + " an operator resolves them",
          duplicates);
    }
  }

  // ===================================================================================
  // Reconciling an existing index with the inventory
  // ===================================================================================

  /** Drop an index that holds these keys under another name, or this name with another shape. */
  private void dropConflicting(MongoCollection<Document> collection, Index index) {
    List<String> conflicting = new ArrayList<>();
    try (MongoCursor<Document> existing = collection.listIndexes().iterator()) {
      while (existing.hasNext()) {
        Document current = existing.next();
        String name = current.getString("name");
        boolean sameKeys = sameKeys(index.keys(), current.get("key", Document.class));
        boolean sameName = index.name().equals(name);
        if (!"_id_".equals(name) && (sameKeys != sameName || (sameName && !sameShape(current, index)))) {
          conflicting.add(name);
        }
      }
    }
    for (String name : conflicting) {
      LOG.info(
          "Dropping index {} on {}: it conflicts with {}",
          name,
          collection.getNamespace().getCollectionName(),
          index.name());
      collection.dropIndex(name);
    }
  }

  /** Same fields in the same order and direction; another tool may store {@code 1} as {@code 1.0}. */
  private static boolean sameKeys(Document wanted, Document current) {
    if (current == null || !List.copyOf(wanted.keySet()).equals(List.copyOf(current.keySet()))) {
      return false;
    }
    return wanted.keySet().stream()
        .allMatch(field -> direction(wanted.get(field)).equals(direction(current.get(field))));
  }

  private static Object direction(Object value) {
    return (value instanceof Number number) ? (Object) number.intValue() : value;
  }

  /** Same uniqueness, sparseness and partial filter; a re-tuned expiry is not a conflict. */
  private static boolean sameShape(Document current, Index index) {
    IndexOptions options = index.options();
    return Boolean.TRUE.equals(current.getBoolean("unique")) == options.isUnique()
        && Boolean.TRUE.equals(current.getBoolean("sparse")) == options.isSparse()
        && Objects.equals(current.get("partialFilterExpression"), options.getPartialFilterExpression());
  }

  private static long deleteAllButFirst(
      MongoCollection<Document> collection, Bson filter, Bson order) {
    List<Object> extras = new ArrayList<>();
    try (MongoCursor<Document> cursor = collection.find(filter).sort(order).skip(1).iterator()) {
      while (cursor.hasNext()) {
        extras.add(cursor.next().get("_id"));
      }
    }
    return extras.isEmpty() ? 0 : collection.deleteMany(Filters.in("_id", extras)).getDeletedCount();
  }

  private static Document keys(String... fields) {
    Document keys = new Document();
    for (String field : fields) {
      keys.append(field, 1);
    }
    return keys;
  }

  private static IndexOptions sparse() {
    return new IndexOptions().sparse(true);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    for (Index index : INVENTORY) {
      dropIndex(db, names.resolve(index.collection()), index.name());
    }
  }
}

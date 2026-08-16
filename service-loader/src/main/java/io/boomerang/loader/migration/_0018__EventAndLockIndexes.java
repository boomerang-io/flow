package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.concurrent.TimeUnit;
import org.bson.Document;

/**
 * Event-outbox/inbox and task-lock indexes. Formerly three separate units ({@code
 * _0007__EventOutboxIndexes}, {@code _0008__EventInboxIndexes}, {@code _0009__TaskLockIndexes}) —
 * merged since all three are small, ungated, independent index passes over their own collections;
 * see {@code _0017__RunIndexes}'s javadoc for why every index unit in this program now runs in
 * Phase 4, after the v3→v5 migration and v4 rename fixups populate the collections they index.
 */
@Change(id = "0018-event-and-lock-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0018__EventAndLockIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    eventOutboxIndexes(db, names);
    eventInboxIndexes(db, names);
    taskLockIndexes(db, names);
  }

  // =====================================================================================
  // events_outbox (formerly _0007__EventOutboxIndexes)
  // =====================================================================================

  /**
   * events_outbox indexes: the FIFO dispatch page and a 7-day TTL on delivered rows. The TTL keys
   * on {@code sentAt}, which only sent rows carry — pending and dead rows are never expired.
   */
  private void eventOutboxIndexes(MongoDatabase db, CollectionNames names) {
    String eventsOutbox = names.resolve("events_outbox");
    ensureIndex(
        db,
        eventsOutbox,
        "dispatch_page",
        new Document("status", 1).append("occurredAt", 1),
        new IndexOptions());
    ensureIndex(
        db,
        eventsOutbox,
        "sent_ttl",
        new Document("sentAt", 1),
        new IndexOptions().expireAfter(7L, TimeUnit.DAYS));
  }

  // =====================================================================================
  // events_inbox (formerly _0008__EventInboxIndexes)
  // =====================================================================================

  /**
   * events_inbox indexes: a 7-day TTL on the dedup ledger (matching transport redelivery
   * windows) and the status page a re-drive sweep scans for stale received rows.
   */
  private void eventInboxIndexes(MongoDatabase db, CollectionNames names) {
    String eventsInbox = names.resolve("events_inbox");
    ensureIndex(
        db,
        eventsInbox,
        "received_ttl",
        new Document("receivedAt", 1),
        new IndexOptions().expireAfter(7L, TimeUnit.DAYS));
    ensureIndex(
        db,
        eventsInbox,
        "redrive_page",
        new Document("status", 1).append("receivedAt", 1),
        new IndexOptions());
  }

  // =====================================================================================
  // task_locks (formerly _0009__TaskLockIndexes)
  // =====================================================================================

  /**
   * task_locks index: a TTL on expiresAt to garbage-collect a crashed holder's lock. Correctness
   * is the acquire Compare-And-Set checking expiresAt itself, so the sweep timing is not relied
   * upon.
   */
  private void taskLockIndexes(MongoDatabase db, CollectionNames names) {
    String taskLocks = names.resolve("task_locks");
    ensureIndex(
        db,
        taskLocks,
        "lease_ttl",
        new Document("expiresAt", 1),
        new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String eventsOutbox = names.resolve("events_outbox");
    dropIndex(db, eventsOutbox, "dispatch_page");
    dropIndex(db, eventsOutbox, "sent_ttl");

    String eventsInbox = names.resolve("events_inbox");
    dropIndex(db, eventsInbox, "received_ttl");
    dropIndex(db, eventsInbox, "redrive_page");

    dropIndex(db, names.resolve("task_locks"), "lease_ttl");
  }
}

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
 * events_outbox indexes: the FIFO dispatch page and a 7-day TTL on delivered rows. The TTL keys
 * on {@code sentAt}, which only sent rows carry — pending and dead rows are never expired.
 */
@Change(id = "0007-event-outbox-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0007__EventOutboxIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
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

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String eventsOutbox = names.resolve("events_outbox");
    dropIndex(db, eventsOutbox, "dispatch_page");
    dropIndex(db, eventsOutbox, "sent_ttl");
  }
}

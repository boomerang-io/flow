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
 * events_ingress indexes: a 7-day TTL on the dedup ledger (matching transport redelivery
 * windows) and the status page a re-drive sweep scans for stale received rows.
 */
@Change(id = "0008-event-ingress-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0008__EventIngressIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String eventsIngress = names.resolve("events_ingress");
    ensureIndex(
        db,
        eventsIngress,
        "received_ttl",
        new Document("receivedAt", 1),
        new IndexOptions().expireAfter(7L, TimeUnit.DAYS));
    ensureIndex(
        db,
        eventsIngress,
        "redrive_page",
        new Document("status", 1).append("receivedAt", 1),
        new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    String eventsIngress = names.resolve("events_ingress");
    dropIndex(db, eventsIngress, "received_ttl");
    dropIndex(db, eventsIngress, "redrive_page");
  }
}

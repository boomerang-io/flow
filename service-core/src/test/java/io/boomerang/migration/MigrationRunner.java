package io.boomerang.migration;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.flamingock.community.Flamingock;
import io.flamingock.store.mongodb.sync.MongoDBSyncAuditStore;
import io.flamingock.targetsystem.mongodb.sync.MongoDBSyncTargetSystem;

/**
 * Run the change-unit chain outside Spring, the same way {@link MigrationConfiguration} wires it,
 * against a database a test has prepared (a hand-built legacy fixture or a restored dump).
 */
abstract class MigrationRunner {

  private MigrationRunner() {}

  /** Run every pending change unit against {@code uri}; throws on any failure. */
  static void run(String uri, String collectionPrefix) {
    ConnectionString connection = new ConnectionString(uri);
    CollectionNames names = new CollectionNames(collectionPrefix);
    try (MongoClient client = MongoClients.create(connection)) {
      MongoDBSyncTargetSystem targetSystem =
          new MongoDBSyncTargetSystem(
              MigrationConfiguration.TARGET_SYSTEM_ID, client, connection.getDatabase());
      targetSystem.addDependency(CollectionNames.class, names);
      MongoDBSyncAuditStore auditStore =
          MongoDBSyncAuditStore.from(targetSystem)
              .withAuditRepositoryName(names.resolve("sys_changelog_loader"))
              .withLockRepositoryName(names.resolve("sys_lock_loader"));
      Flamingock.builder().addTargetSystem(targetSystem).setAuditStore(auditStore).build().run();
    }
  }
}

package io.boomerang.migration;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.flamingock.community.Flamingock;
import io.flamingock.store.mongodb.sync.MongoDBSyncAuditStore;
import io.flamingock.targetsystem.mongodb.sync.MongoDBSyncTargetSystem;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Run the change-unit chain outside Spring, the same way {@link MigrationConfiguration} wires it,
 * against a database a test has prepared (a hand-built legacy fixture or a restored dump).
 */
abstract class MigrationRunner {

  private MigrationRunner() {}

  /** Run every pending change unit against {@code uri}; throws on any failure. */
  static void run(String uri, String collectionPrefix) {
    ConnectionString connection = new ConnectionString(uri);
    Environment environment = environment(collectionPrefix);
    CollectionNames names = CollectionNames.from(environment);
    try (MongoClient client = MongoClients.create(connection)) {
      MongoDBSyncTargetSystem targetSystem =
          new MongoDBSyncTargetSystem(
              MigrationConfiguration.TARGET_SYSTEM_ID, client, connection.getDatabase());
      targetSystem.addDependency(Environment.class, environment);
      MongoDBSyncAuditStore auditStore =
          MongoDBSyncAuditStore.from(targetSystem)
              .withAuditRepositoryName(names.resolve("sys_migration_changelog"))
              .withLockRepositoryName(names.resolve("sys_migration_lock"));
      Flamingock.builder().addTargetSystem(targetSystem).setAuditStore(auditStore).build().run();
    }
  }

  /** The environment a change unit reads its collection prefix from. */
  static Environment environment(String collectionPrefix) {
    return new MockEnvironment().withProperty("flow.mongo.collection.prefix", collectionPrefix);
  }
}

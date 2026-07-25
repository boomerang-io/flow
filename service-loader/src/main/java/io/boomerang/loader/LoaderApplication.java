package io.boomerang.loader;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.flamingock.api.annotations.EnableFlamingock;
import io.flamingock.api.annotations.Stage;
import io.flamingock.community.Flamingock;
import io.flamingock.store.mongodb.sync.MongoDBSyncAuditStore;
import io.flamingock.targetsystem.mongodb.sync.MongoDBSyncTargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run all pending database migrations against the Flow MongoDB and exit — non-zero on any
 * failure, so a pre-deploy Job halts the rollout. Connection comes from {@code flow.mongo.uri}
 * (or env {@code FLOW_MONGO_URI}); collection names honour {@code flow.mongo.collection.prefix}
 * (or env {@code FLOW_MONGO_COLLECTION_PREFIX}).
 */
@EnableFlamingock(stages = {@Stage(location = "io.boomerang.loader.migration")})
public class LoaderApplication {

  public static final String TARGET_SYSTEM_ID = "flow-mongodb";

  private static final Logger LOG = LoggerFactory.getLogger(LoaderApplication.class);
  private static final String DEFAULT_DATABASE = "boomerang";

  public static void main(String[] args) {
    String uri = setting("flow.mongo.uri", "FLOW_MONGO_URI");
    String prefix = setting("flow.mongo.collection.prefix", "FLOW_MONGO_COLLECTION_PREFIX");
    if (uri == null || uri.isBlank()) {
      LOG.error("No MongoDB connection configured — set flow.mongo.uri or FLOW_MONGO_URI");
      System.exit(1);
    }
    try {
      execute(uri, prefix);
    } catch (Exception e) {
      LOG.error("Migration run failed", e);
      System.exit(1);
    }
  }

  /** Run the Flamingock pipeline; throws on any migration failure. */
  public static void execute(String uri, String collectionPrefix) {
    ConnectionString connection = new ConnectionString(uri);
    String databaseName =
        (connection.getDatabase() != null) ? connection.getDatabase() : DEFAULT_DATABASE;
    CollectionNames names = new CollectionNames(collectionPrefix);
    try (MongoClient client = MongoClients.create(connection)) {
      MongoDBSyncTargetSystem targetSystem =
          new MongoDBSyncTargetSystem(TARGET_SYSTEM_ID, client, databaseName);
      MongoDBSyncAuditStore auditStore =
          MongoDBSyncAuditStore.from(targetSystem)
              .withAuditRepositoryName(names.resolve("sys_changelog_loader"))
              .withLockRepositoryName(names.resolve("sys_lock_loader"));
      Flamingock.builder()
          .addTargetSystem(targetSystem)
          .setAuditStore(auditStore)
          .addDependency(names)
          .build()
          .run();
    }
    LOG.info("Migration run complete for database {}", databaseName);
  }

  private static String setting(String property, String envVar) {
    String value = System.getProperty(property);
    return (value != null && !value.isBlank()) ? value : System.getenv(envVar);
  }
}

package io.boomerang.migration;

import com.mongodb.client.MongoClient;
import io.boomerang.core.config.MongoConfiguration;
import io.flamingock.api.annotations.EnableFlamingock;
import io.flamingock.api.annotations.Stage;
import io.flamingock.internal.core.external.store.CommunityAuditStore;
import io.flamingock.store.mongodb.sync.MongoDBSyncAuditStore;
import io.flamingock.targetsystem.mongodb.sync.MongoDBSyncTargetSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;

/**
 * Run every pending change unit against the application's own MongoDB client while the context
 * starts ({@code flamingock.management-mode=INITIALIZING_BEAN}), so the web server, the scheduled
 * sweeps and the dispatcher API only ever see a migrated database. Replicas that start together
 * wait on the lock; a failed unit fails startup.
 */
@Configuration
@EnableFlamingock(stages = {@Stage(location = "io.boomerang.migration")})
public class MigrationConfiguration {

  public static final String TARGET_SYSTEM_ID = "flow-mongodb";

  @Bean
  public MongoDBSyncTargetSystem migrationTargetSystem(
      MongoClient mongoClient,
      MongoDatabaseFactory databaseFactory,
      MongoConfiguration mongoConfiguration) {
    MongoDBSyncTargetSystem targetSystem =
        new MongoDBSyncTargetSystem(
            TARGET_SYSTEM_ID, mongoClient, databaseFactory.getMongoDatabase().getName());
    // Registered by explicit type: change units take CollectionNames, which Flamingock does not
    // resolve on its own.
    targetSystem.addDependency(
        CollectionNames.class, new CollectionNames(mongoConfiguration.collectionPrefix()));
    return targetSystem;
  }

  @Bean
  public CommunityAuditStore migrationAuditStore(
      MongoDBSyncTargetSystem migrationTargetSystem, MongoConfiguration mongoConfiguration) {
    CollectionNames names = new CollectionNames(mongoConfiguration.collectionPrefix());
    return MongoDBSyncAuditStore.from(migrationTargetSystem)
        .withAuditRepositoryName(names.resolve("sys_migration_changelog"))
        .withLockRepositoryName(names.resolve("sys_migration_lock"));
  }
}

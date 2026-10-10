package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * V3-only. Moves the v3 global parameters into {@code parameters}, the collection {@code
 * GlobalParamEntity} reads, and drops the v3 collection.
 *
 * <p>The v3 collection is {@code global_config} ({@code {_id, key, label, type, value,
 * description, readOnly}}): {@code key} becomes {@code name}, the other fields carry across, and
 * the {@code _id} is kept, which makes the insert idempotent. No {@code _class} is written. A
 * {@code global_params} collection, the name an earlier loader read by mistake, is migrated the
 * same way should an install carry one, taking {@code values} or else {@code value}.
 */
@Change(id = "0005-v3-migrate-global-parameters", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0005__V3MigrateGlobalParameters {

  private static final Logger LOG = LoggerFactory.getLogger(_0005__V3MigrateGlobalParameters.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install - no v3 global parameters to migrate.");
      return;
    }
    long fromConfig = migrateGlobalConfig(db, names);
    long fromParams = migrateGlobalParams(db, names);

    LOG.info(
        "v3 global parameters migrated to {} — {} from global_config, {} from global_params",
        names.resolve("parameters"),
        fromConfig,
        fromParams);
  }

  /** The real v3 source: {@code global_config}, {@code key}/{@code value} (singular). */
  private long migrateGlobalConfig(MongoDatabase db, CollectionNames names) {
    String collectionName = names.resolve("global_config");
    MongoCollection<Document> globalConfig = db.getCollection(collectionName);
    if (globalConfig.countDocuments() == 0) {
      LOG.info("No global_config documents to migrate.");
      return 0;
    }
    long migrated = 0;
    for (Document source : globalConfig.find()) {
      Document param = toParameter(source, source.getString("key"), source.get("value"));
      if (SeedResources.insertIfAbsent(
          db, names.resolve("parameters"), Filters.eq("_id", source.get("_id")), param)) {
        migrated++;
      }
    }
    globalConfig.drop();
    return migrated;
  }

  /** {@code global_params}, should an install carry one: {@code values}, else {@code value}. */
  private long migrateGlobalParams(MongoDatabase db, CollectionNames names) {
    String collectionName = names.resolve("global_params");
    MongoCollection<Document> globalParams = db.getCollection(collectionName);
    if (globalParams.countDocuments() == 0) {
      return 0;
    }
    LOG.info(
        "global_params collection found — migrating defensively (not present on the verified v3"
            + " dump; unexpected on a real v3 install).");
    long migrated = 0;
    for (Document source : globalParams.find()) {
      Object value = source.containsKey("values") ? source.get("values") : source.get("value");
      Document param = toParameter(source, source.getString("key"), value);
      if (SeedResources.insertIfAbsent(
          db, names.resolve("parameters"), Filters.eq("_id", source.get("_id")), param)) {
        migrated++;
      }
    }
    globalParams.drop();
    return migrated;
  }

  private Document toParameter(Document source, String name, Object value) {
    Document param = new Document("_id", source.get("_id"));
    param.put("name", name);
    putIfPresent(param, source, "label");
    putIfPresent(param, source, "type");
    putIfPresent(param, source, "description");
    param.put("value", value);
    putIfPresent(param, source, "readOnly");
    return param;
  }

  private void putIfPresent(Document target, Document source, String field) {
    if (source.containsKey(field)) {
      target.put(field, source.get(field));
    }
  }

  @Rollback
  public void rollback() {
    // The source collections are dropped once migrated - forward-only, matching the other v3-only
    // migrations in this chain.
  }
}

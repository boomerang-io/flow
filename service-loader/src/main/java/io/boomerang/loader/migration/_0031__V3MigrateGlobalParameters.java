package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Fixes a real v4 bug (see "v3 → v5 migration consolidation" in {@code
 * specifications/merge-execution-plan.md}): legacy changeset {@code 4045} migrates {@code
 * global_params} -> {@code parameters} mapping {@code key}->{@code name}, {@code values}->{@code
 * value}. But the REAL v3 collection is {@code global_config} (verified against a real v3 dump: 1
 * document, {@code {_id, key, label, type, value, description, readOnly}}, {@code
 * _class=io.boomerang.mongo.entity.FlowGlobalConfigEntity}) and its value field is {@code value}
 * (singular, not {@code values}) — {@code 4045} matched nothing on real v3 data, so global
 * parameters were silently dropped on every real v4 install, and {@code global_config} was never
 * even dropped.
 *
 * <p>Migrates {@code global_config} -> {@code parameters} onto {@code
 * io.boomerang.workflow.entity.GlobalParamEntity} ({@code extends
 * io.boomerang.common.model.AbstractParam}): {@code key}->{@code name}, {@code value}->{@code
 * value}, {@code label}/{@code type}/{@code description}/{@code readOnly} carried straight
 * across — the only fields the v3 document and the v5 entity both have (the entity's richer
 * fields — {@code defaultValue}, {@code minValueLength}, {@code options}, {@code required}, ... —
 * have no v3 source and are left unset). The original {@code _id} is preserved as the new
 * document's {@code id} (the entity's {@code @Id} is a plain {@code String}; Spring Data's
 * built-in {@code ObjectId<->String} converters make this transparent), which doubles as this
 * unit's idempotency key. No {@code _class} is written (the target document is built from
 * scratch), avoiding the same stale-discriminator hazard {@code _0021__V3MigrateSettings} strips
 * from the settings collection. {@code global_config} is dropped once migrated.
 *
 * <p>Defensively also handles a {@code global_params} collection (the name {@code 4045} actually
 * read) should some other install carry one, mapping {@code key}->{@code name} and {@code
 * values}->{@code value} (falling back to a singular {@code value} field if that's what is
 * present instead) per {@code 4045}'s original intent — dropped once migrated. On the verified v3
 * dump this collection does not exist at all: only {@code global_config} does, with exactly one
 * document.
 */
@Change(id = "0031-v3-migrate-global-parameters", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0031__V3MigrateGlobalParameters {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0031__V3MigrateGlobalParameters.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — no global_config/global_params to migrate.");
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

  /**
   * Defensive: the collection legacy {@code 4045} actually targeted, in case some install
   * genuinely has one. {@code values} (plural) is 4045's field; fall back to singular {@code
   * value} if that's what is actually present.
   */
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
    // The source collections are dropped once migrated - not restorable, matching the other
    // forward-only online migrations in this chain.
  }
}

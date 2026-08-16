package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Merges the settings migration with the global-parameters migration (formerly separate
 * units {@code _0021__V3MigrateSettings} and {@code _0031__V3MigrateGlobalParameters}) — both
 * transform small, independent, single-purpose v3 collections into their v5 shapes and always run
 * together in the same generation, so folding them into one pass halves this program's number of
 * full-database scans for settings-adjacent data without changing either algorithm.
 *
 * <h2>Settings</h2>
 *
 * Squashes legacy v4 changesets {@code 4019} (drop User Defaults), {@code 4020} (task
 * settings key renames), {@code 4025}+{@code 4037} (extensions -> integration + GitHub config),
 * {@code 4039} (team quota key renames/relabels + the new {@code max.workflowrun.storage} entry)
 * and {@code 4040} (feature-flag rename) into ONE pass that transforms the v3 {@code settings}
 * collection (8 documents, keyed by the legacy {@code key} field: controller/activity/workflow/
 * users/features/teams/extensions/customizations — verified against a real v3 dump) directly into
 * the v5 shape: the 7 documents {@code service-loader/src/main/resources/seed/settings.json}
 * seeds on a fresh/v4 install (task/workflowrun/workflow/features/teams/integration/
 * customizations).
 *
 * <p>Every {@code _id} is preserved (the seed's own ids were captured from this same legacy
 * data), so this unit locates each document by its known literal {@code _id} rather than by
 * {@code key} — the very field the migration is rewriting. Idempotent: each rename only fires
 * when the pre-migration key/value is still present, and the {@code _id} lookups make a second
 * run a no-op write of the already-migrated content.
 *
 * <p>Also strips the stale {@code _class} discriminator ({@code
 * io.boomerang.mongo.entity.FlowSettingsEntity}) every v3 settings document carries (confirmed in
 * the dump). Left in place, {@code MappingMongoConverter} would fail to resolve that (nonexistent
 * on this classpath) type on the very next {@code SettingsService} read — none of the legacy v4
 * changesets stripped it because the v4 loader never introduced a different entity class; v5's
 * {@code SettingEntity} does. Stripped from all 7 surviving documents, including the two
 * (workflow/customizations) whose key/config never changes.
 *
 * <p>Verified against a real v3 dump: the {@code integration} (v3 {@code extensions}) document
 * carries one config key with no v5 counterpart — {@code slack.installURL}, an operator-set value
 * from a legacy "Manage Distribution" flow the v5 seed shape does not model. Left in place
 * (harmless extra entry; {@code SettingConfig} readers key off known entries and ignore the rest)
 * rather than destroyed, since it may still carry a live operator-configured value.
 *
 * <h2>Global parameters</h2>
 *
 * Fixes a real v4 bug: legacy changeset {@code 4045} migrates {@code global_params} -> {@code
 * parameters} mapping {@code key}->{@code name}, {@code values}->{@code value}. But the REAL v3
 * collection is {@code global_config} (verified against a real v3 dump: 1 document, {@code {_id,
 * key, label, type, value, description, readOnly}}, {@code
 * _class=io.boomerang.mongo.entity.FlowGlobalConfigEntity}) and its value field is {@code value}
 * (singular, not {@code values}) - {@code 4045} matched nothing on real v3 data, so global
 * parameters were silently dropped on every real v4 install, and {@code global_config} was never
 * even dropped.
 *
 * <p>Migrates {@code global_config} -> {@code parameters} onto {@code
 * io.boomerang.workflow.entity.GlobalParamEntity} ({@code extends
 * io.boomerang.common.model.AbstractParam}): {@code key}->{@code name}, {@code value}->{@code
 * value}, {@code label}/{@code type}/{@code description}/{@code readOnly} carried straight
 * across - the only fields the v3 document and the v5 entity both have (the entity's richer
 * fields - {@code defaultValue}, {@code minValueLength}, {@code options}, {@code required}, ... -
 * have no v3 source and are left unset). The original {@code _id} is preserved as the new
 * document's {@code id} (the entity's {@code @Id} is a plain {@code String}; Spring Data's
 * built-in {@code ObjectId<->String} converters make this transparent), which doubles as this
 * unit's idempotency key. No {@code _class} is written (the target document is built from
 * scratch), avoiding the same stale-discriminator hazard the settings half strips from the
 * settings collection. {@code global_config} is dropped once migrated.
 *
 * <p>Defensively also handles a {@code global_params} collection (the name {@code 4045} actually
 * read) should some other install carry one, mapping {@code key}->{@code name} and {@code
 * values}->{@code value} (falling back to a singular {@code value} field if that's what is
 * present instead) per {@code 4045}'s original intent - dropped once migrated. On the verified v3
 * dump this collection does not exist at all: only {@code global_config} does, with exactly one
 * document.
 */
@Change(id = "0005-v3-migrate-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0005__V3MigrateSettings {

  private static final Logger LOG = LoggerFactory.getLogger(_0005__V3MigrateSettings.class);

  private static final ObjectId USERS_ID = new ObjectId("6123c1e20b07a54cdce637c0");
  private static final ObjectId ACTIVITY_ID = new ObjectId("60245957226920beece4fdf9");
  private static final ObjectId CONTROLLER_ID = new ObjectId("5f32cb19d09662744c0df51d");
  private static final ObjectId EXTENSIONS_ID = new ObjectId("62a7bec0a6166d30aff64a5b");
  private static final ObjectId TEAMS_ID = new ObjectId("61393f5966c5eea103dfe134");
  private static final ObjectId FEATURES_ID = new ObjectId("612904d60b07a54cdc4dc6a9");
  private static final ObjectId WORKFLOW_ID = new ObjectId("60245b56226920beece547e3");
  private static final ObjectId CUSTOMIZATIONS_ID = new ObjectId("62b0f1f5a6166d30af05fa5d");

  /** Legacy {@code 4020}'s task/controller config-key renames. */
  private static final Map<String, String> TASK_CONFIG_RENAMES =
      Map.of(
          "job.deletion.policy", "deletion.policy",
          "enable.tasks", "edit.verified",
          "task.timeout.configuration", "default.timeout",
          "worker.image", "default.image",
          "enable.debug", "debug");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — settings/global parameters already in v5 shape.");
      return;
    }
    migrateSettings(db, names);
    migrateGlobalParameters(db, names);
  }

  // =====================================================================================
  // Settings
  // =====================================================================================

  private void migrateSettings(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));

    long deletedUsers = settings.deleteOne(Filters.eq("_id", USERS_ID)).getDeletedCount();

    migrateActivity(settings);
    migrateTask(settings);
    migrateIntegration(settings);
    migrateTeams(settings);
    migrateFeatures(settings);
    stripLegacyClass(settings, WORKFLOW_ID);
    stripLegacyClass(settings, CUSTOMIZATIONS_ID);

    LOG.info(
        "v3 settings migrated to v5 shape — {} documents remain (User Defaults deleted: {})",
        settings.countDocuments(),
        deletedUsers > 0);
  }

  /** {@code 4020}: Workspace Configuration - Activity Storage -> {@code workflowrun}. */
  private void migrateActivity(MongoCollection<Document> settings) {
    Document doc = settings.find(Filters.eq("_id", ACTIVITY_ID)).first();
    if (doc == null) {
      return;
    }
    doc.put("key", "workflowrun");
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", ACTIVITY_ID), doc);
  }

  /** {@code 4020}: Task Configuration -> {@code task}, plus its config-key renames. */
  private void migrateTask(MongoCollection<Document> settings) {
    Document doc = settings.find(Filters.eq("_id", CONTROLLER_ID)).first();
    if (doc == null) {
      return;
    }
    doc.put("key", "task");
    renameConfigKeys(configOf(doc), TASK_CONFIG_RENAMES);
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", CONTROLLER_ID), doc);
  }

  /** {@code 4025}+{@code 4037}: Extensions Configuration -> Integration Configuration. */
  private void migrateIntegration(MongoCollection<Document> settings) {
    Document doc = settings.find(Filters.eq("_id", EXTENSIONS_ID)).first();
    if (doc == null) {
      return;
    }
    doc.put("key", "integration");
    doc.put("name", "Integration Configuration");
    List<Document> config = configOf(doc);
    addConfigIfAbsent(config, "github.appId", "The GitHub App ID", "GitHub App ID", "text", "");
    addConfigIfAbsent(
        config,
        "github.pem",
        "Private key used to sign access token requests",
        "GitHub Private Key",
        "secured",
        "");
    addConfigIfAbsent(
        config, "github.appName", "The GitHub App Name", "GitHub App Name", "text", "");
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", EXTENSIONS_ID), doc);
  }

  /** {@code 4039}: Team Defaults -> Team Quotas, quota key renames/relabels, storage addition. */
  private void migrateTeams(MongoCollection<Document> settings) {
    Document doc = settings.find(Filters.eq("_id", TEAMS_ID)).first();
    if (doc == null) {
      return;
    }
    doc.put("name", "Team Quotas");
    doc.put(
        "description",
        "Define default team quotas which are referenced unless overridden on the Team.");
    List<Document> config = configOf(doc);
    renameConfigKey(
        config, "max.team.concurrent.workflows", "max.workflowrun.concurrent", null, null);
    renameConfigKey(config, "max.team.workflow.count", "max.workflow.count", null, null);
    renameConfigKey(
        config,
        "max.team.workflow.execution.monthly",
        "max.workflowrun.monthly",
        "Max WorkflowRuns per month",
        null);
    renameConfigKey(
        config,
        "max.team.workflow.duration",
        "max.workflowrun.duration",
        "Max WorkflowRun Duration",
        null);
    renameConfigKey(
        config,
        "max.team.workflow.storage",
        "max.workflow.storage",
        "Max Workflow Storage",
        "Maximum storage allowed per Workflow across runs (executions)");
    addConfigIfAbsent(
        config,
        "max.workflowrun.storage",
        "Maximum storage allowed per WorkflowRun (execution)",
        "Max WorkflowRun Storage",
        "text",
        "2Gi");
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", TEAMS_ID), doc);
  }

  /** {@code 4040}: {@code workflowQuotas} -> {@code teamQuotas}. */
  private void migrateFeatures(MongoCollection<Document> settings) {
    Document doc = settings.find(Filters.eq("_id", FEATURES_ID)).first();
    if (doc == null) {
      return;
    }
    renameConfigKey(
        configOf(doc), "workflowQuotas", "teamQuotas", "Team Quotas", "Enforce Team level quotas");
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", FEATURES_ID), doc);
  }

  /** Documents whose key/config never changes (workflow, customizations) still need decontamination. */
  private void stripLegacyClass(MongoCollection<Document> settings, ObjectId id) {
    Document doc = settings.find(Filters.eq("_id", id)).first();
    if (doc == null || !doc.containsKey("_class")) {
      return;
    }
    doc.remove("_class");
    settings.replaceOne(Filters.eq("_id", id), doc);
  }

  @SuppressWarnings("unchecked")
  private List<Document> configOf(Document doc) {
    return (List<Document>) doc.get("config");
  }

  private void renameConfigKeys(List<Document> config, Map<String, String> renames) {
    if (config == null) {
      return;
    }
    for (Document entry : config) {
      String renamed = renames.get(entry.getString("key"));
      if (renamed != null) {
        entry.put("key", renamed);
      }
    }
  }

  private void renameConfigKey(
      List<Document> config,
      String oldKey,
      String newKey,
      String newLabel,
      String newDescription) {
    if (config == null) {
      return;
    }
    for (Document entry : config) {
      if (oldKey.equals(entry.getString("key"))) {
        entry.put("key", newKey);
        if (newLabel != null) {
          entry.put("label", newLabel);
        }
        if (newDescription != null) {
          entry.put("description", newDescription);
        }
        return;
      }
    }
  }

  private void addConfigIfAbsent(
      List<Document> config,
      String key,
      String description,
      String label,
      String type,
      String value) {
    if (config == null) {
      return;
    }
    boolean present = config.stream().anyMatch(entry -> key.equals(entry.getString("key")));
    if (present) {
      return;
    }
    config.add(
        new Document("description", description)
            .append("key", key)
            .append("label", label)
            .append("type", type)
            .append("value", value)
            .append("readOnly", false));
  }

  // =====================================================================================
  // Global parameters
  // =====================================================================================

  private void migrateGlobalParameters(MongoDatabase db, CollectionNames names) {
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
    // Settings carry operator-configured values, and the source collections are dropped once
    // migrated, once an install is live - forward-only, matching the other v3-only online
    // migrations in this chain.
  }
}

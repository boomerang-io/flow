package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds artifacts: the {@code artifacts} collection's indexes, the {@code artifacts} settings
 * document (default and maximum retention, largest artifact) and the {@code max.artifact.storage}
 * default on the quota document (keyed {@code workspaces} here, {@code quotas} from {@code _0055}).
 *
 * <p>Indexes: {@code run_name_idx} is unique and is what refuses a second artifact of the same name
 * in a run; {@code status_expiration_idx} serves the expiry and stale-upload sweeps; {@code
 * workflow_status_idx} serves the workspace list, the storage total and the workflow prune.
 *
 * <p>Settings: a fresh install gets both from {@code seed/settings.json} through {@code _0021}; an
 * install that ran {@code _0021} earlier gets them here.
 *
 * <p>Idempotent: the document is inserted only when absent by {@code _id} or key, and the quota
 * entry is appended only when the quota document does not already carry it.
 */
@Change(id = "0051-artifacts", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0051__Artifacts {

  static final String COLLECTION = "artifacts";
  static final String RUN_NAME_INDEX = "run_name_idx";
  static final String STATUS_EXPIRATION_INDEX = "status_expiration_idx";
  static final String WORKFLOW_STATUS_INDEX = "workflow_status_idx";

  private static final Logger LOG = LoggerFactory.getLogger(_0051__Artifacts.class);

  static final String QUOTA_KEY = "max.artifact.storage";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String artifactsCollection = names.resolve(COLLECTION);
    ensureIndexKeys(
        db,
        artifactsCollection,
        RUN_NAME_INDEX,
        new Document("workflowRunRef", 1).append("name", 1),
        new IndexOptions().unique(true));
    ensureIndexKeys(
        db,
        artifactsCollection,
        STATUS_EXPIRATION_INDEX,
        new Document("status", 1).append("expirationDate", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        artifactsCollection,
        WORKFLOW_STATUS_INDEX,
        new Document("workflowRef", 1).append("status", 1),
        new IndexOptions());

    List<Document> seed = SeedResources.load("seed/settings.json");
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));

    Document artifacts = seedDocument(seed, "artifacts");
    boolean inserted =
        SeedResources.insertIfAbsent(
            db,
            names.resolve("settings"),
            Filters.or(Filters.eq("_id", artifacts.get("_id")), Filters.eq("key", "artifacts")),
            artifacts);
    SeedResources.logSeeded("settings(artifacts)", inserted ? 1 : 0, 1);

    // The quota defaults are keyed "workspaces" until _0055 renames them "quotas"; a fresh install
    // already has the seed's "quotas" document by the time this unit runs.
    Document quotas = settings.find(Filters.in("key", "workspaces", "quotas")).first();
    if (quotas == null) {
      LOG.warn("No quota settings document found - no artifact storage quota to add.");
      return;
    }
    List<Document> existing = quotas.getList("config", Document.class);
    List<Document> config = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
    if (config.stream().anyMatch(entry -> QUOTA_KEY.equals(entry.getString("key")))) {
      LOG.info("{} already present - nothing to add.", QUOTA_KEY);
      return;
    }
    config.add(
        seedDocument(seed, "quotas").getList("config", Document.class).stream()
            .filter(entry -> QUOTA_KEY.equals(entry.getString("key")))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "seed/settings.json 'quotas' is missing " + QUOTA_KEY)));
    settings.updateOne(Filters.eq("_id", quotas.get("_id")), Updates.set("config", config));
    LOG.info("Added {} to the quota defaults.", QUOTA_KEY);
  }

  private static Document seedDocument(List<Document> seed, String key) {
    return seed.stream()
        .filter(document -> key.equals(document.getString("key")))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("seed/settings.json is missing the '" + key + "' document"));
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Settings carry operator-configured values once an install is live - forward-only, matching
    // the other settings units in this chain.
    String artifactsCollection = names.resolve(COLLECTION);
    dropIndex(db, artifactsCollection, RUN_NAME_INDEX);
    dropIndex(db, artifactsCollection, STATUS_EXPIRATION_INDEX);
    dropIndex(db, artifactsCollection, WORKFLOW_STATUS_INDEX);
  }
}

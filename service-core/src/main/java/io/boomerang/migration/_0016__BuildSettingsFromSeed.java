package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Build every settings document from {@code seed/settings.json}, so an empty database and an
 * upgraded v3 database both end with exactly the seed's documents, keys, wording and types.
 *
 * <p>A seed document replaces the stored document with its {@code _id}, its key or an earlier key,
 * keeping the stored {@code _id}; with no such document it is inserted. Each seed entry takes the
 * value stored under its key or an earlier key, except a value stored as another type (secured
 * values are encrypted at rest and decrypted by type), a value an earlier release shipped and no
 * longer means anything, and a choice that is no longer an option: those take the seed value, and
 * the change is logged. Stored entries the seed does not define are kept after the seed's and
 * logged; retired entries, the v3 {@code _class} and the v3 User Defaults and workflow storage
 * documents are removed.
 *
 * <p>Idempotent: rebuilding a document that was built from the seed writes the same document.
 */
@Change(id = "0016-build-settings-from-seed", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0016__BuildSettingsFromSeed {

  private static final Logger LOG = LoggerFactory.getLogger(_0016__BuildSettingsFromSeed.class);

  /** Earlier keys of a settings document, newest first. */
  private static final Map<String, List<String>> EARLIER_DOCUMENT_KEYS =
      Map.of(
          "task", List.of("controller"),
          "workflowrun", List.of("activity"),
          "integration", List.of("extensions"),
          "quotas", List.of("workspaces", "teams"));

  /** Earlier keys of a settings entry, newest first. */
  private static final Map<String, List<String>> EARLIER_ENTRY_KEYS =
      Map.ofEntries(
          Map.entry("debug", List.of("enable.debug")),
          Map.entry("default.image", List.of("worker.image")),
          Map.entry("deletion.policy", List.of("job.deletion.policy")),
          Map.entry("edit.verified", List.of("enable.tasks")),
          Map.entry("default.timeout", List.of("task.timeout.configuration")),
          Map.entry("github.jwt", List.of("github.pem")),
          Map.entry("max.workflowrun.concurrent", List.of("max.team.concurrent.workflows")),
          Map.entry("max.workflow.count", List.of("max.team.workflow.count")),
          Map.entry("max.workflowrun.monthly", List.of("max.team.workflow.execution.monthly")),
          Map.entry("max.workflowrun.duration", List.of("max.team.workflow.duration")),
          Map.entry("max.workflow.storage", List.of("max.team.workflow.storage")),
          Map.entry("workspaceQuotas", List.of("teamQuotas", "workflowQuotas")),
          Map.entry("workspaceParameters", List.of("teamParameters")),
          Map.entry("workspaceManagement", List.of("teamManagement")),
          Map.entry("workspaceTasks", List.of("teamTasks")));

  /**
   * Values an install holds only because an earlier release shipped them: the 90-minute task
   * ceiling, now off by default, and images of the retired worker-flow lineage.
   */
  private static final Map<String, Predicate<Object>> RETIRED_VALUES =
      Map.of(
          "default.timeout", "90"::equals,
          "default.image", value -> String.valueOf(value).contains("worker-flow"));

  /** Entries nothing reads any more. */
  private static final Set<String> RETIRED_ENTRIES =
      Set.of("storage.size", "storage.class", "storage.accessMode", "max.storage.size");

  /** The v3 User Defaults and workflow storage documents, which nothing reads any more. */
  private static final ObjectId USER_DEFAULTS_ID = new ObjectId("6123c1e20b07a54cdce637c0");

  private static final ObjectId WORKFLOW_STORAGE_ID = new ObjectId("60245b56226920beece547e3");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    List<Document> seed = SeedResources.load("seed/settings.json");
    int inserted = 0;
    for (Document seedDocument : seed) {
      Document stored = stored(settings, seedDocument);
      if (stored == null) {
        settings.insertOne(seedDocument);
        inserted++;
      } else {
        settings.replaceOne(Filters.eq("_id", stored.get("_id")), rebuild(seedDocument, stored));
      }
    }
    long retired =
        settings
            .deleteMany(
                Filters.or(
                    Filters.in("_id", USER_DEFAULTS_ID, WORKFLOW_STORAGE_ID),
                    Filters.eq("key", "workflow")))
            .getDeletedCount();
    LOG.info(
        "Settings built from the seed - {} inserted, {} rebuilt, {} retired document(s) deleted",
        inserted,
        seed.size() - inserted,
        retired);
  }

  /** The stored document with the seed's {@code _id}, else with its key or an earlier key. */
  private static Document stored(MongoCollection<Document> settings, Document seedDocument) {
    Object id = seedDocument.get("_id");
    Document byId = (id != null) ? settings.find(Filters.eq("_id", id)).first() : null;
    if (byId != null) {
      return byId;
    }
    return withEarlierKeys(seedDocument.getString("key"), EARLIER_DOCUMENT_KEYS)
        .map(key -> settings.find(Filters.eq("key", key)).first())
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** The seed document under the stored {@code _id}, holding the stored values. */
  private static Document rebuild(Document seedDocument, Document stored) {
    String documentKey = seedDocument.getString("key");
    Map<String, Document> storedEntries = new LinkedHashMap<>();
    configOf(stored).forEach(entry -> storedEntries.put(entry.getString("key"), entry));

    Set<String> known = new HashSet<>(RETIRED_ENTRIES);
    List<Document> config = new ArrayList<>();
    for (Document seedEntry : configOf(seedDocument)) {
      List<String> keys = withEarlierKeys(seedEntry.getString("key"), EARLIER_ENTRY_KEYS).toList();
      known.addAll(keys);
      Document entry = new Document(seedEntry);
      keys.stream()
          .map(storedEntries::get)
          .filter(Objects::nonNull)
          .findFirst()
          .ifPresent(previous -> entry.put("value", carriedValue(documentKey, seedEntry, previous)));
      config.add(entry);
    }
    storedEntries.forEach(
        (key, entry) -> {
          if (!known.contains(key)) {
            LOG.info("Settings '{}' keeps '{}', which the seed does not define", documentKey, key);
            config.add(entry);
          }
        });

    Document rebuilt = new Document(seedDocument);
    rebuilt.put("_id", stored.get("_id"));
    rebuilt.put("config", config);
    return rebuilt;
  }

  /**
   * The stored value, unless it was stored as another type, is a retired value or is not one of
   * the entry's options; an empty value carries whatever its type.
   */
  private static Object carriedValue(String documentKey, Document seedEntry, Document previous) {
    String key = seedEntry.getString("key");
    Object value = previous.get("value");
    Object seedValue = seedEntry.get("value");
    if (value == null) {
      return seedValue;
    }
    // Compared as SettingsService reads it: case-insensitively.
    if (!"".equals(value)
        && !String.valueOf(previous.get("type")).equalsIgnoreCase(seedEntry.getString("type"))) {
      LOG.warn(
          "Settings '{}' entry '{}' was stored as type '{}', not '{}' - reset to the default",
          documentKey,
          key,
          previous.get("type"),
          seedEntry.get("type"));
      return seedValue;
    }
    if (RETIRED_VALUES.getOrDefault(key, retired -> false).test(value)
        || !isOption(seedEntry, value)) {
      LOG.info(
          "Settings '{}' entry '{}' moved from '{}' to '{}'", documentKey, key, value, seedValue);
      return seedValue;
    }
    return value;
  }

  /** True unless the entry is a choice and {@code value} is not one of its options. */
  private static boolean isOption(Document seedEntry, Object value) {
    List<Document> options = seedEntry.getList("options", Document.class);
    return (options == null)
        || options.stream().anyMatch(option -> Objects.equals(option.get("key"), value));
  }

  private static Stream<String> withEarlierKeys(String key, Map<String, List<String>> earlier) {
    return Stream.concat(Stream.of(key), earlier.getOrDefault(key, List.of()).stream());
  }

  private static List<Document> configOf(Document setting) {
    List<Document> config = setting.getList("config", Document.class);
    return (config != null) ? config : List.of();
  }

  @Rollback
  public void rollback() {
    // Settings carry operator-configured values once an install is live - forward-only.
  }
}

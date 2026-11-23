package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the two settings of the workspace run queue to an install whose settings were built before
 * the seed carried them: the {@code quotas} entry {@code max.workflowrun.queued}, how many runs may
 * wait for a slot, and the {@code workflowrun} entry {@code queue.max.age}, the hours one may wait.
 * Each entry is the seed's own, so an empty database and an upgraded one hold the same default; an
 * install that already has a key is left as it is.
 */
@Change(id = "0051-seed-run-queue-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0051__SeedRunQueueSettings {

  private static final Logger LOG = LoggerFactory.getLogger(_0051__SeedRunQueueSettings.class);

  // Settings group to the key added to it.
  static final Map<String, String> KEYS =
      Map.of("quotas", "max.workflowrun.queued", "workflowrun", "queue.max.age");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    KEYS.forEach(
        (group, key) -> {
          MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
          long added =
              settings
                  .updateOne(
                      Filters.and(Filters.eq("key", group), Filters.ne("config.key", key)),
                      Updates.push("config", seedEntry(group, key)))
                  .getModifiedCount();
          if (added > 0) {
            LOG.info("Added the {} setting {}", group, key);
          } else if (settings.countDocuments(Filters.eq("key", group)) > 0) {
            LOG.info("The {} setting {} is already present", group, key);
          } else {
            LOG.warn("No {} settings document to add {} to; the setting reads as its default", group, key);
          }
        });
  }

  // The seed's entry, so there is one definition of the setting.
  static Document seedEntry(String group, String key) {
    List<Document> seed = SeedResources.load("seed/settings.json");
    for (Document document : seed) {
      if (group.equals(document.getString("key"))) {
        for (Document config : document.getList("config", Document.class)) {
          if (key.equals(config.getString("key"))) {
            return config;
          }
        }
      }
    }
    throw new IllegalStateException("seed/settings.json has no " + group + "." + key);
  }

  @Rollback
  public void rollback() {
    // Forward-only, like every other settings unit in this chain.
  }
}

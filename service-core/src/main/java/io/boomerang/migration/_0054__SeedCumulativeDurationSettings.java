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
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the two {@code quotas} settings of the cumulative duration quota to an install whose settings
 * were built before the seed carried them: {@code max.workflowrun.cumulative.duration}, the minutes
 * of task time a workspace may use in a month ({@code 0}, unlimited), and {@code
 * unmetered.workflows}, the system workspace's workflows whose runs use none of the monthly quotas
 * (none). Each entry is the seed's own, so an empty database and an upgraded one hold the same
 * default; an install that already has a key is left as it is.
 */
@Change(id = "0054-seed-cumulative-duration-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0054__SeedCumulativeDurationSettings {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0054__SeedCumulativeDurationSettings.class);

  static final String GROUP = "quotas";
  static final List<String> KEYS =
      List.of("max.workflowrun.cumulative.duration", "unmetered.workflows");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    MongoCollection<Document> settings =
        db.getCollection(CollectionNames.from(env).resolve("settings"));
    for (String key : KEYS) {
      long added =
          settings
              .updateOne(
                  Filters.and(Filters.eq("key", GROUP), Filters.ne("config.key", key)),
                  Updates.push("config", _0051__SeedRunQueueSettings.seedEntry(GROUP, key)))
              .getModifiedCount();
      if (added > 0) {
        LOG.info("Added the {} setting {}", GROUP, key);
      } else if (settings.countDocuments(Filters.eq("key", GROUP)) > 0) {
        LOG.info("The {} setting {} is already present", GROUP, key);
      } else {
        LOG.warn(
            "No {} settings document to add {} to; the setting reads as its default", GROUP, key);
      }
    }
  }

  @Rollback
  public void rollback() {
    // Forward-only, like every other settings unit in this chain.
  }
}

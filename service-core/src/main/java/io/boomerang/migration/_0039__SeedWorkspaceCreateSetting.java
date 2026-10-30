package io.boomerang.migration;

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
 * Adds the {@code features} setting {@code workspaceCreate}, whether a user who is not an admin may
 * create a workspace, to an install whose settings were built before the seed carried it. The entry
 * is the seed's own, so an empty database and an upgraded one hold the same default; an install that
 * already has the key is left as it is.
 */
@Change(id = "0039-seed-workspace-create-setting", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0039__SeedWorkspaceCreateSetting {

  private static final Logger LOG = LoggerFactory.getLogger(_0039__SeedWorkspaceCreateSetting.class);
  static final String KEY = "workspaceCreate";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    Document entry = seedEntry();
    long added =
        db.getCollection(names.resolve("settings"))
            .updateOne(
                Filters.and(Filters.eq("key", "features"), Filters.ne("config.key", KEY)),
                Updates.push("config", entry))
            .getModifiedCount();
    LOG.info(added > 0 ? "Added the features setting {}" : "The features setting {} is already present", KEY);
  }

  // The seed's entry, so there is one definition of the setting.
  static Document seedEntry() {
    List<Document> seed = SeedResources.load("seed/settings.json");
    for (Document document : seed) {
      if ("features".equals(document.getString("key"))) {
        for (Document config : document.getList("config", Document.class)) {
          if (KEY.equals(config.getString("key"))) {
            return config;
          }
        }
      }
    }
    throw new IllegalStateException("seed/settings.json has no features." + KEY);
  }

  @Rollback
  public void rollback() {
    // Forward-only, like every other settings unit in this chain.
  }
}

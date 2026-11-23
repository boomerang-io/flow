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
import org.bson.BsonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Gives the {@code system} workspace no cumulative duration limit, {@code
 * quotas.maxCumulativeDuration: 0}, as {@code seed/workspace.json} does for an empty database, so
 * an embedded engine's workspace is never held to one by the platform default. A value already
 * stored, an operator's own limit included, is left as it is.
 */
@Change(id = "0055-system-workspace-unmetered-duration", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0055__SystemWorkspaceUnmeteredDuration {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0055__SystemWorkspaceUnmeteredDuration.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    long updated =
        db.getCollection(CollectionNames.from(env).resolve("workspaces"))
            .updateOne(
                Filters.and(
                    Filters.eq("name", "system"),
                    Filters.type("quotas", BsonType.DOCUMENT),
                    Filters.exists("quotas.maxCumulativeDuration", false)),
                Updates.set("quotas.maxCumulativeDuration", 0))
            .getModifiedCount();
    LOG.info(
        updated > 0
            ? "The system workspace has no cumulative duration limit"
            : "The system workspace stores a cumulative duration limit already, or has no quotas"
                + " document; left as it is");
  }

  @Rollback
  public void rollback() {
    // Forward-only, like every other unit in this chain.
  }
}

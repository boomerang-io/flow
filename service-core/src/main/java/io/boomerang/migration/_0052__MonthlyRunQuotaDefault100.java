package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
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
 * Moves the {@code quotas} settings' {@code max.workflowrun.monthly}, the runs a workspace may
 * submit in a month unless it stores its own limit, from {@code 20} to {@code 100}, where an install
 * still holds the value an earlier seed set. A value an operator chose is left as it is, and so is
 * every workspace's own stored quota.
 *
 * <p>An empty database has {@code 100} from {@code seed/settings.json} through {@link
 * _0016__BuildSettingsFromSeed}, and this unit then finds nothing to change.
 */
@Change(id = "0052-monthly-run-quota-default-100", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0052__MonthlyRunQuotaDefault100 {

  private static final Logger LOG = LoggerFactory.getLogger(_0052__MonthlyRunQuotaDefault100.class);

  static final String PREVIOUS_DEFAULT = "20";
  static final String DEFAULT = "100";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    long updated =
        db.getCollection(names.resolve("settings"))
            .updateOne(
                Filters.eq("key", "quotas"),
                Updates.set("config.$[entry].value", DEFAULT),
                new UpdateOptions()
                    .arrayFilters(
                        List.of(
                            new Document("entry.key", "max.workflowrun.monthly")
                                .append("entry.value", PREVIOUS_DEFAULT))))
            .getModifiedCount();
    LOG.info(
        updated > 0
            ? "Moved the default monthly run quota from {} to {}"
            : "The default monthly run quota is not {}; left as it is, not moved to {}",
        PREVIOUS_DEFAULT,
        DEFAULT);
  }

  @Rollback
  public void rollback() {
    // Runs admitted since may already rely on the larger quota - forward-only, like every other
    // settings unit in this chain.
  }
}

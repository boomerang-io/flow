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
 * Moves the {@code task} settings' {@code default.image}, the image a catalogue task without one of
 * its own runs on, from {@code boomerangio/task-flow:3.1.0} to {@code 3.3.0}, where an install
 * still holds the value an earlier seed set. A value an operator chose is left as it is.
 *
 * <p>An empty database and a v3 upgrade have {@code 3.3.0} from {@code seed/settings.json} through
 * {@link _0016__BuildSettingsFromSeed}, and this unit then finds nothing to change.
 */
@Change(id = "0036-default-task-image-task-flow-3-3", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0036__DefaultTaskImageTaskFlow33 {

  private static final Logger LOG = LoggerFactory.getLogger(_0036__DefaultTaskImageTaskFlow33.class);

  static final String PREVIOUS_IMAGE = "boomerangio/task-flow:3.1.0";
  static final String IMAGE = "boomerangio/task-flow:3.3.0";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    long updated =
        db.getCollection(names.resolve("settings"))
            .updateOne(
                Filters.eq("key", "task"),
                Updates.set("config.$[entry].value", IMAGE),
                new UpdateOptions()
                    .arrayFilters(
                        List.of(
                            new Document("entry.key", "default.image")
                                .append("entry.value", PREVIOUS_IMAGE))))
            .getModifiedCount();
    LOG.info(
        updated > 0
            ? "Moved the default task image from {} to {}"
            : "The default task image is not {}; left as it is, not moved to {}",
        PREVIOUS_IMAGE,
        IMAGE);
  }

  @Rollback
  public void rollback() {
    // Task runs created since may already have taken the new image - forward-only, like every other
    // settings unit in this chain.
  }
}

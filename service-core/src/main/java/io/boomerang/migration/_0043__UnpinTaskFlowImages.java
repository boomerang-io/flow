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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Removes the image from every task revision that pins a {@code boomerangio/task-flow} version, so
 * each runs on the {@code task} settings' {@code default.image} and follows it when the operator
 * moves it. A revision on any other image (the AI tasks, Box, the Python worker) keeps its own: the
 * default stands in for task-flow alone.
 */
@Change(id = "0043-unpin-task-flow-images", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0043__UnpinTaskFlowImages {

  private static final Logger LOG = LoggerFactory.getLogger(_0043__UnpinTaskFlowImages.class);
  static final Pattern TASK_FLOW_IMAGE = Pattern.compile("^boomerangio/task-flow:");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    long unpinned =
        db.getCollection(names.resolve("task_revisions"))
            .updateMany(Filters.regex("spec.image", TASK_FLOW_IMAGE), Updates.unset("spec.image"))
            .getModifiedCount();
    LOG.info("Unpinned the task-flow image from {} task revision(s)", unpinned);
  }

  @Rollback
  public void rollback() {
    // Forward-only: the pinned versions are not kept.
  }
}

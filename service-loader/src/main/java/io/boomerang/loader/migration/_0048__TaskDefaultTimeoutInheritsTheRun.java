package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turn the {@code task} / {@code default.timeout} setting from a shipped 90-minute per-task
 * default into an optional operator ceiling that is off by default.
 *
 * <p>Two timeout guards exist and both are wanted: the per-task one becomes the pod's {@code
 * activeDeadlineSeconds} and the watcher's per-task reap, and the run one kills the whole graph.
 * Decision 0063 requires the outer guard to cover the work inside it, but the seed shipped the
 * task value at 90 minutes while the run-duration quota ships at 30 - so out of the box a task
 * believed it had three times the budget of the run containing it, and the run guard reaped
 * healthy work. {@code DAGUtility.createTaskList} now clamps every task's timeout to its run's,
 * which fixes the arithmetic; this unit fixes the value, because a per-task default that only
 * ever acts when it happens to be smaller than the run is a knob that does nothing and still has
 * to be explained. Seeded at 0 the setting imposes no ceiling and a task simply inherits its
 * run's timeout; an operator who wants a hard per-pod limit in a long-running graph sets a
 * number, and that number is then the first of the three ceilings.
 *
 * <p>0, not empty: the settings screen renders this entry as a {@code number} input whose
 * generated validation rejects a blank value ({@code yup.number}, "Enter a number"), which would
 * disable Save for the whole task settings group until an admin typed something. 0 is the value
 * the engine already reads as "unguarded" ({@code DAGUtility}'s {@code : 0L} branch and its
 * {@code timeout <= 0} comparisons), so it means exactly the same thing to the execution path
 * without breaking the screen.
 *
 * <p>A fresh install gets 0 from the updated {@code seed/settings.json} via {@code
 * _0021__SeedSettings}. An install that already ran {@code _0021} keeps whatever is in its
 * database, so this unit rewrites it - but only when it is still the shipped {@code 90}. Any
 * other number is an operator's own choice (including a deliberate 90 re-entered by hand, which
 * is indistinguishable from the seed and is knowingly rewritten) and is left alone. The {@code
 * label} and {@code description} carry no operator choice, so they are brought to the seed's
 * wording either way - the same lock-step rewording {@code _0032} and {@code _0034} do - and
 * {@code key}, {@code type} and {@code readOnly} are untouched.
 *
 * <p>Ungated: the clamp is v5 behaviour on every generation, so there is no install for which
 * this value is right. Idempotent: after the first run the value is 0, which is not 90, so a
 * second run rewrites nothing.
 */
@Change(id = "0048-task-default-timeout-inherits-the-run", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0048__TaskDefaultTimeoutInheritsTheRun {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0048__TaskDefaultTimeoutInheritsTheRun.class);

  static final String TASK_SETTINGS_KEY = "task";
  static final String CONFIG_KEY = "default.timeout";
  /** The value the seed shipped, and the only one this unit replaces. */
  static final String SEEDED_VALUE = "90";

  static final String NEW_VALUE = "0";
  static final String NEW_LABEL = "Maximum task duration";
  static final String NEW_DESCRIPTION =
      "Maximum minutes any single task may run, whatever its run allows. Set to 0 to let tasks"
          + " inherit the run's timeout.";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document task = settings.find(Filters.eq("key", TASK_SETTINGS_KEY)).first();
    if (task == null) {
      LOG.info("No '{}' settings document - nothing to update.", TASK_SETTINGS_KEY);
      return;
    }
    Document entry = configEntry(task);
    if (entry == null) {
      LOG.info("'{}' settings carry no '{}' entry - nothing to update.", TASK_SETTINGS_KEY, CONFIG_KEY);
      return;
    }

    // Display fields first: they never carry an operator choice, so they track the seed whatever
    // the value turns out to be.
    settings.updateOne(
        Filters.and(Filters.eq("_id", task.get("_id")), Filters.eq("config.key", CONFIG_KEY)),
        Updates.combine(
            Updates.set("config.$.label", NEW_LABEL),
            Updates.set("config.$.description", NEW_DESCRIPTION)));

    String current = entry.getString("value");
    if (!SEEDED_VALUE.equals(current)) {
      LOG.info(
          "Task '{}' is '{}', not the seeded '{}' - left as the operator set it.",
          CONFIG_KEY,
          current,
          SEEDED_VALUE);
      return;
    }
    settings.updateOne(
        Filters.and(Filters.eq("_id", task.get("_id")), Filters.eq("config.key", CONFIG_KEY)),
        Updates.set("config.$.value", NEW_VALUE));
    LOG.info(
        "Task '{}' moved from the seeded '{}' to '{}' - tasks now inherit their run's timeout.",
        CONFIG_KEY,
        SEEDED_VALUE,
        NEW_VALUE);
  }

  @Rollback
  public void rollback() {
    // Settings carry operator-configured values once an install is live - forward-only, matching
    // the other settings units in this chain. Restoring 90 would also restore the inequality
    // decision 0063 forbids.
  }

  @SuppressWarnings("unchecked")
  private static Document configEntry(Document task) {
    Object config = task.get("config");
    if (!(config instanceof List)) {
      return null;
    }
    for (Document entry : (List<Document>) config) {
      if (CONFIG_KEY.equals(entry.getString("key"))) {
        return entry;
      }
    }
    return null;
  }
}

package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds {@code default.timeout} to the {@code workflowrun} settings document: the timeout in minutes
 * a WorkflowRun is created with when neither the submit request nor the Workflow revision declares
 * one.
 *
 * <p>Submit validates the run timeout against the revision's task budgets, and a run with no
 * timeout at all cannot be validated - it is simply unguarded. Seeded at 30, matching the
 * {@code max.workflowrun.duration} quota default, so an install's behaviour is unchanged on the
 * day it upgrades: a run with no declared timeout used to be created at the run-duration ceiling.
 *
 * <p>Set-if-absent and idempotent: an install that already carries the key (a fresh install, whose
 * seed already contains it, or a second run of this unit) is matched out by the {@code config.key}
 * guard and left alone, so an operator's own value is never overwritten.
 */
@Change(id = "0045-default-run-timeout-setting", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0045__DefaultRunTimeoutSetting {

  private static final Logger LOG = LoggerFactory.getLogger(_0045__DefaultRunTimeoutSetting.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    Document config =
        new Document("key", "default.timeout")
            .append("label", "Default Run Timeout")
            .append(
                "description",
                "The timeout in minutes applied to a WorkflowRun when neither the request nor the"
                    + " Workflow declares one. 0 leaves such a run unguarded.")
            .append("type", "number")
            .append("value", "30")
            .append("readOnly", false);

    long added =
        db.getCollection(names.resolve("settings"))
            .updateOne(
                Filters.and(
                    Filters.eq("key", "workflowrun"),
                    Filters.ne("config.key", "default.timeout")),
                Updates.push("config", config))
            .getModifiedCount();
    LOG.info("Added workflowrun.default.timeout to {} settings document(s)", added);
  }

  @Rollback
  public void rollback() {
    // Settings carry operator-configured values once an install is live - forward-only.
  }
}

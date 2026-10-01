package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites the text of the {@code task} settings' {@code deletion.policy} so an admin can see what
 * each choice does: "Never" does not keep a worker forever, it leaves it (with its logs and any run
 * storage it holds) to the retention period, and "Always" frees storage at task end at the cost of
 * the task's logs.
 *
 * <p>Only the description and the option labels change. The selected value is the admin's choice
 * and is left alone; a fresh install gets "Always" from {@code seed/settings.json}. Idempotent: it
 * overwrites the same text on every run, and a document without the key is left untouched.
 */
@Change(id = "0050-describe-task-deletion-policy", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0050__DescribeTaskDeletionPolicy {

  private static final Logger LOG = LoggerFactory.getLogger(_0050__DescribeTaskDeletionPolicy.class);

  static final String SETTING_KEY = "deletion.policy";

  static final String DESCRIPTION =
      "When a finished task's worker (its Kubernetes Job or Tekton TaskRun) is removed. A task's"
          + " logs are read from its worker, so they go with it, and a run's storage is only freed"
          + " once no worker still holds it. Always: at task end. On Success: at task end if the"
          + " task succeeded; a failed task's worker stays for its logs. Never: not at task end;"
          + " the worker, its logs and any storage it holds stay until the retention period"
          + " (kube.task.ttlDays, 7 days by default) removes it.";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document task = settings.find(Filters.eq("key", "task")).first();
    if (task == null) {
      LOG.warn("No 'task' settings document found - nothing to describe.");
      return;
    }
    List<Document> existingConfig = task.getList("config", Document.class);
    if (existingConfig == null
        || existingConfig.stream().noneMatch(c -> SETTING_KEY.equals(c.getString("key")))) {
      LOG.info("{} absent - nothing to describe.", SETTING_KEY);
      return;
    }
    List<Document> config = new ArrayList<>();
    for (Document entry : existingConfig) {
      if (SETTING_KEY.equals(entry.getString("key"))) {
        entry.put("description", DESCRIPTION);
        entry.put("options", options());
      }
      config.add(entry);
    }
    settings.updateOne(Filters.eq("_id", task.get("_id")), Updates.set("config", config));
    LOG.info("Described {} on the existing 'task' document.", SETTING_KEY);
  }

  static List<Document> options() {
    return List.of(
        new Document("key", "Never").append("value", "Never (keep until the retention period)"),
        new Document("key", "OnSuccess").append("value", "On Success (remove when a task succeeds)"),
        new Document("key", "Always").append("value", "Always (remove when a task ends)"));
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Text only, on a document an operator may have edited since - forward-only, matching the
    // other settings units in this chain.
  }
}

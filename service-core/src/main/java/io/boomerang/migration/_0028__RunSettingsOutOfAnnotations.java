package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Removes the run annotations the engine used to decide from. A run's workspace is now its
 * workflow's owner in the relationship graph, and a TaskRun takes the task settings when it is
 * created, so the runs lose {@code boomerang.io/workspace-name}, {@code task-timeout}, {@code
 * task-default-image} and {@code task-deletion}, and the {@code global-params}, {@code
 * workspace-params} and {@code context-params} keys older runs still carry. A TaskRun loses its
 * copy of the workspace name; one pre-approved by an early event keeps that status as {@code
 * preApprovedStatus} in place of its {@code preApproved} flag and status annotation. A held task
 * lock keyed {@code <workspace name>:<key>} is re-keyed {@code <workspace id>:<key>}, which is how a
 * lock is scoped now. Map keys are stored with their dots as {@code #}. An empty or a v3 database
 * holds none of these, and a second run finds none.
 */
@Change(id = "0028-run-settings-out-of-annotations", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0028__RunSettingsOutOfAnnotations {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0028__RunSettingsOutOfAnnotations.class);

  static final List<String> RUN_KEYS =
      List.of(
          "annotations.boomerang#io/workspace-name",
          "annotations.boomerang#io/task-timeout",
          "annotations.boomerang#io/task-default-image",
          "annotations.boomerang#io/task-deletion",
          "annotations.boomerang#io/global-params",
          "annotations.boomerang#io/workspace-params",
          "annotations.boomerang#io/context-params");
  static final String STATUS_KEY = "annotations.boomerang#io/status";
  static final String TASK_WORKSPACE_KEY = "annotations.boomerang#io/workspace-name";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);

    long runs =
        db.getCollection(names.resolve("workflow_runs"))
            .updateMany(anyExists(RUN_KEYS), Updates.combine(unsetAll(RUN_KEYS)))
            .getModifiedCount();

    MongoCollection<Document> taskRuns = db.getCollection(names.resolve("task_runs"));
    // Each pre-approved TaskRun in one write: its status moves to the typed field as the flag goes.
    List<Document> preApproved =
        taskRuns.find(Filters.eq("preApproved", true)).into(new ArrayList<>());
    for (Document taskRun : preApproved) {
      Object annotations = taskRun.get("annotations");
      Object status =
          (annotations instanceof Document document) ? document.get("boomerang#io/status") : null;
      taskRuns.updateOne(
          Filters.eq("_id", taskRun.get("_id")),
          Updates.combine(
              Updates.set("preApprovedStatus", (status != null) ? status : "succeeded"),
              Updates.unset("preApproved"),
              Updates.unset(STATUS_KEY)));
    }
    long tasks =
        taskRuns
            .updateMany(
                anyExists(List.of("preApproved", STATUS_KEY, TASK_WORKSPACE_KEY)),
                Updates.combine(
                    Updates.unset("preApproved"),
                    Updates.unset(STATUS_KEY),
                    Updates.unset(TASK_WORKSPACE_KEY)))
            .getModifiedCount();

    int locks = rekeyLocks(db, names);
    LOG.info(
        "Removed engine annotations from {} runs and {} task runs, moved {} pre-approved statuses,"
            + " re-keyed {} task locks",
        runs,
        tasks,
        preApproved.size(),
        locks);
  }

  // A held lock keyed by a workspace's current name is re-keyed by that workspace's id. A key whose
  // prefix names no workspace - an id already, a renamed workspace's old name, or no scope - stays.
  private static int rekeyLocks(MongoDatabase db, CollectionNames names) {
    Map<String, String> idsByName = new HashMap<>();
    for (Document workspace :
        db.getCollection(names.resolve("workspaces"))
            .find()
            .projection(new Document("name", 1))) {
      if (workspace.getString("name") != null) {
        idsByName.put(workspace.getString("name"), workspace.get("_id").toString());
      }
    }
    MongoCollection<Document> locks = db.getCollection(names.resolve("task_locks"));
    int rekeyed = 0;
    for (Document lock : locks.find().into(new ArrayList<>())) {
      String key = lock.get("_id").toString();
      int split = key.indexOf(':');
      String id = (split > 0) ? idsByName.get(key.substring(0, split)) : null;
      if (id == null) {
        continue;
      }
      lock.put("_id", id + key.substring(split));
      locks.replaceOne(Filters.eq("_id", lock.get("_id")), lock, new ReplaceOptions().upsert(true));
      locks.deleteOne(Filters.eq("_id", key));
      rekeyed++;
    }
    return rekeyed;
  }

  private static Bson anyExists(List<String> fields) {
    return Filters.or(fields.stream().map(Filters::exists).toList());
  }

  private static List<Bson> unsetAll(List<String> fields) {
    return fields.stream().map(Updates::unset).toList();
  }

  @Rollback
  public void rollback() {
    // Nothing reads the removed annotations or the flag any more; there is nothing to restore.
  }
}

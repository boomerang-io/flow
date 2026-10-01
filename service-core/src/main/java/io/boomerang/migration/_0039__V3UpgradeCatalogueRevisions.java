package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only. Bring the migrated catalogue up to what the engine runs, after the catalogue seed has
 * reconciled it:
 *
 * <ul>
 *   <li>No revision runs the retired {@code worker-flow} image: it read its params from a
 *       ConfigMap the dispatcher no longer serves, so an explicit pin moves to the seed's {@code
 *       task-flow} image, and a command holding the image name (a v3 data error) is cleared.
 *       Other image families are untouched.
 *   <li>The child-run tasks declare every param their seeded revision declares; the engine reads
 *       {@code workflowRef}, {@code wait} and the scheduling params from the run task, so an
 *       undeclared one is never filled.
 * </ul>
 *
 * <p>Idempotent: only {@code worker-flow} values and params missing by name are written.
 */
@Change(id = "0039-v3-upgrade-catalogue-revisions", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0039__V3UpgradeCatalogueRevisions {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0039__V3UpgradeCatalogueRevisions.class);

  static final String TARGET_IMAGE = "boomerangio/task-flow:3.1.0";
  private static final String RETIRED_IMAGE = "worker-flow";
  private static final List<String> CHILD_RUN_TASKS = List.of("run-workflow", "run-scheduled-workflow");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install - the seeded catalogue is already current.");
      return;
    }
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    long repointed =
        revisions
            .updateMany(
                Filters.regex("spec.image", RETIRED_IMAGE), Updates.set("spec.image", TARGET_IMAGE))
            .getModifiedCount();
    long commandsCleared =
        revisions
            .updateMany(Filters.regex("spec.command", RETIRED_IMAGE), Updates.set("spec.command", List.of()))
            .getModifiedCount();
    LOG.info(
        "{} revision(s) moved off {}, {} command(s) holding the image cleared",
        repointed,
        RETIRED_IMAGE,
        commandsCleared);

    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));
    for (String taskName : CHILD_RUN_TASKS) {
      declareSeedParams(tasks, revisions, taskName);
    }
  }

  /** Append the params the task's latest seeded revision declares and its latest revision lacks. */
  private void declareSeedParams(
      MongoCollection<Document> tasks, MongoCollection<Document> revisions, String taskName) {
    Document task = tasks.find(Filters.eq("name", taskName)).first();
    if (task == null) {
      LOG.warn("No {} catalogue task - nothing to declare", taskName);
      return;
    }
    Document latest =
        revisions
            .find(Filters.eq("parentRef", task.get("_id").toString()))
            .sort(Sorts.descending("version"))
            .first();
    if (latest == null) {
      LOG.warn("The {} task has no revision - nothing to declare", taskName);
      return;
    }
    Set<String> declared =
        paramsOf(latest).stream().map(param -> param.getString("name")).collect(Collectors.toSet());
    List<Document> missing =
        paramsOf(latestSeedRevision(taskName)).stream()
            .filter(param -> !declared.contains(param.getString("name")))
            .toList();
    if (!missing.isEmpty()) {
      revisions.updateOne(
          Filters.eq("_id", latest.get("_id")), Updates.pushEach("spec.params", missing));
    }
    LOG.info("{} declared {} param(s) from its seeded revision", taskName, missing.size());
  }

  private static Document latestSeedRevision(String taskName) {
    Object taskId =
        SeedResources.load("seed/tasks.json").stream()
            .filter(task -> taskName.equals(task.getString("name")))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("seed/tasks.json has no " + taskName))
            .get("_id");
    return SeedResources.load("seed/task-revisions.json").stream()
        .filter(revision -> taskId.toString().equals(revision.getString("parentRef")))
        .max(Comparator.comparing(revision -> revision.getInteger("version")))
        .orElseThrow(() -> new IllegalStateException("seed/task-revisions.json has no " + taskName));
  }

  private static List<Document> paramsOf(Document revision) {
    Document spec = revision.get("spec", Document.class);
    List<Document> params = (spec != null) ? spec.getList("params", Document.class) : null;
    return (params != null) ? params : List.of();
  }

  @Rollback
  public void rollback() {
    // The old image pins are not recorded; a revision can be re-pinned by hand if ever needed.
  }
}

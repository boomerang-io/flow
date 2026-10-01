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
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declares the {@code jsonSchema} param on the {@code ai} catalogue task. The AI worker (task-ai
 * 1.1.0) holds a JSON reply to this schema, and a workflow that sets an undeclared param is refused
 * at apply time, so without the declaration the param cannot be used at all.
 *
 * <p>A fresh install gets it from {@code seed/task-revisions.json}. This unit reads the param from
 * that same seed (one definition, not a second copy) and places it after {@code responseFormat} on
 * the latest {@code ai} revision, found by task name. Idempotent: a revision that already declares
 * it is left alone, and, like {@code _0046__DeclareRunWorkflowWaitParam}, the version is not
 * bumped.
 */
@Change(id = "0054-declare-ai-json-schema-param", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0054__DeclareAiJsonSchemaParam {

  private static final Logger LOG = LoggerFactory.getLogger(_0054__DeclareAiJsonSchemaParam.class);

  static final String TASK_NAME = "ai";
  static final String PARAM_NAME = "jsonSchema";
  private static final String AFTER_PARAM = "responseFormat";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    Document task = db.getCollection(names.resolve("tasks")).find(Filters.eq("name", TASK_NAME)).first();
    if (task == null) {
      LOG.warn("No ai catalogue task - nothing to declare.");
      return;
    }
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    Document latest =
        revisions
            .find(Filters.eq("parentRef", task.get("_id").toString()))
            .sort(Sorts.descending("version"))
            .limit(1)
            .first();
    Document spec = latest == null ? null : latest.get("spec", Document.class);
    if (spec == null) {
      LOG.warn("No ai task revision with a spec - nothing to declare.");
      return;
    }
    List<Document> params = new ArrayList<>(spec.getList("params", Document.class, List.of()));
    if (params.stream().anyMatch(p -> PARAM_NAME.equals(p.getString("name")))) {
      LOG.info("ai task revision {} already declares {}.", latest.get("_id"), PARAM_NAME);
      return;
    }
    int after = indexOf(params, AFTER_PARAM);
    params.add(after < 0 ? params.size() : after + 1, seededParam());
    revisions.updateOne(Filters.eq("_id", latest.get("_id")), Updates.set("spec.params", params));
    LOG.info("Declared {} on ai task revision {}.", PARAM_NAME, latest.get("_id"));
  }

  /** The param exactly as the seed declares it on the ai revision. */
  private static Document seededParam() {
    String seededId =
        SeedResources.load("seed/tasks.json").stream()
            .filter(t -> TASK_NAME.equals(t.getString("name")))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("seed/tasks.json no longer carries the ai task"))
            .get("_id")
            .toString();
    return SeedResources.load("seed/task-revisions.json").stream()
        .filter(r -> seededId.equals(r.getString("parentRef")))
        .flatMap(r -> r.get("spec", Document.class).getList("params", Document.class).stream())
        .filter(p -> PARAM_NAME.equals(p.getString("name")))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("seed/task-revisions.json no longer declares ai jsonSchema"));
  }

  private static int indexOf(List<Document> params, String name) {
    for (int i = 0; i < params.size(); i++) {
      if (name.equals(params.get(i).getString("name"))) {
        return i;
      }
    }
    return -1;
  }

  @Rollback
  public void rollback() {
    // Workflows may already set the param - forward-only, like the other catalogue units.
  }
}

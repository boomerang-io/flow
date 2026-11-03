package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Rewrites the parameter references in the starter workflows' revisions that admission refuses: a
 * task param reading its own name through {@code $(params.x)} now names the workflow's layer, and
 * the v3 {@code workflow-activity-id} becomes the run's context reference. Only a value holding one
 * of those strings changes, on starter workflows alone; a workspace's own copies are left as they are.
 */
@Change(id = "0042-starter-workflow-layer-references", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0042__StarterWorkflowLayerReferences {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0042__StarterWorkflowLayerReferences.class);

  static final Map<String, String> REFERENCES =
      Map.of(
          "$(params.workflow-activity-id)", "$(context.params.workflowrun-ref)",
          "$(params.query)", "$(workflow.params.query)",
          "$(params.url)", "$(workflow.params.url)",
          "$(params.model)", "$(workflow.params.model)");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> revisions = db.getCollection(names.resolve("workflow_revisions"));
    List<String> starters =
        workflows
            .find(Filters.eq("type", "starter"))
            .map(workflow -> workflow.getObjectId("_id").toString())
            .into(new ArrayList<>());
    int rewritten = 0;
    for (Document revision : revisions.find(Filters.in("workflowRef", starters))) {
      List<Document> tasks = revision.getList("tasks", Document.class);
      if (tasks == null || !rewrite(tasks)) {
        continue;
      }
      revisions.updateOne(Filters.eq("_id", revision.get("_id")), Updates.set("tasks", tasks));
      rewritten++;
    }
    LOG.info("Rewrote the parameter references of {} starter workflow revision(s)", rewritten);
  }

  /** Replace the references in every task param value; return whether anything changed. */
  static boolean rewrite(List<Document> tasks) {
    boolean changed = false;
    for (Document task : tasks) {
      List<Document> params = task.getList("params", Document.class);
      if (params == null) {
        continue;
      }
      for (Document param : params) {
        if (!(param.get("value") instanceof String value)) {
          continue;
        }
        String rewritten = value;
        for (Map.Entry<String, String> reference : REFERENCES.entrySet()) {
          rewritten = rewritten.replace(reference.getKey(), reference.getValue());
        }
        if (!rewritten.equals(value)) {
          param.put("value", rewritten);
          changed = true;
        }
      }
    }
    return changed;
  }

  @Rollback
  public void rollback() {
    // Forward-only: the earlier references did not resolve.
  }
}

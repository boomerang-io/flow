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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites the {@code path} help text on the {@code upload-artifact} and {@code download-artifact}
 * catalogue tasks: the worker now resolves a relative path against {@code /workspace}, so a path
 * names its workspace ({@code workflowrun/...}, {@code workflow/...}) and an absolute path is used
 * as-is.
 *
 * <p>Only the help text changes. Idempotent: it writes the same text on every run, and a task or
 * revision that is absent is skipped.
 */
@Change(id = "0053-artifact-path-help", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0053__ArtifactPathHelp {

  private static final Logger LOG = LoggerFactory.getLogger(_0053__ArtifactPathHelp.class);

  static final Map<String, String> HELP =
      Map.of(
          "upload-artifact",
          "A file or folder to upload; a folder is sent as one .tar.gz. Relative to /workspace"
              + " (e.g. workflowrun/reports/sbom.json), or an absolute path.",
          "download-artifact",
          "Where to put it; an uploaded folder is unpacked here. Relative to /workspace (e.g."
              + " workflowrun/inputs/), or an absolute path.");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> tasks = db.getCollection(names.resolve("tasks"));
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    HELP.forEach(
        (taskName, help) -> {
          Document task = tasks.find(Filters.eq("name", taskName)).first();
          if (task == null) {
            LOG.warn("No {} catalogue task - nothing to describe.", taskName);
            return;
          }
          for (Document revision :
              revisions.find(Filters.eq("parentRef", task.get("_id").toString()))) {
            Document spec = revision.get("spec", Document.class);
            List<Document> params = spec == null ? null : spec.getList("params", Document.class);
            if (params == null) {
              continue;
            }
            List<Document> updated = new ArrayList<>();
            for (Document param : params) {
              if ("path".equals(param.getString("name"))) {
                param.put("helpertext", help);
              }
              updated.add(param);
            }
            revisions.updateOne(
                Filters.eq("_id", revision.get("_id")), Updates.set("spec.params", updated));
          }
          LOG.info("Described the relative path on the {} task.", taskName);
        });
  }

  @Rollback
  public void rollback() {
    // Help text only - forward-only, like the other catalogue units in this chain.
  }
}

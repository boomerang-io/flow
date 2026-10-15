package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Adds the {@code upload-artifact} and {@code download-artifact} catalogue tasks (types {@code
 * uploadartifact} and {@code downloadartifact}), which the default worker image runs. Each declares
 * every param its worker reads: the ones an author fills ({@code name}, {@code path}, {@code scope},
 * {@code retention-days} on upload and {@code optional} on download) and the read-only link params
 * Flow fills when it hands the task to a dispatcher ({@code url}, {@code headers}, and {@code
 * sha256} and {@code contentType} on download). Download declares the {@code found} result the
 * engine fills.
 *
 * <p>Ids are assigned on insert. Idempotent: each task is matched by name, its version 1 revision
 * by parent and version, and its graph node and root edge by id.
 */
@Change(id = "0018-seed-artifact-tasks", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0018__SeedArtifactTasks {

  private static final Logger LOG = LoggerFactory.getLogger(_0018__SeedArtifactTasks.class);

  static final String UPLOAD_TASK = "upload-artifact";
  static final String DOWNLOAD_TASK = "download-artifact";
  private static final String ROOT_NODE_ID = "root:root";
  private static final String FILLED_BY_FLOW = "Filled in by Flow when the task runs.";
  private static final String SCOPE_HELP =
      "run: this run only, expires on its retention date. workflow: kept for every later run of"
          + " this workflow until replaced.";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    seed(
        db,
        names,
        UPLOAD_TASK,
        "uploadartifact",
        "Upload Artifact",
        "Upload a file or folder from the run workspace as an artifact of this run or of the"
            + " workflow.",
        "Upload",
        List.of(
            param("name", "Artifact name", "text", true, false,
                "Unique within the run. Letters, digits, dot, dash and underscore."),
            param("path", "Path", "text", true, false,
                "A file or folder to upload; a folder is sent as one .tar.gz. Relative to"
                    + " /workspace (e.g. workflowrun/reports/sbom.json), or an absolute path."),
            scopeParam(),
            param("retention-days", "Retention days", "text", false, false,
                "Blank keeps it for the workspace's retention. You can ask for fewer days, not"
                    + " more. Not allowed with scope workflow."),
            param("url", "Upload link", "password", false, true, FILLED_BY_FLOW),
            param("headers", "Upload headers", "text", false, true, FILLED_BY_FLOW)),
        List.of());
    seed(
        db,
        names,
        DOWNLOAD_TASK,
        "downloadartifact",
        "Download Artifact",
        "Download an artifact of this run, or the workflow's kept artifact of that name, into the"
            + " run workspace, checking its SHA-256.",
        "Download",
        List.of(
            param("name", "Artifact name", "text", true, false,
                "An artifact an earlier task in this run uploaded, or with scope workflow the"
                    + " workflow's kept artifact of that name."),
            param("path", "Destination path", "text", true, false,
                "Where to put it; an uploaded folder is unpacked here. Relative to /workspace"
                    + " (e.g. workflowrun/inputs/), or an absolute path."),
            scopeParam(),
            param("optional", "Optional", "boolean", false, false, "false", List.of(),
                "Succeed with result found=false when no artifact of this name exists, instead of"
                    + " failing."),
            param("url", "Download link", "password", false, true, FILLED_BY_FLOW),
            param("headers", "Download headers", "text", false, true, FILLED_BY_FLOW),
            param("sha256", "SHA-256", "text", false, true, FILLED_BY_FLOW),
            param("contentType", "Content type", "text", false, true, FILLED_BY_FLOW)),
        List.of(
            new Document("description", "true when an artifact was downloaded")
                .append("name", "found")));
  }

  private static Document scopeParam() {
    return param(
        "scope",
        "Scope",
        "select",
        false,
        false,
        "run",
        List.of(option("run"), option("workflow")),
        SCOPE_HELP);
  }

  private static Document option(String value) {
    return new Document("key", value).append("value", value);
  }

  private void seed(
      MongoDatabase db,
      CollectionNames names,
      String name,
      String type,
      String displayName,
      String description,
      String icon,
      List<Document> params,
      List<Document> results) {
    Document existing =
        db.getCollection(names.resolve("tasks")).find(Filters.eq("name", name)).first();
    String taskId;
    if (existing != null) {
      taskId = existing.get("_id").toString();
    } else {
      ObjectId id = new ObjectId();
      db.getCollection(names.resolve("tasks"))
          .insertOne(
              new Document("_id", id)
                  .append("name", name)
                  .append("status", "active")
                  .append("verified", true)
                  .append("labels", new Document())
                  .append(
                      "annotations",
                      new Document("boomerang#io/generation", "4")
                          .append("boomerang#io/kind", "Task"))
                  .append("creationDate", new Date())
                  .append("type", type));
      taskId = id.toString();
      LOG.info("Inserted the {} catalogue task as {}", name, taskId);
    }

    Document revision =
        new Document("displayName", displayName)
            .append("description", description)
            .append("category", "Artifacts")
            .append("icon", icon)
            .append("version", 1)
            .append(
                "changelog",
                new Document("reason", "Initial Task Template")
                    .append("date", new Date())
                    .append("author", ""))
            .append(
                "spec",
                new Document("params", params)
                    .append("arguments", new ArrayList<>())
                    .append("command", new ArrayList<>())
                    .append("envs", null)
                    .append("image", "")
                    .append("results", new ArrayList<>(results))
                    .append("script", null)
                    .append("workingDir", null))
            .append("parentRef", taskId);
    if (SeedResources.insertIfAbsent(
        db,
        names.resolve("task_revisions"),
        Filters.and(Filters.eq("parentRef", taskId), Filters.eq("version", 1)),
        revision)) {
      LOG.info("Inserted the {} task revision for task {}", name, taskId);
    }

    String nodeId = "task:" + taskId;
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_nodes"),
        Filters.eq("_id", nodeId),
        SeedResources.node("task", taskId, name));
    SeedResources.insertIfAbsent(
        db,
        names.resolve("rel_edges"),
        Filters.and(
            Filters.eq("from", ROOT_NODE_ID),
            Filters.eq("label", "hasTask"),
            Filters.eq("to", nodeId)),
        SeedResources.edge(ROOT_NODE_ID, "hasTask", nodeId, new Document()));
  }

  private static Document param(
      String name, String label, String type, boolean required, boolean readOnly, String help) {
    return param(name, label, type, required, readOnly, "", null, help);
  }

  /** The seed catalogue's param shape; {@code options} only on a select. */
  private static Document param(
      String name,
      String label,
      String type,
      boolean required,
      boolean readOnly,
      String defaultValue,
      List<Document> options,
      String help) {
    Document param = new Document("description", "").append("label", label).append("type", type);
    if (options != null && !options.isEmpty()) {
      param.append("options", new ArrayList<>(options));
    }
    return param
        .append("required", required)
        .append("placeholder", "")
        .append("helpertext", help)
        .append("defaultValue", defaultValue)
        .append("readOnly", readOnly)
        .append("name", name);
  }

  @Rollback
  public void rollback() {
    // Workflows on a live install may already reference the tasks by name - forward-only, like
    // every other catalogue unit in this chain.
  }
}

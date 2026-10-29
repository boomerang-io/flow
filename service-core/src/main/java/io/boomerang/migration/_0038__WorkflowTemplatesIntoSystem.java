package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Move the workflow templates into the system workspace as starters, and drop the collection they
 * lived in. A starter is a workflow of type {@code starter}: one {@code workflows} document per
 * template name, one {@code workflow_revisions} document per template version, and the
 * {@code workspace --hasWorkflow--> workflow} edge that makes the system workspace its owner, so
 * admins version and edit it like any workflow and every workspace copies it through the template
 * routes. The seeded templates and a v3 install's own templates ({@code
 * _0011__V3ExtractWorkflowTemplates}) move alike; a name the system workspace already holds is
 * left in place and its template dropped with the rest.
 */
@Change(id = "0038-workflow-templates-into-system", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0038__WorkflowTemplatesIntoSystem {

  private static final Logger LOG = LoggerFactory.getLogger(_0038__WorkflowTemplatesIntoSystem.class);

  static final String TEMPLATES = "workflow_templates";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> templates = db.getCollection(names.resolve(TEMPLATES));
    MongoCollection<Document> workflows = db.getCollection(names.resolve("workflows"));
    MongoCollection<Document> revisions = db.getCollection(names.resolve("workflow_revisions"));
    Document system =
        db.getCollection(names.resolve("workspaces")).find(Filters.eq("name", "system")).first();
    if (system == null) {
      LOG.warn("No system workspace: the workflow templates stay where they are.");
      return;
    }
    String workspaceNodeId = "workspace:" + system.getObjectId("_id").toString();

    Map<String, List<Document>> byName = new LinkedHashMap<>();
    for (Document template : templates.find().into(new ArrayList<>())) {
      byName.computeIfAbsent(template.getString("name"), name -> new ArrayList<>()).add(template);
    }
    int moved = 0;
    int kept = 0;
    for (Map.Entry<String, List<Document>> entry : byName.entrySet()) {
      String name = entry.getKey();
      if (ownedBySystem(db, names, workspaceNodeId, workflows, name)) {
        kept++;
        continue;
      }
      List<Document> versions = entry.getValue();
      versions.sort((a, b) -> Integer.compare(version(a), version(b)));
      Document first = versions.get(0);
      ObjectId workflowId = new ObjectId();
      workflows.insertOne(starter(workflowId, first));
      for (Document version : versions) {
        revisions.insertOne(revision(db, names, workflowId, version));
      }
      String nodeId = "workflow:" + workflowId;
      SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_nodes"),
          Filters.eq("_id", nodeId),
          SeedResources.node("workflow", workflowId.toString(), name));
      SeedResources.insertIfAbsent(
          db,
          names.resolve("rel_edges"),
          Filters.and(
              Filters.eq("from", workspaceNodeId),
              Filters.eq("label", "hasWorkflow"),
              Filters.eq("to", nodeId)),
          SeedResources.edge(workspaceNodeId, "hasWorkflow", nodeId, new Document()));
      moved++;
    }
    templates.drop();
    LOG.info(
        "Workflow templates moved into the system workspace as starters: {} moved, {} already"
            + " there; the {} collection is dropped.",
        moved,
        kept,
        TEMPLATES);
  }

  // Whether the system workspace already owns a workflow of this name: the edge from its node to
  // a workflow node with that slug.
  private static boolean ownedBySystem(
      MongoDatabase db,
      CollectionNames names,
      String workspaceNodeId,
      MongoCollection<Document> workflows,
      String name) {
    for (Document edge :
        db.getCollection(names.resolve("rel_edges"))
            .find(Filters.and(Filters.eq("from", workspaceNodeId), Filters.eq("label", "hasWorkflow")))
            .into(new ArrayList<>())) {
      Document node =
          db.getCollection(names.resolve("rel_nodes"))
              .find(Filters.and(Filters.eq("_id", edge.getString("to")), Filters.eq("slug", name)))
              .first();
      if (node != null) {
        return true;
      }
    }
    return false;
  }

  private static int version(Document template) {
    Integer version = template.getInteger("version");
    return (version != null) ? version : 1;
  }

  // The workflow document, in the shape WorkflowEntity writes, from the template's first version.
  private static Document starter(ObjectId id, Document template) {
    Document annotations =
        new Document(template.get("annotations", new Document()))
            .append("boomerang#io/generation", "4")
            .append("boomerang#io/kind", "Workflow");
    // The complete v5 triggers document, as the v3 upgrade writes it: manual on, the rest off.
    Document triggers =
        new Document("manual", new Document("enabled", true))
            .append("schedule", new Document("enabled", false))
            .append("webhook", new Document("enabled", false))
            .append("event", new Document("enabled", false));
    return new Document("_id", id)
        .append("name", template.getString("name"))
        .append("displayName", template.getString("displayName"))
        .append("status", "active")
        .append(
            "creationDate",
            template.getDate("creationDate") != null ? template.getDate("creationDate") : new Date())
        .append("icon", template.getString("icon"))
        .append("description", template.getString("description"))
        .append("labels", template.get("labels", new Document()))
        .append("annotations", annotations)
        .append("triggers", triggers)
        .append("type", "starter");
  }

  // One revision per template version, in the shape WorkflowRevisionEntity writes. A task the seed
  // left without a taskVersion takes its catalogue task's latest, as a save would pin it.
  private static Document revision(
      MongoDatabase db, CollectionNames names, ObjectId workflowId, Document template) {
    List<Document> tasks = template.getList("tasks", Document.class, new ArrayList<>());
    for (Document task : tasks) {
      if (task.getString("taskRef") != null && task.get("taskVersion") == null) {
        Document latest =
            db.getCollection(names.resolve("task_revisions"))
                .find(Filters.eq("parentRef", task.getString("taskRef")))
                .sort(new Document("version", -1))
                .first();
        if (latest != null) {
          task.put("taskVersion", latest.getInteger("version"));
        }
      }
    }
    Document revision =
        new Document("_id", new ObjectId())
            .append("workflowRef", workflowId.toString())
            .append("version", version(template))
            .append("tasks", tasks)
            .append("params", template.get("params", new ArrayList<>()));
    for (String field : List.of("changelog", "markdown", "workspace", "timeout", "retries")) {
      if (template.get(field) != null) {
        revision.append(field, template.get(field));
      }
    }
    return revision;
  }

  @Rollback
  public void rollback() {}
}

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
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Renames the type of a workspace's own task node from {@code teamtask} to {@code workspacetask}. A
 * node's id is {@code <type>:<ref>}, so each node is written again under its new id before the old
 * one is removed, and every edge naming an old id is re-pointed. Nodes and edges are moved
 * separately, so a start that stopped part way finishes the rest. Only a database that ran a v5
 * beta holds one: an empty or a v3 database has none, and a second run finds none.
 */
@Change(id = "0027-rename-workspace-task-nodes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0027__RenameWorkspaceTaskNodes {

  private static final Logger LOG = LoggerFactory.getLogger(_0027__RenameWorkspaceTaskNodes.class);

  static final String FROM = "teamtask";
  static final String TO = "workspacetask";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> nodes = db.getCollection(names.resolve("rel_nodes"));
    MongoCollection<Document> edges = db.getCollection(names.resolve("rel_edges"));

    List<Document> oldNodes = nodes.find(Filters.eq("type", FROM)).into(new ArrayList<>());
    for (Document node : oldNodes) {
      String oldId = node.getString("_id");
      node.put("_id", renamed(oldId));
      node.put("type", TO);
      nodes.replaceOne(Filters.eq("_id", node.get("_id")), node, new ReplaceOptions().upsert(true));
      nodes.deleteOne(Filters.eq("_id", oldId));
    }

    Bson oldEnd =
        Filters.or(Filters.regex("from", "^" + FROM + ":"), Filters.regex("to", "^" + FROM + ":"));
    List<Document> oldEdges = edges.find(oldEnd).into(new ArrayList<>());
    for (Document edge : oldEdges) {
      edges.updateOne(
          Filters.eq("_id", edge.get("_id")),
          Updates.combine(
              Updates.set("from", renamed(edge.getString("from"))),
              Updates.set("to", renamed(edge.getString("to")))));
    }
    LOG.info("Renamed {} workspace task nodes and re-pointed {} edges", oldNodes.size(), oldEdges.size());
  }

  // A node id of the old type under the new one; any other id as it is.
  private static String renamed(String nodeId) {
    return (nodeId != null && nodeId.startsWith(FROM + ":"))
        ? TO + nodeId.substring(FROM.length())
        : nodeId;
  }

  @Rollback
  public void rollback() {
    // The old type is read by no code that runs after this change; there is nothing to restore.
  }
}

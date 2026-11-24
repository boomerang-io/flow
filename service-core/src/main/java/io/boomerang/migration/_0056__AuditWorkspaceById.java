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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Re-keys the audit trail on the workspace's id. Events recorded before 5.0.1 name their workspace
 * by its name, which a rename changes: each workspace's events move to its id, with the name they
 * carried kept as {@code workspaceName}. An event whose name no workspace holds now, a deleted
 * workspace's, keeps the name as its key and gains it as {@code workspaceName}. An event that has a
 * {@code workspaceName} is already keyed by id and is left alone, so a second run changes nothing.
 * An event a route recorded by the workspace's id gains the workspace's name now. A name two
 * workspaces hold, as a 3.5 upgrade can carry over, names neither: its events keep it, and the unit
 * logs it. An empty database has no events; an upgrade from 3.5 carries none over.
 */
@Change(id = "0056-audit-workspace-by-id", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0056__AuditWorkspaceById {

  private static final Logger LOG = LoggerFactory.getLogger(_0056__AuditWorkspaceById.class);

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> audit = db.getCollection(names.resolve("audit"));
    Map<String, List<String>> idsByName = new HashMap<>();
    for (Document workspace :
        db.getCollection(names.resolve("workspaces")).find().projection(new Document("name", 1))) {
      String name = workspace.getString("name");
      if (name != null) {
        idsByName.computeIfAbsent(name, key -> new ArrayList<>()).add(workspace.get("_id").toString());
      }
    }
    long named = 0;
    long rekeyed = 0;
    for (Map.Entry<String, List<String>> workspace : idsByName.entrySet()) {
      String name = workspace.getKey();
      // An event a route recorded by the workspace's id takes its name now.
      for (String id : workspace.getValue()) {
        named +=
            audit
                .updateMany(
                    Filters.and(Filters.eq("workspaceId", id), Filters.exists("workspaceName", false)),
                    Updates.set("workspaceName", name))
                .getModifiedCount();
      }
      // A name two workspaces hold, as a 3.5 upgrade can carry over, says neither: its events keep it.
      if (workspace.getValue().size() > 1) {
        LOG.warn(
            "Audit events named \"{}\" are left under the name: {} workspaces hold it", name,
            workspace.getValue().size());
        continue;
      }
      // Served by the workspace_time index on its leading key.
      rekeyed +=
          audit
              .updateMany(
                  Filters.and(Filters.eq("workspaceId", name), Filters.exists("workspaceName", false)),
                  Updates.combine(
                      Updates.set("workspaceId", workspace.getValue().get(0)),
                      Updates.set("workspaceName", name)))
              .getModifiedCount();
    }
    long kept =
        audit
            .updateMany(
                Filters.and(Filters.exists("workspaceName", false), Filters.ne("workspaceId", null)),
                List.of(new Document("$set", new Document("workspaceName", "$workspaceId"))))
            .getModifiedCount();
    LOG.info(
        "Audit events keyed by workspace id: {} re-keyed, {} already keyed by id named, {} kept the"
            + " name they carried",
        rekeyed,
        named,
        kept);
  }

  @Rollback
  public void rollback() {
    // The names stay on every event, so nothing is lost - forward-only, like every unit here.
  }
}

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
import java.util.List;
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
 * An empty database has no events; an upgrade from 3.5 carries none over.
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
    long rekeyed = 0;
    for (Document workspace :
        db.getCollection(names.resolve("workspaces"))
            .find()
            .projection(new Document("name", 1))) {
      String name = workspace.getString("name");
      if (name == null) {
        continue;
      }
      // Served by the workspace_time index on its leading key.
      rekeyed +=
          audit
              .updateMany(
                  Filters.and(Filters.eq("workspaceId", name), Filters.exists("workspaceName", false)),
                  Updates.combine(
                      Updates.set("workspaceId", workspace.get("_id").toString()),
                      Updates.set("workspaceName", name)))
              .getModifiedCount();
    }
    long orphaned =
        audit
            .updateMany(
                Filters.and(
                    Filters.exists("workspaceName", false),
                    Filters.ne("workspaceId", null)),
                List.of(new Document("$set", new Document("workspaceName", "$workspaceId"))))
            .getModifiedCount();
    LOG.info(
        "Audit events keyed by workspace id: {} re-keyed, {} kept a deleted workspace's name",
        rekeyed,
        orphaned);
  }

  @Rollback
  public void rollback() {
    // The names stay on every event, so nothing is lost - forward-only, like every unit here.
  }
}

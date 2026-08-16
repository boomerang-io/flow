package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
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
import java.util.regex.Pattern;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-01 Team -> Workspace rename, the persisted half. The code-level rename (workspace.Workspace*
 * classes, {@code AuthScope.team -> AuthScope.workspace}, {@code RelationshipType.TEAM ->
 * WORKSPACE}, the {@code /api/v2/team} -> {@code /api/v2/workspace} path aliases) shipped
 * earlier; this migrates the stored strings that still carry the old "team" value:
 *
 * <ul>
 *   <li>{@code rel_nodes}: {@code type} "team" -> "workspace" (RelationshipType's stored label).
 *       Every migrated node is also re-keyed - the node's {@code _id} is the composite {@code
 *       type:ref} built at creation time ({@code "team:<ref>"}), and {@code
 *       RelationshipService#getParentByLabel} reconstructs that same composite key from the
 *       type's *current* label rather than resolving it through the node, so a stale {@code
 *       _id} would silently stop matching once the label changes. Mongo {@code _id} is
 *       immutable, so this deletes the old-keyed document and inserts an identical one under
 *       the new {@code "workspace:<ref>"} id (keeping every other field, including {@code
 *       creationDate}).
 *   <li>{@code rel_edges}: {@code from}/{@code to} - any value with the {@code team:} prefix
 *       (the composite key produced by the {@code rel_nodes} re-keying above) becomes {@code
 *       workspace:}, keeping every edge pointed at the node's new id.
 *   <li>{@code tokens.type} and {@code roles.type}: "team" -> "workspace" (AuthScope's stored
 *       enum name - both entities carry an AuthScope-typed {@code type} field written with the
 *       default enum-name Mongo converter, and {@code RoleRepository}/{@code TokenRepository}
 *       are queried with the literal scope string).
 * </ul>
 *
 * <p>Idempotent: every step only touches documents still carrying the old "team" string, so a
 * second run is a no-op.
 */
@Change(id = "0012-workspace-rename", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0012__WorkspaceRename {

  private static final Logger LOG = LoggerFactory.getLogger(_0012__WorkspaceRename.class);
  private static final String OLD_LABEL = "team";
  private static final String NEW_LABEL = "workspace";
  private static final Pattern OLD_PREFIX = Pattern.compile("^team:");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    long nodesRekeyed = rekeyTeamNodes(db, names.resolve("rel_nodes"));
    long edgesFromFixed = rewritePrefix(db, names.resolve("rel_edges"), "from");
    long edgesToFixed = rewritePrefix(db, names.resolve("rel_edges"), "to");
    long tokensRenamed = renameFieldValue(db, names.resolve("tokens"), "type");
    long rolesRenamed = renameFieldValue(db, names.resolve("roles"), "type");
    LOG.info(
        "Workspace rename — rel_nodes re-keyed: {}, rel_edges.from fixed: {}, rel_edges.to"
            + " fixed: {}, tokens.type renamed: {}, roles.type renamed: {}",
        nodesRekeyed,
        edgesFromFixed,
        edgesToFixed,
        tokensRenamed,
        rolesRenamed);
  }

  /**
   * Re-keys every {@code rel_nodes} document whose {@code type} is still "team": sets {@code
   * type} to "workspace" and replaces the {@code "team:<ref>"} {@code _id} with {@code
   * "workspace:<ref>"} (delete + insert, since Mongo {@code _id} can't be updated in place).
   * Skipped per-document if a document already exists under the target id (idempotent / safe to
   * re-run after a partial failure).
   */
  private long rekeyTeamNodes(MongoDatabase db, String collection) {
    MongoCollection<Document> nodes = db.getCollection(collection);
    List<Document> stale = new ArrayList<>();
    try (MongoCursor<Document> cursor = nodes.find(Filters.eq("type", OLD_LABEL)).iterator()) {
      while (cursor.hasNext()) {
        stale.add(cursor.next());
      }
    }
    long rekeyed = 0;
    for (Document doc : stale) {
      String oldId = doc.getString("_id");
      String newId = NEW_LABEL + ":" + doc.getString("ref");
      if (nodes.find(Filters.eq("_id", newId)).first() != null) {
        LOG.warn("Skipping re-key of {} — {} already exists", oldId, newId);
        continue;
      }
      Document rekeyedDoc = new Document(doc);
      rekeyedDoc.put("_id", newId);
      rekeyedDoc.put("type", NEW_LABEL);
      nodes.insertOne(rekeyedDoc);
      nodes.deleteOne(Filters.eq("_id", oldId));
      rekeyed++;
    }
    return rekeyed;
  }

  /** Rewrites a {@code team:}-prefixed composite key field to the {@code workspace:} prefix. */
  private long rewritePrefix(MongoDatabase db, String collection, String field) {
    MongoCollection<Document> coll = db.getCollection(collection);
    List<Document> stale = new ArrayList<>();
    try (MongoCursor<Document> cursor =
        coll.find(Filters.regex(field, OLD_PREFIX)).iterator()) {
      while (cursor.hasNext()) {
        stale.add(cursor.next());
      }
    }
    long updated = 0;
    for (Document doc : stale) {
      String value = doc.getString(field);
      String newValue = NEW_LABEL + ":" + value.substring(OLD_LABEL.length() + 1);
      coll.updateOne(Filters.eq("_id", doc.get("_id")), Updates.set(field, newValue));
      updated++;
    }
    return updated;
  }

  private long renameFieldValue(MongoDatabase db, String collection, String field) {
    return db.getCollection(collection)
        .updateMany(Filters.eq(field, OLD_LABEL), Updates.set(field, NEW_LABEL))
        .getModifiedCount();
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Value/id renames are not restored - "workspace" is authoritative going forward, matching
    // the other online migrations' rollback scope (see _0011__DispatcherRename).
  }
}

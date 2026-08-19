package io.boomerang.loader.migration;

import com.mongodb.MongoNamespace;
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
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-01 Team -> Workspace rename, the persisted half — H14's full sweep. The code-level rename
 * (workspace.Workspace* classes, {@code AuthScope.team -> AuthScope.workspace}, {@code
 * RelationshipType.TEAM -> WORKSPACE}, the {@code /api/v2/team} -> {@code /api/v2/workspace} path
 * aliases, and H14's retirement of that alias / {@code PermissionResource}/{@code AuditScope}
 * renames / {@code boomerang.io/team-*} annotation keys / {@code teams} collection name) shipped
 * in code separately; this migrates every stored string/collection that still carries the old
 * "team" value. Runs UNGATED — on every install generation — since each step only touches
 * documents/collections still carrying the old shape, so it is a no-op wherever there is nothing
 * to fix (a fresh v5 install, or an install that already ran this unit).
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
 *   <li>{@code roles.type}: "team" -> "workspace" ({@code PermissionScope}'s stored enum name;
 *       {@code RoleRepository} is queried with the literal scope string). {@code tokens.type} is
 *       deliberately NOT rewritten: the v5 token classes ({@code AuthScope}) are {@code
 *       session/user/key/global} - "workspace" is not one of them - and the retired {@code team}
 *       (and {@code workflow}) class tokens are deleted outright by {@code
 *       _0028__TokenClassRestructure}, which accepts either spelling. Rewriting the value here
 *       would only create an intermediate state no enum can load.
 *   <li>{@code roles.permissions[]}: any element with a {@code team/} prefix (a stored {@code
 *       PermissionResource} label, e.g. {@code "team/read"}) becomes {@code workspace/}. Array
 *       elements, so this is a fetch-rewrite-replace per document, not a single {@code $rename}.
 *   <li>{@code tokens.permissions[].actions[]}: the same {@code team/} -> {@code workspace/}
 *       rewrite, one level deeper - {@code TokenEntity.permissions} is a {@code
 *       List<ResolvedPermissions>}, and {@code ResolvedPermissions.actions} is itself the
 *       {@code List<String>} of permission strings.
 *   <li>{@code audit.scope}: "TEAM" -> "WORKSPACE" (AuditScope's raw {@code Enum.name()} - unlike
 *       the scopes above, {@code AuditScope} has no custom {@code @JsonCreator}/{@code
 *       valueOfLabel} parse path at the Mongo boundary, so this rewrite is REQUIRED for
 *       correctness: a stray persisted {@code "TEAM"} would throw on load once the {@code TEAM}
 *       enum constant is renamed, not just mis-resolve).
 *   <li>{@code workflow_runs.annotations}/{@code task_runs.annotations}: the {@code "#"}-escaped
 *       (see {@code MongoConfiguration#setMapKeyDotReplacement}) keys {@code
 *       boomerang#io/team-name} and {@code boomerang#io/team-params} become {@code
 *       boomerang#io/workspace-name}/{@code boomerang#io/workspace-params} - a nested map-key
 *       rename, done with {@code $rename} on the dotted field path.
 *   <li>{@code teams} collection -> {@code workspaces} ({@code WorkspaceEntity}'s {@code
 *       @Document} name, kept as {@code teams} through DD-01 and only renamed at H14). A plain
 *       {@code renameCollection} - every v3/v4/fresh-install unit earlier in this chain
 *       (_0003/_0007/_0008/_0012/_0013) deliberately still targets the literal {@code teams}
 *       name, so this MUST run after all of them and before anything that expects {@code
 *       workspaces} (the seed units and the application itself, both post-loader).
 * </ul>
 *
 * <p>Idempotent: every step only touches documents/values still carrying the old "team" string
 * (or, for the collection rename, only fires when {@code teams} exists and {@code workspaces}
 * does not), so a second run is a no-op.
 */
@Change(id = "0016-workspace-rename", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0016__WorkspaceRename {

  private static final Logger LOG = LoggerFactory.getLogger(_0016__WorkspaceRename.class);
  private static final String OLD_LABEL = "team";
  private static final String NEW_LABEL = "workspace";
  private static final Pattern OLD_PREFIX = Pattern.compile("^team:");
  private static final Pattern OLD_PERMISSION_PREFIX = Pattern.compile("^team/");

  /** {@code MongoConfiguration#setMapKeyDotReplacement("#")} - see H14-b's annotation keys. */
  private static final Map<String, String> ANNOTATION_KEY_RENAMES =
      Map.of(
          "annotations.boomerang#io/team-name", "annotations.boomerang#io/workspace-name",
          "annotations.boomerang#io/team-params", "annotations.boomerang#io/workspace-params");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    long nodesRekeyed = rekeyTeamNodes(db, names.resolve("rel_nodes"));
    long edgesFromFixed = rewritePrefix(db, names.resolve("rel_edges"), "from");
    long edgesToFixed = rewritePrefix(db, names.resolve("rel_edges"), "to");
    long rolesRenamed = renameFieldValue(db, names.resolve("roles"), "type");
    long rolePermissionsRewritten = rewriteRolePermissions(db, names.resolve("roles"));
    long tokenActionsRewritten = rewriteTokenPermissionActions(db, names.resolve("tokens"));
    long auditScopesRenamed = renameAuditScope(db, names.resolve("audit"));
    long taskRunAnnotationsRenamed =
        renameAnnotationKeys(db, names.resolve("task_runs"));
    long workflowRunAnnotationsRenamed =
        renameAnnotationKeys(db, names.resolve("workflow_runs"));
    boolean collectionRenamed = renameTeamsCollection(db, names);
    LOG.info(
        "Workspace rename — rel_nodes re-keyed: {}, rel_edges.from fixed: {}, rel_edges.to"
            + " fixed: {}, roles.type renamed: {}, role permissions"
            + " rewritten: {}, token action permissions rewritten: {}, audit scopes renamed: {},"
            + " task_runs annotations renamed: {}, workflow_runs annotations renamed: {}, teams"
            + " collection renamed to workspaces: {}",
        nodesRekeyed,
        edgesFromFixed,
        edgesToFixed,
        rolesRenamed,
        rolePermissionsRewritten,
        tokenActionsRewritten,
        auditScopesRenamed,
        taskRunAnnotationsRenamed,
        workflowRunAnnotationsRenamed,
        collectionRenamed);
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

  /**
   * {@code roles.permissions[]}: flat {@code List<String>} of {@code "resource/action"} strings.
   * Rewrites any element whose resource half is the old {@code team} label — a substring rewrite
   * inside an array element, so this fetches, rewrites in memory, and replaces the whole array
   * rather than using a single {@code $rename}/{@code $set} on a field path.
   */
  private long rewriteRolePermissions(MongoDatabase db, String collection) {
    MongoCollection<Document> roles = db.getCollection(collection);
    long updated = 0;
    try (MongoCursor<Document> cursor =
        roles.find(Filters.regex("permissions", OLD_PERMISSION_PREFIX)).iterator()) {
      while (cursor.hasNext()) {
        Document role = cursor.next();
        List<String> permissions = role.getList("permissions", String.class);
        List<String> rewritten = permissions.stream().map(this::rewritePermission).toList();
        roles.updateOne(Filters.eq("_id", role.get("_id")), Updates.set("permissions", rewritten));
        updated++;
      }
    }
    return updated;
  }

  /**
   * {@code tokens.permissions[].actions[]}: {@code TokenEntity.permissions} is a {@code
   * List<ResolvedPermissions>} subdocument array; each subdocument's own {@code actions[]} is the
   * {@code List<String>} of permission strings. One level deeper than {@link
   * #rewriteRolePermissions}, so the whole {@code permissions} array is rewritten in memory and
   * replaced.
   */
  private long rewriteTokenPermissionActions(MongoDatabase db, String collection) {
    MongoCollection<Document> tokens = db.getCollection(collection);
    long updated = 0;
    try (MongoCursor<Document> cursor =
        tokens.find(Filters.regex("permissions.actions", OLD_PERMISSION_PREFIX)).iterator()) {
      while (cursor.hasNext()) {
        Document token = cursor.next();
        List<Document> permissions = token.getList("permissions", Document.class);
        List<Document> rewritten = new ArrayList<>();
        for (Document resolvedPermission : permissions) {
          List<String> actions = resolvedPermission.getList("actions", String.class);
          if (actions == null) {
            rewritten.add(resolvedPermission);
            continue;
          }
          Document copy = new Document(resolvedPermission);
          copy.put("actions", actions.stream().map(this::rewritePermission).toList());
          rewritten.add(copy);
        }
        tokens.updateOne(Filters.eq("_id", token.get("_id")), Updates.set("permissions", rewritten));
        updated++;
      }
    }
    return updated;
  }

  private String rewritePermission(String permission) {
    return OLD_PERMISSION_PREFIX.matcher(permission).find()
        ? NEW_LABEL + "/" + permission.substring(OLD_LABEL.length() + 1)
        : permission;
  }

  /**
   * {@code audit.scope}: "TEAM" -> "WORKSPACE" (the raw enum name, not the lowercase label — see
   * the class javadoc on why this one is load-bearing, not just cosmetic).
   */
  private long renameAuditScope(MongoDatabase db, String collection) {
    return db.getCollection(collection)
        .updateMany(Filters.eq("scope", "TEAM"), Updates.set("scope", "WORKSPACE"))
        .getModifiedCount();
  }

  /**
   * H14-b: renames the {@code "#"}-escaped {@code boomerang#io/team-*} annotation map keys to
   * {@code boomerang#io/workspace-*} on the given collection ({@code workflow_runs} or {@code
   * task_runs}). A plain nested-field {@code $rename} per key — cheap, and Mongo's {@code
   * $rename} is naturally a no-op on documents that don't carry the old key, so no upfront filter
   * is needed to stay idempotent.
   */
  private long renameAnnotationKeys(MongoDatabase db, String collection) {
    MongoCollection<Document> coll = db.getCollection(collection);
    long updated = 0;
    for (Map.Entry<String, String> rename : ANNOTATION_KEY_RENAMES.entrySet()) {
      updated +=
          coll.updateMany(Filters.exists(rename.getKey()), Updates.rename(rename.getKey(), rename.getValue()))
              .getModifiedCount();
    }
    return updated;
  }

  /**
   * H14-d: renames the {@code teams} collection ({@code WorkspaceEntity}'s pre-H14 {@code
   * @Document} name) to {@code workspaces}. Every v3/v4/fresh-install unit earlier in this chain
   * (_0003/_0007/_0008/_0012/_0013) deliberately still targets the literal {@code teams} name —
   * this rename runs once, here, after all of them have written to it, so no earlier unit needs
   * to special-case a collection name that does not exist until this point in the chain.
   *
   * @return true if the rename actually happened (false when already renamed, or when there was
   *     never a {@code teams} collection to rename — both no-ops).
   */
  private boolean renameTeamsCollection(MongoDatabase db, CollectionNames names) {
    String oldName = names.resolve("teams");
    String newName = names.resolve("workspaces");
    List<String> existing = new ArrayList<>();
    db.listCollectionNames().into(existing);
    if (!existing.contains(oldName)) {
      return false;
    }
    if (existing.contains(newName)) {
      LOG.warn(
          "Both {} and {} exist — leaving both alone for investigation rather than guessing"
              + " which is authoritative.",
          oldName,
          newName);
      return false;
    }
    db.getCollection(oldName).renameCollection(new MongoNamespace(db.getName(), newName));
    return true;
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // Value/id renames are not restored - "workspace" is authoritative going forward, matching
    // the other online migrations' rollback scope (see _0015__DispatcherRename).
  }
}

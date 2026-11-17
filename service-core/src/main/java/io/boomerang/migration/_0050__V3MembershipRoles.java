package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Gives every user's workspace membership without a role the role v3 gave it. {@link
 * _0013__V3BuildRelationshipGraph} writes each {@code user:<id> --memberOf--> workspace:<id>} edge
 * of a personal workspace and of a v3 team with no {@code data.role}, and a membership whose role
 * is not a seeded workspace role grants nothing, so an upgraded user who is not an admin could
 * not work in their own workspace or their teams'. In v3 a user owned their personal team and a
 * team member worked on the team's workflows: a membership of the user's own personal workspace
 * (a {@code workspaces} document with {@code type=personal} whose {@code externalRef} is the
 * user's id, the linkage {@link _0008__V3MigrateUsers} writes) becomes {@code owner}, any other
 * becomes {@code editor}.
 *
 * <p>Runs on every install, not only a v3 upgrade, so an install that ran the chain before this
 * unit existed is repaired too. An edge that has a role is never touched, and each write is
 * conditional on the role still being absent, so a second run finds nothing. An empty database
 * and an install built by v5 have no role-less membership, since the application always writes a
 * role.
 */
@Change(id = "0050-v3-membership-roles", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0050__V3MembershipRoles {

  private static final Logger LOG = LoggerFactory.getLogger(_0050__V3MembershipRoles.class);

  static final String PERSONAL_ROLE = "owner";
  static final String TEAM_ROLE = "editor";

  // A user's membership whose data.role is absent, null or empty ($in with null matches absent).
  static final Bson ROLE_LESS_MEMBERSHIP =
      Filters.and(
          Filters.eq("label", "memberOf"),
          Filters.regex("from", "^user:"),
          Filters.in("data.role", Arrays.asList(null, "")));

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> edges = db.getCollection(names.resolve("rel_edges"));
    List<Document> roleLess = edges.find(ROLE_LESS_MEMBERSHIP).into(new ArrayList<>());
    if (roleLess.isEmpty()) {
      LOG.info("No workspace membership without a role");
      return;
    }

    Map<String, String> ownerByPersonalWorkspace = new HashMap<>();
    for (Document workspace :
        db.getCollection(names.resolve("workspaces"))
            .find(Filters.and(Filters.eq("type", "personal"), Filters.ne("externalRef", null)))
            .projection(Projections.include("_id", "externalRef"))) {
      ownerByPersonalWorkspace.put(workspace.get("_id").toString(), workspace.getString("externalRef"));
    }

    int owners = 0;
    int editors = 0;
    for (Document edge : roleLess) {
      String role = roleFor(edge.getString("from"), edge.getString("to"), ownerByPersonalWorkspace);
      Object data = edge.get("data");
      // data.role cannot be set inside a null or missing data; the whole data is set instead.
      Bson update =
          data instanceof Document
              ? Updates.set("data.role", role)
              : Updates.set("data", new Document("role", role));
      long updated =
          edges
              .updateOne(Filters.and(Filters.eq("_id", edge.get("_id")), ROLE_LESS_MEMBERSHIP), update)
              .getModifiedCount();
      if (updated == 0) {
        continue;
      }
      if (PERSONAL_ROLE.equals(role)) {
        owners++;
      } else {
        editors++;
      }
    }
    LOG.info(
        "Gave {} personal workspace membership(s) the {} role and {} other membership(s) the {} role",
        owners,
        PERSONAL_ROLE,
        editors,
        TEAM_ROLE);
  }

  // owner when the workspace is the member's own personal workspace, editor otherwise.
  static String roleFor(String from, String to, Map<String, String> ownerByPersonalWorkspace) {
    String userId = from.substring("user:".length());
    String workspaceId = to != null && to.startsWith("workspace:") ? to.substring("workspace:".length()) : null;
    return workspaceId != null && userId.equals(ownerByPersonalWorkspace.get(workspaceId))
        ? PERSONAL_ROLE
        : TEAM_ROLE;
  }

  @Rollback
  public void rollback() {
    // Forward-only: a membership without a role grants nothing, and members may have been given
    // other roles since.
  }
}

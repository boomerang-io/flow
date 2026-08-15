package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;

/**
 * Seed the five roles the authorization layer resolves against — {@code RoleRepository} looks them
 * up by {@code type} + {@code name}, and {@code RelationshipService.checkPermissions} matches the
 * glob-style permission strings. There is no Java-side default: with an empty {@code roles}
 * collection no permission check can resolve, so a fresh install has to be given these.
 *
 * <p>Ported from the legacy loader's {@code flow/4023/*.json}, with the {@code AuthScope} value
 * {@code team} mapped to {@code workspace} per DD-01 (the same convention {@code
 * _0012__WorkspaceRename} applies to stored role documents):
 *
 * <ul>
 *   <li>{@code workspace}/owner — {@code **}/{@code **}
 *   <li>{@code workspace}/editor — read, write, action
 *   <li>{@code workspace}/reader — read
 *   <li>{@code global}/admin — {@code **}/{@code **}
 *   <li>{@code global}/operator — read, write, action
 * </ul>
 *
 * <p>Guarded on {@code type} + {@code name}, so an install that already carries a role (including
 * one an operator has since edited) keeps its own definition untouched.
 */
@Change(id = "0015-seed-roles", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0015__SeedRoles {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    List<Document> roles = SeedResources.load("seed/roles.json");
    int inserted = 0;
    for (Document role : roles) {
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("roles"),
          Filters.and(
              Filters.eq("type", role.getString("type")),
              Filters.eq("name", role.getString("name"))),
          role)) {
        inserted++;
      }
    }
    SeedResources.logSeeded("roles", inserted, roles.size());
  }

  @Rollback
  public void rollback() {
    // Removing roles would break every permission check on an install already using them.
  }
}

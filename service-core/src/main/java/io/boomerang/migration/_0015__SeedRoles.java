package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.springframework.core.env.Environment;

/**
 * Seed the five roles the authorization layer resolves against — {@code RoleRepository} looks them
 * up by {@code type} + {@code name}, and {@code RelationshipService.checkPermissions} matches the
 * glob-style permission strings. There is no Java-side default: with an empty {@code roles}
 * collection no permission check can resolve, so a fresh install has to be given these.
 *
 * <p>Ported from the legacy loader's {@code flow/4023/*.json}, with the {@code AuthScope} value
 * {@code team} written as {@code workspace}:
 *
 * <ul>
 *   <li>{@code workspace}/owner — {@code **}/{@code **}
 *   <li>{@code workspace}/editor — read, write, action
 *   <li>{@code workspace}/reader — read
 *   <li>{@code global}/admin — {@code **}/{@code **}
 *   <li>{@code global}/operator — read, write, action
 * </ul>
 *
 * <p>Guarded on {@code type} + {@code name}, so a re-run, or a role an operator has since edited,
 * keeps the stored definition untouched.
 */
@Change(id = "0015-seed-roles", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0015__SeedRoles {

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
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

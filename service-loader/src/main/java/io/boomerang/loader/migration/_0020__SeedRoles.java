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
 * _0016__WorkspaceRename} applies to stored role documents):
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
 *
 * <p><b>Must run AFTER {@code _0016__WorkspaceRename}</b> - the only ordering constraint that
 * kept this seed out of the early bootstrap group ({@code _0002__SeedRelationshipRoot}/{@code
 * _0003__SeedSystemWorkspace}, moved ahead of the v3 migration for a different reason - see
 * {@code _0003}'s own javadoc). A pre-existing role still carrying the legacy {@code team} type
 * (an upgraded v4 install, or a role written before {@code _0016} ran) does NOT natural-key-match
 * this seed's {@code workspace}-typed content, so seeding BEFORE the rename would insert a
 * duplicate {@code workspace/owner} etc. alongside the not-yet-renamed {@code team/owner} -
 * verified against {@code LoaderMigrationTest}'s v4-shaped fixture, which failed with exactly one
 * extra role (6 instead of 5) when this seed was briefly moved ahead of the rename during this
 * restructure. Running after {@code _0016} means every legacy {@code team}-typed role has already
 * become {@code workspace}-typed, so the natural-key match (and skip) works correctly.
 */
@Change(id = "0020-seed-roles", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0020__SeedRoles {

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

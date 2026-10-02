package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Lower-cases every {@code users.email} so {@code UserService}'s exact-match lookups find every
 * account and can seek the {@code users.email_unique} index built by {@link _0021__Indexes}.
 *
 * <p><b>Why.</b> A case-insensitive lookup is an {@code $options:'i'} regex, for which MongoDB
 * cannot compute index bounds, so it can only scan an index end-to-end, never seek it. {@code
 * UserService} therefore stores emails already lower-cased ({@code Locale.ROOT}) and queries them
 * with plain equality; this unit brings rows written before that rule - the v3 users {@link
 * _0008__V3MigrateUsers} passes through verbatim - up to the same shape, in one place. It runs
 * before {@link _0013__V3BuildRelationshipGraph}, so user nodes are slugged by the lower-cased
 * address.
 *
 * <p><b>Collisions are reported, never resolved.</b> Two users whose emails differ only by case
 * ({@code Ada@example.com} and {@code ada@example.com}) become one value once lower-cased. Merging
 * or deleting an account is a data decision this migration has no mandate to make, so every
 * document in a colliding group is left EXACTLY as it is and the group is logged at {@code ERROR}
 * with each colliding {@code _id} and its stored email. Leaving them is also what lets {@link
 * _0021__Indexes} build the UNIQUE {@code email_unique} index: lower-cased, the pair would share a
 * value and the build would fail, stopping startup. Those users keep their mixed-case address and
 * are not found by the exact-match lookup until an operator resolves the duplicate. No index is
 * added or changed here.
 *
 * <p><b>Idempotency.</b> The update matches only documents whose email is not already equal to its
 * own lower-cased form, so a second (or third) execution against an already-normalised collection
 * modifies nothing. Documents with a missing or non-string {@code email} are excluded by an
 * explicit {@code $type} guard rather than relying on {@code $toLower}'s null-to-empty-string
 * coercion, which would otherwise rewrite them to {@code ""}.
 */
@Change(id = "0009-normalise-user-emails", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0009__NormaliseUserEmails {

  private static final Logger LOG = LoggerFactory.getLogger(_0009__NormaliseUserEmails.class);

  private static final Document LOWERCASED_EMAIL = new Document("$toLower", "$email");

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    MongoCollection<Document> users = db.getCollection(names.resolve("users"));

    Set<Object> collidingIds = reportCollisions(users);

    Document filter =
        new Document("email", new Document("$type", "string"))
            .append("$expr", new Document("$ne", List.of("$email", LOWERCASED_EMAIL)));
    if (!collidingIds.isEmpty()) {
      filter.append("_id", new Document("$nin", new ArrayList<>(collidingIds)));
    }

    UpdateResult result =
        users.updateMany(filter, List.of(new Document("$set", new Document("email", LOWERCASED_EMAIL))));

    LOG.info(
        "users.email normalised to lower case — {} document(s) rewritten, {} left untouched as"
            + " case collisions",
        result.getModifiedCount(),
        collidingIds.size());
  }

  /**
   * Finds groups of users that would share one email once lower-cased and logs each one with its
   * member {@code _id}s and stored emails.
   *
   * @return the {@code _id}s of every document in a colliding group — all of which this unit leaves
   *     unmodified
   */
  private Set<Object> reportCollisions(MongoCollection<Document> users) {
    List<Document> collisions = new ArrayList<>();
    users
        .aggregate(
            List.of(
                new Document("$match", new Document("email", new Document("$type", "string"))),
                new Document(
                    "$group",
                    new Document("_id", LOWERCASED_EMAIL)
                        .append("count", new Document("$sum", 1))
                        .append("ids", new Document("$push", "$_id"))
                        .append("emails", new Document("$push", "$email"))),
                new Document("$match", new Document("count", new Document("$gt", 1)))))
        .allowDiskUse(true)
        .into(collisions);

    Set<Object> collidingIds = new LinkedHashSet<>();
    for (Document collision : collisions) {
      List<Object> ids = collision.getList("ids", Object.class);
      collidingIds.addAll(ids);
      LOG.error(
          "users.email case collision on '{}' — {} accounts share this address once lower-cased and"
              + " have therefore been LEFT UNCHANGED (mixed case, and so unreachable by the"
              + " exact-match lookup) rather than merged or deleted. Resolve manually, then re-run"
              + " this change unit. Colliding _id -> email: {}",
          collision.get("_id"),
          ids.size(),
          renderCollision(ids, collision.getList("emails", Object.class)));
    }
    return collidingIds;
  }

  private String renderCollision(List<Object> ids, List<Object> emails) {
    StringBuilder rendered = new StringBuilder();
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        rendered.append(", ");
      }
      rendered.append(ids.get(i)).append(" -> '").append(emails.get(i)).append('\'');
    }
    return rendered.toString();
  }

  @Rollback
  public void rollback() {
    // Not reversible - the original casing is not recorded anywhere, and nothing reads it. Every
    // consumer of users.email is case-insensitive by intent, so the lower-cased value is a
    // complete replacement rather than a lossy one.
  }
}

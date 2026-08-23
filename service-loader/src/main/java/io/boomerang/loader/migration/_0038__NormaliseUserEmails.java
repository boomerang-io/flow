package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import io.boomerang.loader.CollectionNames;
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

/**
 * Lower-cases every {@code users.email} so the {@code users.email_lookup} index built by {@code
 * _0036__RelationshipAndAuditIndexes} can actually seek.
 *
 * <p><b>Why.</b> Every email lookup used to be a {@code ...IgnoreCase} derived query, which Spring
 * Data renders as an {@code $options:'i'} regex. MongoDB cannot compute index bounds for a
 * case-insensitive regex, so {@code email_lookup} could only ever be scanned end-to-end, never
 * sought. {@code UserService} now stores emails already lower-cased ({@code Locale.ROOT}) and
 * queries them with plain equality; this unit brings rows written before that rule up to the same
 * shape. {@code _0008__V3MigrateUsers} is deliberately left as a verbatim v3 pass-through — it runs
 * earlier in this same pipeline, so its output lands here and is normalised in one place.
 *
 * <p><b>Collisions are reported, never resolved.</b> Two users whose emails differ only by case
 * ({@code Ada@example.com} and {@code ada@example.com}) become one value once lower-cased. Merging
 * or deleting an account is a data decision this migration has no mandate to make, so every
 * document in a colliding group is left EXACTLY as it is and the group is logged at {@code ERROR}
 * with each colliding {@code _id} and its stored email. Skipping them is also what keeps the run
 * green on a V3-generation install: {@code _0019__DomainIndexes} builds a UNIQUE {@code
 * email_unique} index there, and lower-casing a colliding pair underneath it would fail with
 * {@code E11000} and abort the deploy. Those users keep their mixed-case address and remain
 * findable only after an operator resolves the duplicate; {@code email_lookup} itself is
 * non-unique, so the index is unaffected either way. No unique index is added or changed here.
 *
 * <p><b>Idempotency.</b> The update matches only documents whose email is not already equal to its
 * own lower-cased form, so a second (or third) execution against an already-normalised collection
 * modifies nothing. Documents with a missing or non-string {@code email} are excluded by an
 * explicit {@code $type} guard rather than relying on {@code $toLower}'s null-to-empty-string
 * coercion, which would otherwise rewrite them to {@code ""}.
 */
@Change(id = "0038-normalise-user-emails", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0038__NormaliseUserEmails {

  private static final Logger LOG = LoggerFactory.getLogger(_0038__NormaliseUserEmails.class);

  private static final Document LOWERCASED_EMAIL = new Document("$toLower", "$email");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
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

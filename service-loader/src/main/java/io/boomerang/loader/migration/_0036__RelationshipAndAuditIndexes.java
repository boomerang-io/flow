package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndexKeys;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;

/**
 * The authz-walk, audit-trail and user-lookup indexes, which until now existed only as inert
 * {@code @Indexed}/{@code @CompoundIndex} annotations (or not at all). {@code
 * spring.data.mongodb.auto-index-creation=false} in {@code service-core} makes every entity
 * annotation a no-op — the loader is the sole index authority — so on a fresh v5 install each of
 * these lookups is a collection scan. A v4 install ran with {@code auto-index-creation=true} (see
 * {@code service-flow/src/main/resources/application.properties:19} at tag {@code
 * flow@4.0.0-beta.330}) and therefore DOES carry Spring-built equivalents, so every index below is
 * created via {@link MigrationUtils#ensureIndexKeys}: an index with the same key pattern under any
 * name is kept and reported rather than rebuilt under a loader-chosen name (which would fail with
 * {@code IndexOptionsConflict}).
 *
 * <p><b>Why the relationship keys are descending.</b> {@code ensureIndexKeys} matches on the exact
 * key document, and the v4-built indexes are descending ({@code type_slug_idx = {type:-1,
 * slug:-1}}, {@code type_ref_idx}, {@code to_label_idx}). These are equality-only lookups with no
 * sort, so direction is semantically irrelevant to the query planner — mirroring the entity
 * annotations' direction is what makes the v4 dedupe actually fire instead of leaving every
 * upgraded install carrying two indexes over the same fields. The audit and users indexes are
 * ascending because their v4 counterparts ({@code @Indexed}) are.
 *
 * <ul>
 *   <li>{@code rel_nodes.type_slug {type:-1, slug:-1}} and {@code rel_nodes.type_ref {type:-1,
 *       ref:-1}} — every {@code RelationshipNodeRepository} finder is the shape {@code {'type': ?0,
 *       '$or': [{'slug': ?1},{'ref': ?1}]}} ({@code existsByTypeAndRefOrSlug}, {@code
 *       findByTypeAndRefOrSlug}, {@code findOneByTypeAndRefOrSlug}, {@code
 *       updateSlugByTypeAndRefOrSlug}, {@code deleteByRefOrSlug}). Mongo plans an {@code $or} as a
 *       union of one index scan per branch, so BOTH indexes are required — with only one of them
 *       the whole {@code $or} degrades to a collection scan. {@code deleteByTypeAndRef} uses the
 *       {@code type_ref} pair directly.
 *   <li>{@code rel_edges.from_label {from:-1, label:-1}} — {@code findByFromAndLabel}, the anchored
 *       downward walk. NEW even on v4: {@code from_to_idx}/{@code from_to_label_idx} lead with
 *       {@code from} but carry {@code to} ahead of {@code label}, so a {@code (from, label)} query
 *       can only seek on {@code from} and must scan every edge out of that node. Also serves
 *       {@code findByFromIn} and the {@code from} branch of {@code deleteByFromOrTo} by prefix.
 *   <li>{@code rel_edges.to_label {to:-1, label:-1}} — {@code findByToAndLabel}, the upward walk,
 *       plus the {@code to} branch of {@code deleteByFromOrTo} by prefix. Matches v4's {@code
 *       to_label_idx} exactly, so an upgrade keeps that index and only a fresh install builds one.
 *   <li>{@code audit.scope_self_ref {scope:1, selfRef:1}}, {@code audit.scope_self_name {scope:1,
 *       selfName:1}}, {@code audit.scope_parent {scope:1, parent:1}} — the three {@code
 *       AuditRepository} finders ({@code findFirstByScopeAndSelfRef}, {@code
 *       findFirstByScopeAndSelfName}, {@code findByScopeAndParent}). v4's four single-field {@code
 *       @Indexed} indexes ({@code scope}, {@code selfRef}, {@code selfName}, {@code parent}) have
 *       different key patterns, so these compounds are created alongside them; a fresh install has
 *       nothing at all.
 *   <li>{@code users.email_lookup {email:1}}, NON-unique — {@code _0019__DomainIndexes}' unique
 *       {@code email_unique} is gated to {@code InstallGeneration.V3}, so fresh v5 installs get no
 *       email index from the loader at all. The unique one deliberately stays V3-only (no
 *       fresh-install dedupe audit has been done, and {@link MigrationUtils#ensureIndex} now aborts
 *       the deploy on a unique-index build failure); this adds the plain lookup for everyone.
 * </ul>
 *
 * <p><b>Former limit on {@code users.email_lookup}, since closed.</b> Every call site used to be
 * case-insensitive ({@code findByEmailIgnoreCase}, {@code findByEmailIgnoreCaseAndStatus}, {@code
 * countByEmailIgnoreCaseAndStatus}), which Spring Data renders as an {@code $options:'i'} regex.
 * MongoDB cannot compute index bounds for a case-insensitive regex, so this index only ever turned
 * the login lookup from a COLLSCAN into a full IXSCAN — a real win on a small, hot collection, but
 * not a seek. The "normalised lower-case email" option noted here as out of scope was taken:
 * {@code UserService} now lower-cases on write and queries with plain equality, and {@code
 * _0038__NormaliseUserEmails} back-fills existing rows, so this index seeks.
 */
@Change(id = "0036-relationship-and-audit-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0036__RelationshipAndAuditIndexes {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String relNodes = names.resolve("rel_nodes");
    ensureIndexKeys(
        db,
        relNodes,
        "type_slug",
        new Document("type", -1).append("slug", -1),
        new IndexOptions());
    ensureIndexKeys(
        db, relNodes, "type_ref", new Document("type", -1).append("ref", -1), new IndexOptions());

    String relEdges = names.resolve("rel_edges");
    ensureIndexKeys(
        db,
        relEdges,
        "from_label",
        new Document("from", -1).append("label", -1),
        new IndexOptions());
    ensureIndexKeys(
        db, relEdges, "to_label", new Document("to", -1).append("label", -1), new IndexOptions());

    String audit = names.resolve("audit");
    ensureIndexKeys(
        db,
        audit,
        "scope_self_ref",
        new Document("scope", 1).append("selfRef", 1),
        new IndexOptions());
    ensureIndexKeys(
        db,
        audit,
        "scope_self_name",
        new Document("scope", 1).append("selfName", 1),
        new IndexOptions());
    ensureIndexKeys(
        db, audit, "scope_parent", new Document("scope", 1).append("parent", 1), new IndexOptions());

    ensureIndexKeys(
        db, names.resolve("users"), "email_lookup", new Document("email", 1), new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("rel_nodes"), "type_slug");
    dropIndex(db, names.resolve("rel_nodes"), "type_ref");
    dropIndex(db, names.resolve("rel_edges"), "from_label");
    dropIndex(db, names.resolve("rel_edges"), "to_label");
    dropIndex(db, names.resolve("audit"), "scope_self_ref");
    dropIndex(db, names.resolve("audit"), "scope_self_name");
    dropIndex(db, names.resolve("audit"), "scope_parent");
    dropIndex(db, names.resolve("users"), "email_lookup");
  }
}

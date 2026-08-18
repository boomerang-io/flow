package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T6-3: restructures the token model from scope-typed ({@code AuthScope} doing double duty as
 * both the token's class AND each grant's scope) to actor/ceiling-typed (see {@code
 * specifications/merge-execution-plan.md}, ruling T6-3, for the full design).
 *
 * <p>Two of the four pre-restructure token classes are RETIRED outright with no deprecation
 * window (maintainer ruling): {@code workspace} (renamed to {@code key}) and {@code workflow}
 * (folded into {@code key} + {@code actorKind=WORKFLOW}). Their raw-token prefixes ({@code bft_},
 * {@code bfw_}) are dropped from {@code TokenTypePrefix}'s pre-DB shape gate at the same time (see
 * that class), so a token minted under either prefix can never authenticate again — only the
 * SHA-256 hash of the full raw token is ever stored, so there is no way to rewrite the raw token
 * itself onto a new prefix. Migrating {@code tokens.type="workspace"|"workflow"} records forward
 * to {@code key} would therefore leave an un-authenticatable zombie row (a credential that can
 * never present a bearer matching its own hash again) sitting in "active tokens" listings
 * forever. Instead, this unit DELETES them outright and logs the count so an operator knows how
 * many principals need a token re-issued (the same "operators re-issue" posture {@code
 * _0026__TokenIndexes} already documents for legacy v3 tokens).
 *
 * <p><b>{@code global}/{@code user}/{@code session} tokens are completely unaffected</b> — their
 * prefixes and classes are unchanged, so they keep working with no migration at all. This unit's
 * filter is narrow by construction ({@code type} exactly {@code "team"} - the pre-DD-01 spelling
 * of the retired workspace class, which {@code _0016__WorkspaceRename} no longer rewrites -,
 * {@code "workspace"} - the spelling on databases where an earlier revision of {@code _0016} did
 * rewrite it -, or {@code "workflow"}) so it cannot touch them.
 *
 * <p><b>Why no other data needs migrating.</b> {@code ResolvedPermissions.scope} (now typed
 * {@code PermissionScope} instead of the former overloaded {@code AuthScope}) only ever took the
 * values {@code workspace}/{@code global} for a surviving ({@code global}/{@code user}/{@code
 * session}-typed) token — {@code workflow}-scoped grants only ever existed inside {@code
 * workflow}-typed token documents, which this unit deletes wholesale, taking their embedded
 * grants with them. {@code roles.type} is a separate, already-correct field: it only ever holds
 * {@code workspace}/{@code global} (seeded by {@code _0020__SeedRoles}; renamed from the legacy
 * {@code team} value by {@code _0016__WorkspaceRename}), so the {@code AuthScope}→{@code
 * PermissionScope} split is a Java-type-only change there — no document rewrite needed. (Real
 * v3-dump verification: the dump's own legacy {@code tokens} collection is a different, v3-only
 * shape dropped outright by {@code _0004__V3DropDeadCollections} — never migrated forward — and
 * the dump carries no pre-existing {@code roles} collection at all, so this unit's behaviour is
 * proven against {@code LoaderMigrationTest}'s synthetic fixture, not the real dump.)
 *
 * <p>Idempotent: the delete filter only ever matches documents still carrying a retired {@code
 * type}, so a second run finds nothing and deletes nothing.
 */
@Change(id = "0028-token-class-restructure", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0028__TokenClassRestructure {

  private static final Logger LOG = LoggerFactory.getLogger(_0028__TokenClassRestructure.class);

  private static final List<String> RETIRED_TYPES = List.of("team", "workspace", "workflow");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String tokens = names.resolve("tokens");
    List<String> existing = new ArrayList<>();
    db.listCollectionNames().into(existing);
    if (!existing.contains(tokens)) {
      LOG.info("Skipping token class restructure — {} does not exist yet.", tokens);
      return;
    }

    MongoCollection<Document> collection = db.getCollection(tokens);
    long deleted = collection.deleteMany(Filters.in("type", RETIRED_TYPES)).getDeletedCount();
    LOG.info(
        "Token class restructure (T6-3) — deleted {} token(s) of retired class {} (re-issue"
            + " required for any affected principal); global/user/session tokens untouched.",
        deleted,
        RETIRED_TYPES);
  }

  @Rollback
  public void rollback() {
    // Destructive delete of tokens whose raw bearer can never authenticate again post-restructure
    // (the prefix gate no longer accepts bft/bfw) - not restorable, matching this chain's other
    // forward-only cleanup units (e.g. _0004, _0014, _0027).
  }
}

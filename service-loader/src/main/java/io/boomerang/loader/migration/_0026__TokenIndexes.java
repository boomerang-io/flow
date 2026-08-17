package io.boomerang.loader.migration;

import static io.boomerang.loader.migration.MigrationUtils.dropIndex;
import static io.boomerang.loader.migration.MigrationUtils.ensureIndex;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.HashSet;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T6-1: {@code tokens.token} (the SHA-256 hash every bearer lookup keys on — session auth, API
 * token auth, and now {@code DispatcherAuthFilter}) has never had an index; every lookup has been
 * a full collection scan. Adding a dispatcher-token caller onto that same hot, unindexed path is
 * what makes this worth fixing now rather than folding it into a future general index pass.
 *
 * <p><b>Deliberately NOT unique</b> (T6-2 posture: a unique index here would need a dedupe pass
 * first, and no such audit has been done for this collection in this track — a plain index fully
 * serves the equality-lookup performance need without taking on that extra correctness
 * commitment). Practically, a hash collision between two independently-minted tokens is not a
 * real-world concern.
 *
 * <p><b>Only runs if {@code tokens} already exists.</b> Building an index on a MISSING collection
 * implicitly creates it, empty — which would resurrect the collection {@code
 * _0004__V3DropDeadCollections} deliberately drops for a v3-sourced install (legacy tokens are a
 * different shape and are never migrated forward — operators re-issue post-migration). A genuine
 * fresh install with no tokens yet simply picks the index up on its next deploy, once the
 * collection exists (the loader reruns every deploy, per DD-07).
 */
@Change(id = "0026-token-indexes", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0026__TokenIndexes {

  private static final Logger LOG = LoggerFactory.getLogger(_0026__TokenIndexes.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    String tokens = names.resolve("tokens");
    Set<String> existing = new HashSet<>();
    db.listCollectionNames().into(existing);
    if (!existing.contains(tokens)) {
      LOG.info("Skipping token_hash_lookup index — {} does not exist yet.", tokens);
      return;
    }
    ensureIndex(db, tokens, "token_hash_lookup", new Document("token", 1), new IndexOptions());
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    dropIndex(db, names.resolve("tokens"), "token_hash_lookup");
  }
}

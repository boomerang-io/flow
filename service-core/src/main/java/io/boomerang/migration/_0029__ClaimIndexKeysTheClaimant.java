package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Re-keys the task-run claim index to the inventory's {@code type, status, phase, claim.by,
 * creationDate}. The termination check looks for the cancelled, timed-out and parked task runs a
 * dispatcher still holds; keyed without {@code claim.by}, it fetched every cancelled or timed-out
 * task run of the last seven days to test the claim. A database that built the index before
 * {@code claim.by} joined it has it rebuilt here; an empty or a v3 database already has the new
 * keys from {@link _0021__Indexes}, and a second run finds them.
 */
@Change(id = "0029-claim-index-keys-the-claimant", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0029__ClaimIndexKeysTheClaimant {

  private static final Logger LOG = LoggerFactory.getLogger(_0029__ClaimIndexKeysTheClaimant.class);

  static final _0021__Indexes.Index CLAIM_PAGE =
      _0021__Indexes.INVENTORY.stream()
          .filter(index -> "task_runs".equals(index.collection()))
          .filter(index -> "claim_page".equals(index.name()))
          .findFirst()
          .orElseThrow();

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    MongoCollection<Document> taskRuns =
        db.getCollection(CollectionNames.from(env).resolve(CLAIM_PAGE.collection()));
    Document current = null;
    for (Document index : taskRuns.listIndexes()) {
      if (CLAIM_PAGE.name().equals(index.getString("name"))) {
        current = index;
      }
    }
    List<String> wanted = List.copyOf(CLAIM_PAGE.keys().keySet());
    if (current != null
        && List.copyOf(current.get("key", Document.class).keySet()).equals(wanted)) {
      LOG.info("{} already keyed {}", CLAIM_PAGE.name(), wanted);
      return;
    }
    // Dropped and built again; a start that stops in between finds no index and builds it.
    if (current != null) {
      taskRuns.dropIndex(CLAIM_PAGE.name());
    }
    MigrationUtils.ensureIndex(
        db,
        taskRuns.getNamespace().getCollectionName(),
        CLAIM_PAGE.name(),
        CLAIM_PAGE.keys(),
        CLAIM_PAGE.options());
    LOG.info("{} re-keyed {}", CLAIM_PAGE.name(), wanted);
  }

  @Rollback
  public void rollback() {
    // The earlier keys only made the termination check slower; nothing reads them.
  }
}

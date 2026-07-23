package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record in the audit log whether this database is an existing installation or a fresh one.
 * An existing installation is detected by the legacy loader's Mongock changelog
 * ({@code sys_changelog_flow}) or by seeded workflows. Detection only — no gating: every
 * subsequent change unit is idempotent regardless of prior state.
 */
@Change(id = "0001-baseline-existing-install", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0001__BaselineExistingInstall {

  private static final Logger LOG = LoggerFactory.getLogger(_0001__BaselineExistingInstall.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    long legacyChangeSets =
        db.getCollection(names.resolve("sys_changelog_flow")).countDocuments();
    long workflows = db.getCollection(names.resolve("workflows")).countDocuments();
    if (legacyChangeSets > 0 || workflows > 0) {
      LOG.info(
          "Existing installation detected — {} legacy loader changesets, {} workflows. "
              + "Subsequent change units reconcile the live schema in place.",
          legacyChangeSets,
          workflows);
    } else {
      LOG.info("Fresh database — no legacy loader history and no workflows.");
    }
  }

  @Rollback
  public void rollback() {
    // Detection only — nothing to undo.
  }
}

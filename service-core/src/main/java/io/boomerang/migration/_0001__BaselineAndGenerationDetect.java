package io.boomerang.migration;

import com.mongodb.client.MongoDatabase;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * First unit of the chain, before anything is written: refuse a database the chain cannot
 * upgrade, then record the install generation once so every later unit gets a stable answer.
 *
 * <p>The chain migrates exactly two starting points: an empty database and a v3 install. A v4
 * install, or a database a 5.0 beta already migrated (it holds the beta's {@code
 * sys_changelog_loader}), stops startup here with a message that says why. The check reruns on
 * every start ({@link RecoveryStrategy#ALWAYS_RETRY}), so pointing the service at the right
 * database is enough to recover.
 *
 * <p>The generation is read from the legacy Mongock changelog ({@code sys_changelog_flow}), which
 * keeps answering {@link InstallGeneration#V3} long after the upgrade; {@link
 * LegacyGenerationMarker} records the answer once so later runs are not re-derived from it.
 */
@Change(id = "0001-baseline-and-generation-detect", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0001__BaselineAndGenerationDetect {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0001__BaselineAndGenerationDetect.class);

  /** The change log a 5.0 beta's separate loader kept. */
  static final String BETA_CHANGELOG = "sys_changelog_loader";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    refuseUnsupportedDatabase(db, names);
    logExistingInstallation(db, names);
    recordGeneration(db, names);
  }

  private void refuseUnsupportedDatabase(MongoDatabase db, CollectionNames names) {
    if (db.getCollection(names.resolve(BETA_CHANGELOG)).countDocuments() > 0) {
      throw new IllegalStateException(
          "This database was migrated by a 5.0 beta ("
              + names.resolve(BETA_CHANGELOG)
              + " exists). Beta databases cannot be upgraded: start from an empty database or"
              + " restore the v3 database it came from.");
    }
    if (InstallGeneration.detect(db, names) == InstallGeneration.V4) {
      throw new IllegalStateException(
          "This database was installed by Flow 4, or by a loader this release does not recognise"
              + " ("
              + names.resolve("sys_changelog_flow")
              + " has no v3 completion marker). Only an empty database or a Flow 3 database can be"
              + " upgraded in place.");
    }
  }

  private void logExistingInstallation(MongoDatabase db, CollectionNames names) {
    long legacyChangeSets = db.getCollection(names.resolve("sys_changelog_flow")).countDocuments();
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

  private void recordGeneration(MongoDatabase db, CollectionNames names) {
    InstallGeneration generation = LegacyGenerationMarker.recordOnce(db, names);
    LOG.info(
        "Install generation recorded as {} in {}",
        generation,
        names.resolve(LegacyGenerationMarker.COLLECTION));
  }

  @Rollback
  public void rollback() {
    // Detection only - nothing to undo.
  }
}

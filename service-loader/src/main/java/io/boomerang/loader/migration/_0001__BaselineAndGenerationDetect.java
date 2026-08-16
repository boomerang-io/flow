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
 * FIRST unit of the whole chain — must run before any other change unit mutates the database (see
 * "v3 → v5 migration consolidation" in {@code specifications/merge-execution-plan.md}, "Post-G
 * consolidation review"). Merges the two units that previously ran at opposite ends of the chain
 * ({@code _0001__BaselineExistingInstall}, which only ever logged, and {@code
 * _0019__LegacyGenerationDetect}, which recorded the durable generation marker after 18 other
 * units had already run) — both are detect-and-record-only, and nothing gates on the first half's
 * output, so combining them costs nothing and buys every later unit a STABLE generation answer
 * from change unit #1 instead of #19.
 *
 * <p><b>Half 1 — existing-installation logging.</b> Detected by the legacy loader's Mongock
 * changelog ({@code sys_changelog_flow}) or by seeded workflows. Detection only — no gating: every
 * subsequent change unit is idempotent regardless of prior state.
 *
 * <p><b>Half 2 — durable {@link InstallGeneration} marker.</b> Recorded — exactly once — via
 * {@link LegacyGenerationMarker}. Why this needs to happen HERE rather than every later unit
 * calling {@link InstallGeneration#detect} itself: detection reads the legacy loader's {@code
 * sys_changelog_flow} changelog, and this consolidated path never writes that changelog's v4-chain
 * marker ({@code changeId: "4000"}) — it goes straight from v3 to v5. A v3 install's changelog
 * therefore keeps satisfying {@link InstallGeneration#V3}'s detection rule forever, on every future
 * Flamingock run against this database, even long after the v3→v5 migration has fully completed.
 * Capturing the generation ONCE, at the earliest point in the chain (before {@link
 * _0004__V3DropDeadCollections} or any other unit mutates the database), gives every later
 * v3-only/v4-only unit a stable answer to "what generation was this install" instead of a live
 * re-derivation that would stay {@code V3} indefinitely.
 *
 * <p>Detection-and-record only — no gating of its own. Idempotent: {@link
 * LegacyGenerationMarker#recordOnce} is a no-op once the marker exists.
 */
@Change(id = "0001-baseline-and-generation-detect", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0001__BaselineAndGenerationDetect {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0001__BaselineAndGenerationDetect.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    logExistingInstallation(db, names);
    recordGeneration(db, names);
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

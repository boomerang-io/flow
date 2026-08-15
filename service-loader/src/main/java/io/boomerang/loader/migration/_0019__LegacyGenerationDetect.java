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
 * First unit of the consolidated v3→v5 migration path (see "v3 → v5 migration consolidation" in
 * {@code specifications/merge-execution-plan.md}). Records the {@link InstallGeneration} this
 * database was detected under — durably, and exactly once — via {@link LegacyGenerationMarker}.
 *
 * <p>Why this needs its own change unit rather than every later unit calling {@link
 * InstallGeneration#detect} itself: detection reads the legacy loader's {@code sys_changelog_flow}
 * changelog, and this consolidated path never writes that changelog's v4-chain marker ({@code
 * changeId: "4000"}) — it goes straight from v3 to v5. A v3 install's changelog therefore keeps
 * satisfying {@link InstallGeneration#V3}'s detection rule forever, on every future Flamingock run
 * against this database, even long after the v3→v5 migration has fully completed. Capturing the
 * generation ONCE, at the earliest point in the chain (before {@link
 * _0020__V3DropDeadCollections} or any other unit mutates the database), gives every later v3-only
 * unit a stable answer to "was this install ever v3" instead of a live re-derivation that would
 * stay true indefinitely.
 *
 * <p>Detection-and-record only — no gating of its own, matching {@link
 * _0001__BaselineExistingInstall}'s scope. Idempotent: {@link LegacyGenerationMarker#recordOnce}
 * is a no-op once the marker exists.
 */
@Change(id = "0019-legacy-generation-detect", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0019__LegacyGenerationDetect {

  private static final Logger LOG = LoggerFactory.getLogger(_0019__LegacyGenerationDetect.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    InstallGeneration generation = LegacyGenerationMarker.recordOnce(db, names);
    LOG.info(
        "Install generation recorded as {} in {}",
        generation,
        names.resolve(LegacyGenerationMarker.COLLECTION));
  }

  @Rollback
  public void rollback() {
    // Detection only - nothing to undo, matching _0001__BaselineExistingInstall.
  }
}

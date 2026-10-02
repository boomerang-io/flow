package io.boomerang.migration;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoDatabase;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V3-only, before anything is seeded: rename {@code teams} to {@code workspaces}, so the system
 * workspace seed and every v3 unit after it work in the one collection the application reads, and
 * drop the v3 collections nothing reads.
 *
 * <p>Dropped, as verified against a real v3 dump:
 *
 * <ul>
 *   <li>{@code workflows_activity_task} — task-level activity, which has no successor.
 *   <li>{@code jobs}, {@code triggers}, {@code calendars}, {@code paused_trigger_groups}, {@code
 *       locks}, {@code schedulers} — the Quartz job store (flow-prefixed); scheduling no longer
 *       uses Quartz.
 *   <li>{@code locks}, unprefixed — a v3 distributed-lock library's collection.
 *   <li>{@code tasks_locks} — task locks of another shape; locks are ephemeral.
 *   <li>{@code tokens} — tokens of another shape, never carried; operators re-issue them.
 * </ul>
 *
 * <p>Every other v3 collection carries data a later unit migrates; {@code extensions} is still
 * the live collection, and {@code sys_changelog_flow} is the record the install generation is
 * read from.
 */
@Change(id = "0002-v3-prepare-collections", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0002__V3PrepareCollections {

  private static final Logger LOG = LoggerFactory.getLogger(_0002__V3PrepareCollections.class);

  private static final List<String> QUARTZ_COLLECTIONS =
      List.of("jobs", "triggers", "calendars", "paused_trigger_groups", "locks", "schedulers");

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (LegacyGenerationMarker.read(db, names) != InstallGeneration.V3) {
      LOG.info("Not a v3 install — no v3 collections to prepare.");
      return;
    }
    renameTeamsToWorkspaces(db, names);
    long dropped = 0;
    dropped += dropIfPresent(db, names.resolve("workflows_activity_task"));
    for (String quartzCollection : QUARTZ_COLLECTIONS) {
      dropped += dropIfPresent(db, names.resolve(quartzCollection));
    }
    dropped += dropIfPresent(db, names.resolve("tasks_locks"));
    dropped += dropIfPresent(db, names.resolve("tokens"));
    dropped += dropIfPresent(db, "locks");
    LOG.info("v3 dead-collection cleanup — {} documents discarded across dropped collections", dropped);
  }

  /** Rename once; a database already holding {@code workspaces} keeps both for investigation. */
  private void renameTeamsToWorkspaces(MongoDatabase db, CollectionNames names) {
    String teams = names.resolve("teams");
    String workspaces = names.resolve("workspaces");
    List<String> existing = new ArrayList<>();
    db.listCollectionNames().into(existing);
    if (!existing.contains(teams)) {
      return;
    }
    if (existing.contains(workspaces)) {
      throw new IllegalStateException(
          "Both " + teams + " and " + workspaces + " exist; resolve which one holds the v3"
              + " workspaces before upgrading");
    }
    db.getCollection(teams).renameCollection(new MongoNamespace(db.getName(), workspaces));
    LOG.info("Renamed {} to {}", teams, workspaces);
  }

  private long dropIfPresent(MongoDatabase db, String collection) {
    long count = db.getCollection(collection).countDocuments();
    db.getCollection(collection).drop();
    if (count > 0) {
      LOG.info("Dropped v3 dead collection {} ({} documents)", collection, count);
    }
    return count;
  }

  @Rollback
  public void rollback() {
    // Dropped collections are not restorable; this chain is forward-only.
  }
}

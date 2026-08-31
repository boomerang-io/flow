package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repoints every catalogue task still on the retired v4 {@code worker-flow} image lineage to
 * {@code boomerangio/task-flow} — the successor built from the boomerang-io/tasks monorepo, which
 * speaks the v5 task contract ({@code PARAM_NAMES}/{@code PARAM_*} env params, {@code
 * RESULTS_PATH}) as well as the v4 one, so it runs on either platform generation.
 *
 * <p><b>Why.</b> v5 agents no longer serve the {@code /params} projected ConfigMap, so a task
 * running a {@code worker-flow:2.x} image on v5 receives no params at all. Command coverage was
 * verified before this repoint: every worker-executed subcommand the seeds invoke ({@code
 * artifactory, file, github, googledrive, googlesheets, http, ibmessentials, mail, servicenow,
 * shell, slack, system, twilio}) exists module-for-module in the new CLI; the remaining seed
 * subcommands ({@code approval, lock, switch, setwfproperty, setwfstatus, runworkflow,
 * runscheduledworkflow}) are engine-handled task types whose image reference is never used.
 *
 * <p>Two surfaces are rewritten, both matched on the {@code worker-flow} lineage only — {@code
 * box-service}, {@code worker-python}, and other image families are untouched:
 *
 * <ul>
 *   <li>{@code task_revisions.spec.image} — the explicit pins, including the stale internal
 *       registry form ({@code tools.boomerangplatform.net/.../bmrg-worker-flow:...}).
 *   <li>{@code settings} — the {@code default.image} config value, which the majority of catalogue
 *       tasks (no explicit image) inherit at dispatch.
 * </ul>
 *
 * <p><b>Idempotency.</b> Both updates match documents still carrying a {@code worker-flow} value;
 * a rerun against a repointed database modifies nothing. The seed files carry the same target for
 * fresh installs; this unit exists for databases seeded before it.
 */
@Change(id = "0039-repoint-worker-flow-images", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0039__RepointWorkerFlowImages {

  private static final Logger LOG = LoggerFactory.getLogger(_0039__RepointWorkerFlowImages.class);

  static final String TARGET_IMAGE = "boomerangio/task-flow:3.1.0";
  private static final String LINEAGE_PATTERN = "worker-flow";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> revisions = db.getCollection(names.resolve("task_revisions"));
    Bson revisionFilter = Filters.regex("spec.image", LINEAGE_PATTERN);
    UpdateResult revisionResult =
        revisions.updateMany(revisionFilter, Updates.set("spec.image", TARGET_IMAGE));

    // v4 data corruption seen in the wild (and in the seeds): one revision carried the IMAGE
    // string inside spec.command with image null - which would execute the image name as the
    // container command. Clear such commands; the task then runs its arguments against the
    // (repointed) default image like its siblings.
    UpdateResult corruptCommandResult =
        revisions.updateMany(
            Filters.regex("spec.command", LINEAGE_PATTERN),
            Updates.set("spec.command", List.of()));

    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    UpdateResult settingsResult =
        settings.updateMany(
            Filters.elemMatch(
                "config",
                Filters.and(
                    Filters.eq("key", "default.image"), Filters.regex("value", LINEAGE_PATTERN))),
            Updates.set("config.$[cfg].value", TARGET_IMAGE),
            new UpdateOptions()
                .arrayFilters(
                    List.of(
                        Filters.and(
                            Filters.eq("cfg.key", "default.image"),
                            Filters.regex("cfg.value", LINEAGE_PATTERN)))));

    LOG.info(
        "worker-flow images repointed to {} — {} task revision(s), {} settings document(s), {}"
            + " corrupt command array(s) cleared",
        TARGET_IMAGE,
        revisionResult.getModifiedCount(),
        settingsResult.getModifiedCount(),
        corruptCommandResult.getModifiedCount());
  }

  @Rollback
  public void rollback() {
    // Not reversible - the individual 2.x tags are not recorded. The old images remain published
    // on Docker Hub, so an operator can re-pin a specific task revision manually if ever needed.
  }
}

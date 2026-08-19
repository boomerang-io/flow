package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-01 (Team -> Workspace) for the one settings document the rename sweep missed: the workspace
 * quota defaults lived under {@code settings.key="teams"} / {@code name="Team Quotas"}. {@code
 * WorkspaceService.setDefaultQuotas} and {@code WorkspaceWorkflowService} resolve the six {@code
 * max.*} quota entries through that key, so the key is renamed to {@code "workspaces"} (and the
 * display name/description reworded) here, in lock-step with the code constant and {@code
 * seed/settings.json}.
 *
 * <p>The nested {@code config[].key} entries ({@code max.workflow.count} etc.) are unchanged -
 * they never carried the team wording. The {@code features} document's {@code teamQuotas}/{@code
 * teamParameters}/{@code teamManagement}/{@code teamTasks} flag keys are a different document and
 * were left alone here on the reasoning that the webapp reads them by those keys ({@code
 * feature["team.quotas"]}), making that rename a coordinated frontend change. That reasoning is
 * superseded: {@link _0034__WorkspaceFeatureFlagSettingsKeys} renames those four keys (webapp,
 * {@code FeatureService}, and this settings document, all in the same change) to close the gap.
 *
 * <p>Idempotent: only a document still keyed {@code "teams"} is touched. If a {@code "workspaces"}
 * document already exists (a fresh install seeded by {@code _0021__SeedSettings} from the updated
 * seed) alongside a legacy {@code "teams"} one, the legacy document is removed rather than renamed
 * so the key stays unique.
 */
@Change(id = "0032-workspace-quota-settings-key", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0032__WorkspaceQuotaSettingsKey {

  private static final Logger LOG = LoggerFactory.getLogger(_0032__WorkspaceQuotaSettingsKey.class);

  static final String OLD_KEY = "teams";
  static final String NEW_KEY = "workspaces";
  static final String NEW_NAME = "Workspace Quotas";
  static final String NEW_DESCRIPTION =
      "Define default workspace quotas which are referenced unless overridden on the Workspace.";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document legacy = settings.find(Filters.eq("key", OLD_KEY)).first();
    if (legacy == null) {
      LOG.info("Workspace quota settings key already migrated - no '{}' document.", OLD_KEY);
      return;
    }
    if (settings.find(Filters.eq("key", NEW_KEY)).first() != null) {
      settings.deleteOne(Filters.eq("_id", legacy.get("_id")));
      LOG.info(
          "Workspace quota settings — '{}' already present, removed the legacy '{}' document.",
          NEW_KEY,
          OLD_KEY);
      return;
    }
    settings.updateOne(
        Filters.eq("_id", legacy.get("_id")),
        Updates.combine(
            Updates.set("key", NEW_KEY),
            Updates.set("name", NEW_NAME),
            Updates.set("description", NEW_DESCRIPTION)));
    LOG.info("Workspace quota settings — renamed settings.key '{}' -> '{}'.", OLD_KEY, NEW_KEY);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    settings.updateOne(
        Filters.eq("key", NEW_KEY),
        Updates.combine(
            Updates.set("key", OLD_KEY),
            Updates.set("name", "Team Quotas"),
            Updates.set(
                "description",
                "Define default team quotas which are referenced unless overridden on the Team.")));
  }
}

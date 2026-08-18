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
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DD-01 (Team -> Workspace) for the four {@code settings.key="features"} config entries {@link
 * _0032__WorkspaceQuotaSettingsKey} deliberately left alone: {@code teamQuotas}, {@code
 * teamParameters}, {@code teamManagement}, {@code teamTasks}. That earlier unit's reasoning - "the
 * webapp reads them by those keys" - is superseded: the webapp read, the {@code /features}
 * response key ({@code FeatureService}), and this nested config key all move together as one
 * change (webapp: {@code App.tsx}/{@code setupTests.tsx}/the fixtures; API-facing key:
 * {@code FeatureService}; this unit: the underlying {@code SettingConfig.key} the service reads
 * via {@code settingsService.getSettingConfig("features", ...)}), so a serving instance is never
 * caught with one layer renamed and another not - the exact failure mode a partial pass produced
 * previously (every gated nav item silently disabled, no error).
 *
 * <p>Renames each entry's {@code key} in place (and rewords its {@code label}/{@code
 * description} to match, same as {@code _0032}) rather than adding a duplicate: {@code
 * teamQuotas} -> {@code workspaceQuotas}, {@code teamParameters} -> {@code workspaceParameters},
 * {@code teamManagement} -> {@code workspaceManagement}, {@code teamTasks} -> {@code
 * workspaceTasks}. {@code value}/{@code type}/{@code readOnly} are untouched - only the
 * identifying and display fields change.
 *
 * <p>Idempotent per entry: an entry already under its new key (a fresh install seeded by {@code
 * _0021__SeedSettings} from the updated seed, or a re-run of this unit) is left alone; an entry
 * still under its legacy key is renamed; the pathological case of BOTH keys present in the same
 * {@code features} document is resolved by dropping the legacy entry and keeping the new one, so
 * the config list never carries a duplicate. A missing {@code features} document (nothing seeded
 * yet) or a missing legacy entry (nothing to rename) is a no-op.
 */
@Change(id = "0034-workspace-feature-flag-settings-keys", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0034__WorkspaceFeatureFlagSettingsKeys {

  private static final Logger LOG =
      LoggerFactory.getLogger(_0034__WorkspaceFeatureFlagSettingsKeys.class);

  static final String FEATURES_KEY = "features";

  private record Rename(
      String oldKey,
      String oldLabel,
      String oldDescription,
      String newKey,
      String newLabel,
      String newDescription) {}

  private static final List<Rename> RENAMES =
      List.of(
          new Rename(
              "teamQuotas",
              "Team Quotas",
              "Enforce Team level quotas",
              "workspaceQuotas",
              "Workspace Quotas",
              "Enforce Workspace level quotas"),
          new Rename(
              "teamParameters",
              "Team Parameters",
              "Enable users to access and create Team Parameters",
              "workspaceParameters",
              "Workspace Parameters",
              "Enable users to access and create Workspace Parameters"),
          new Rename(
              "teamManagement",
              "Team Management",
              "Allow management and editing of Teams. The Teams screen will always be visible but"
                  + " if disabled will be in view only mode",
              "workspaceManagement",
              "Workspace Management",
              "Allow management and editing of Workspaces. The Workspaces screen will always be"
                  + " visible but if disabled will be in view only mode"),
          new Rename(
              "teamTasks",
              "Team Tasks",
              "Allow users to access and create Team Tasks",
              "workspaceTasks",
              "Workspace Tasks",
              "Allow users to access and create Workspace Tasks"));

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document features = settings.find(Filters.eq("key", FEATURES_KEY)).first();
    if (features == null) {
      LOG.info("No '{}' settings document - nothing to rename.", FEATURES_KEY);
      return;
    }
    Object featuresId = features.get("_id");
    List<Document> config = configOf(features);
    int renamed = 0;
    for (Rename r : RENAMES) {
      boolean oldPresent = config.stream().anyMatch(c -> r.oldKey().equals(c.getString("key")));
      boolean newPresent = config.stream().anyMatch(c -> r.newKey().equals(c.getString("key")));
      if (!oldPresent) {
        continue;
      }
      if (newPresent) {
        settings.updateOne(
            Filters.eq("_id", featuresId), Updates.pull("config", new Document("key", r.oldKey())));
        LOG.info(
            "Workspace feature flag settings - '{}' already present, dropped legacy '{}'.",
            r.newKey(),
            r.oldKey());
      } else {
        settings.updateOne(
            Filters.and(Filters.eq("_id", featuresId), Filters.eq("config.key", r.oldKey())),
            Updates.combine(
                Updates.set("config.$.key", r.newKey()),
                Updates.set("config.$.label", r.newLabel()),
                Updates.set("config.$.description", r.newDescription())));
        LOG.info(
            "Workspace feature flag settings - renamed config.key '{}' -> '{}'.",
            r.oldKey(),
            r.newKey());
      }
      renamed++;
    }
    if (renamed == 0) {
      LOG.info("Workspace feature flag settings already migrated - no legacy keys found.");
    }
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    for (Rename r : RENAMES) {
      settings.updateOne(
          Filters.and(Filters.eq("key", FEATURES_KEY), Filters.eq("config.key", r.newKey())),
          Updates.combine(
              Updates.set("config.$.key", r.oldKey()),
              Updates.set("config.$.label", r.oldLabel()),
              Updates.set("config.$.description", r.oldDescription())));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Document> configOf(Document features) {
    List<Document> config = (List<Document>) features.get("config");
    return config == null ? List.of() : config;
  }
}

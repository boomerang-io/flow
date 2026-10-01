package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives every settings group the name and description the Settings tab shows: a plain noun and
 * one sentence saying what the group controls, in place of "Configure Customizations" and the
 * "... Configuration" suffixes. The quota defaults also change key, {@code workspaces} to {@code
 * quotas}, in lock-step with {@code WorkspaceService.QUOTAS_SETTINGS_KEY} and {@code
 * seed/settings.json}: the key names the group, and the group is the platform's quota defaults.
 *
 * <p>Nested {@code config[].key} entries and every value are untouched. Idempotent: the text is
 * overwritten on every run; the key rename touches only a document still keyed {@code
 * workspaces}, and if a {@code quotas} document already exists beside it (a fresh install seeded
 * from the updated seed), the legacy document is removed so the key stays unique.
 */
@Change(id = "0055-rename-settings-groups", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0055__RenameSettingsGroups {

  private static final Logger LOG = LoggerFactory.getLogger(_0055__RenameSettingsGroups.class);

  static final String OLD_QUOTAS_KEY = "workspaces";
  static final String QUOTAS_KEY = "quotas";

  /** key -> {name, description}. */
  static final List<Document> TEXT =
      List.of(
          text("auth", "Authentication", "The identity provider that signs people in, and how their tokens are exchanged."),
          text("customizations", "Customization", "The name, logo and links this instance shows its users."),
          text("features", "Features", "Turn parts of the product on or off for everyone."),
          text("integration", "Integrations", "Credentials and endpoints for the systems Flow connects to."),
          text("task", "Tasks", "How task containers run: images, worker clean-up, timeouts."),
          text(QUOTAS_KEY, "Quotas", "The limits every workspace starts with; a workspace can override them."),
          text(
              "workflowrun",
              "Run limits",
              "How deep a run may nest child workflows and how many items a for-each may fan out to."));

  private static Document text(String key, String name, String description) {
    return new Document("key", key).append("name", name).append("description", description);
  }

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    renameQuotasKey(settings);
    for (Document entry : TEXT) {
      long matched =
          settings
              .updateOne(
                  Filters.eq("key", entry.getString("key")),
                  Updates.combine(
                      Updates.set("name", entry.getString("name")),
                      Updates.set("description", entry.getString("description"))))
              .getMatchedCount();
      LOG.info("Settings group '{}': {}.", entry.getString("key"), (matched > 0) ? "named" : "absent");
    }
  }

  private static void renameQuotasKey(MongoCollection<Document> settings) {
    Document legacy = settings.find(Filters.eq("key", OLD_QUOTAS_KEY)).first();
    if (legacy == null) {
      return;
    }
    if (settings.find(Filters.eq("key", QUOTAS_KEY)).first() != null) {
      settings.deleteOne(Filters.eq("_id", legacy.get("_id")));
      LOG.info("Quota settings: '{}' already present, removed the legacy '{}' document.", QUOTAS_KEY, OLD_QUOTAS_KEY);
      return;
    }
    settings.updateOne(Filters.eq("_id", legacy.get("_id")), Updates.set("key", QUOTAS_KEY));
    LOG.info("Quota settings: renamed settings.key '{}' -> '{}'.", OLD_QUOTAS_KEY, QUOTAS_KEY);
  }

  @Rollback
  public void rollback(MongoDatabase db, CollectionNames names) {
    // The key is what the code reads, so it goes back; the text is display copy on documents an
    // operator may have edited since and stays, matching the other settings units in this chain.
    db.getCollection(names.resolve("settings"))
        .updateOne(Filters.eq("key", QUOTAS_KEY), Updates.set("key", OLD_QUOTAS_KEY));
  }
}

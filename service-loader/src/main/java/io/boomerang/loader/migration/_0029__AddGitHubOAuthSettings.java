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
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the GitHub OAuth client settings ({@code github.clientId}, {@code github.clientSecret},
 * {@code github.webhookSecret}) to the existing {@code integration} settings document, and
 * renames the pre-existing {@code github.pem} config entry to {@code github.jwt} in place
 * (preserving any operator-provided value) so its key matches the one {@code GitHubService} has
 * always read.
 *
 * <p>{@link _0021__SeedSettings} only inserts each of the seven settings documents when the WHOLE
 * document is absent by {@code _id} or {@code key} - an install that already has an {@code
 * integration} document (any install that ran that unit before these entries were added to {@code
 * seed/settings.json}) never picks up entries added to the seed afterwards. This unit backfills
 * them directly into the existing document's {@code config} array. A fresh install seeds the new
 * shape directly via {@code _0021} and finds nothing to do here.
 *
 * <p>Idempotent: each new key is only pushed if absent; the rename only happens when {@code
 * github.jwt} is absent and {@code github.pem} is present. A second run changes nothing.
 */
@Change(id = "0029-add-github-oauth-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0029__AddGitHubOAuthSettings {

  private static final Logger LOG = LoggerFactory.getLogger(_0029__AddGitHubOAuthSettings.class);

  private static final List<Document> NEW_ENTRIES =
      List.of(
          new Document("key", "github.clientId")
              .append("label", "GitHub Client ID")
              .append("description", "The Client ID from your GitHub App credentials.")
              .append("type", "text")
              .append("value", "")
              .append("readOnly", false),
          new Document("key", "github.clientSecret")
              .append("label", "GitHub Client Secret")
              .append("description", "The Client Secret from your GitHub App credentials.")
              .append("type", "secured")
              .append("value", "")
              .append("readOnly", false),
          new Document("key", "github.webhookSecret")
              .append("label", "GitHub Webhook Secret")
              .append(
                  "description",
                  "The Webhook Secret configured on your GitHub App, used to verify the"
                      + " X-Hub-Signature-256 header on incoming webhook payloads.")
              .append("type", "secured")
              .append("value", "")
              .append("readOnly", false));

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    Document integration = settings.find(Filters.eq("key", "integration")).first();
    if (integration == null) {
      LOG.warn("No 'integration' settings document found - nothing to backfill.");
      return;
    }

    List<Document> existingConfig = integration.getList("config", Document.class);
    List<Document> config = existingConfig == null ? new ArrayList<>() : new ArrayList<>(existingConfig);
    boolean changed = false;

    for (Document newEntry : NEW_ENTRIES) {
      String key = newEntry.getString("key");
      if (config.stream().noneMatch(c -> key.equals(c.getString("key")))) {
        config.add(newEntry);
        changed = true;
      }
    }

    boolean hasJwtKey = config.stream().anyMatch(c -> "github.jwt".equals(c.getString("key")));
    if (!hasJwtKey) {
      for (Document c : config) {
        if ("github.pem".equals(c.getString("key"))) {
          c.put("key", "github.jwt");
          changed = true;
          break;
        }
      }
    }

    if (changed) {
      settings.updateOne(Filters.eq("_id", integration.get("_id")), Updates.set("config", config));
      LOG.info("Backfilled GitHub OAuth settings onto the existing 'integration' document.");
    } else {
      LOG.info("GitHub OAuth settings already present - nothing to backfill.");
    }
  }

  @Rollback
  public void rollback() {
    // Additive/renaming backfill onto an operator-configured document - forward-only, matching
    // the other settings units in this chain.
  }
}

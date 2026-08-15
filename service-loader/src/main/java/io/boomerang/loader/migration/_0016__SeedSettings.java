package io.boomerang.loader.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.loader.CollectionNames;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.util.List;
import org.bson.Document;

/**
 * Seed the seven instance settings documents, each a {@code SettingEntity} whose {@code config}
 * list holds the individual {@code SettingConfig} entries the services read by key:
 *
 * <ul>
 *   <li>{@code task} — task execution defaults (debug, default image, deletion policy, edit
 *       verified, default timeout)
 *   <li>{@code workflow} / {@code workflowrun} — storage size, class, access mode, max size
 *   <li>{@code teams} — the default workspace quotas {@code WorkspaceService.setDefaultQuotas}
 *       resolves at read time ({@code max.workflow.count}, {@code max.workflowrun.concurrent},
 *       {@code max.workflowrun.monthly}, {@code max.workflowrun.duration}, {@code
 *       max.workflow.storage}, {@code max.workflowrun.storage}). Without this document every
 *       workspace resolves null quotas.
 *   <li>{@code features} — the feature toggles the web app reads
 *   <li>{@code integration} — Slack and GitHub app credentials (seeded empty, filled by an
 *       operator)
 *   <li>{@code customizations} — instance name, platform name, logo
 * </ul>
 *
 * <p>Ported from the legacy loader's {@code flow/*&#47;flow_settings/*.json} chain as its final
 * state, keeping the literal {@code _id}s so an upgraded install matches on them. Guarded on
 * {@code key}: an install that already holds a setting keeps its own configured values.
 */
@Change(id = "0016-seed-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0016__SeedSettings {

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    List<Document> settings = SeedResources.load("seed/settings.json");
    int inserted = 0;
    for (Document setting : settings) {
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("settings"),
          Filters.eq("key", setting.getString("key")),
          setting)) {
        inserted++;
      }
    }
    SeedResources.logSeeded("settings", inserted, settings.size());
  }

  @Rollback
  public void rollback() {
    // Settings carry operator-configured values once an install is live - forward-only.
  }
}

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@code _id} OR {@code key}: an install that already holds a setting keeps its own configured
 * values. The {@code _id} half of the guard is defence-in-depth against a v3 install that already
 * holds these same seven {@code _id}s under different {@code key} values (the legacy loader
 * generated them independently) — on a v3 database this change unit does not run at all (see
 * below), so it only ever matters on v4/fresh, where the two never actually collide.
 *
 * <p><b>Skipped entirely on a v3 install</b> ({@link InstallGeneration#V3}): all seven {@code
 * _id}s already exist there under different {@code key} values, and the (separate) v3→v5
 * migration is what reconciles this collection for those installs.
 */
@Change(id = "0016-seed-settings", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0016__SeedSettings {

  private static final Logger LOG = LoggerFactory.getLogger(_0016__SeedSettings.class);

  @Apply
  public void execute(MongoDatabase db, CollectionNames names) {
    if (InstallGeneration.detect(db, names) == InstallGeneration.V3) {
      LOG.info(
          "v3 install detected — skipping settings seed; the v3->v5 migration reconciles the "
              + "settings collection for this install.");
      return;
    }
    List<Document> settings = SeedResources.load("seed/settings.json");
    int inserted = 0;
    for (Document setting : settings) {
      if (SeedResources.insertIfAbsent(
          db,
          names.resolve("settings"),
          Filters.or(
              Filters.eq("_id", setting.get("_id")), Filters.eq("key", setting.getString("key"))),
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

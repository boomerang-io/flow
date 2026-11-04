package io.boomerang.core.audit;

import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.SettingsRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Applies the {@code audit.retentionDays} setting to the audit collection's TTL index at startup
 * and again, on every instance, within one check interval of the setting changing (collMod is
 * idempotent, so instances applying it at once is safe). {@code
 * io.boomerang.migration._0021__Indexes} creates the index at the 365-day default.
 *
 * <p>The retention is floored at {@value #MIN_RETENTION_DAYS} days — monthly quota counting relies
 * on at least one month of rows. Best-effort: a missing collection, index, or setting logs and
 * leaves the trail untouched.
 */
@Service
public class AuditRetentionService {

  static final int MIN_RETENTION_DAYS = 60;
  static final int DEFAULT_RETENTION_DAYS = 365;
  static final String TTL_INDEX_NAME = "createdAt_ttl";

  private static final Logger LOGGER = LogManager.getLogger();

  private final MongoTemplate mongoTemplate;
  private final MongoConfiguration mongoConfiguration;
  private final SettingsRepository settingsRepository;

  // The days this instance last applied; 0 until the first successful apply.
  private volatile int appliedDays;

  public AuditRetentionService(
      MongoTemplate mongoTemplate,
      MongoConfiguration mongoConfiguration,
      SettingsRepository settingsRepository) {
    this.mongoTemplate = mongoTemplate;
    this.mongoConfiguration = mongoConfiguration;
    this.settingsRepository = settingsRepository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void applyRetention() {
    apply(configuredRetentionDays());
  }

  /** Re-apply the TTL when the setting no longer matches what this instance last applied. */
  @Scheduled(
      initialDelayString = "${flow.audit.retention-interval-ms:60000}",
      fixedDelayString = "${flow.audit.retention-interval-ms:60000}")
  public void reapplyOnChange() {
    int days = configuredRetentionDays();
    if (days != appliedDays) {
      apply(days);
    }
  }

  private void apply(int days) {
    try {
      String collection = mongoConfiguration.fullCollectionName("audit");
      mongoTemplate
          .getDb()
          .runCommand(
              new Document("collMod", collection)
                  .append(
                      "index",
                      new Document("name", TTL_INDEX_NAME)
                          .append("expireAfterSeconds", days * 86400L)));
      appliedDays = days;
      LOGGER.info("Audit retention set to {} days on {}", days, collection);
    } catch (RuntimeException e) {
      LOGGER.warn("Could not apply audit retention ({}) - the trail keeps its current TTL", e.toString());
    }
  }

  /** The configured retention, floored at {@value #MIN_RETENTION_DAYS} days. */
  int configuredRetentionDays() {
    int days = DEFAULT_RETENTION_DAYS;
    SettingEntity settings = settingsRepository.findOneByKey(AuditEventEmitter.SETTINGS_KEY);
    if (settings != null && settings.getConfig() != null) {
      try {
        days =
            settings.getConfig().stream()
                .filter(config -> "retentionDays".equals(config.getKey()))
                .findFirst()
                .map(SettingConfig::getValue)
                .map(Integer::parseInt)
                .orElse(DEFAULT_RETENTION_DAYS);
      } catch (NumberFormatException e) {
        LOGGER.warn("audit.retentionDays is not a number - using the {} day default", DEFAULT_RETENTION_DAYS);
      }
    }
    return Math.max(days, MIN_RETENTION_DAYS);
  }
}

package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.SettingsService;
import io.boomerang.core.config.MongoConfiguration;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A change to {@code audit.retentionDays} reaches the audit collection's TTL index without a
 * restart, on the instance that served the change and on any other one running the check, and a
 * value below the floor is applied as the floor.
 */
class AuditRetentionServiceTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final long DAY = 86400L;

  @Autowired private AuditRetentionService retentionService;
  @Autowired private SettingsService settingsService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private MongoConfiguration mongoConfiguration;

  @BeforeEach
  void seedSetting() {
    SettingEntity existing = settingsRepository.findOneByKey(AuditEventEmitter.SETTINGS_KEY);
    if (existing != null) {
      settingsRepository.delete(existing);
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey(AuditEventEmitter.SETTINGS_KEY);
    settings.setName("Audit");
    settings.setType(ConfigurationType.ValuesList);
    SettingConfig retention = new SettingConfig();
    retention.setKey("retentionDays");
    retention.setType("number");
    retention.setValue("365");
    settings.setConfig(new ArrayList<>(List.of(retention)));
    settingsRepository.save(settings);
    retentionService.applyRetention();
  }

  /** Leaves the shared database as the other test classes expect it: no audit setting, 365 days. */
  @AfterEach
  void restoreDefault() {
    SettingEntity settings = settingsRepository.findOneByKey(AuditEventEmitter.SETTINGS_KEY);
    if (settings != null) {
      settingsRepository.delete(settings);
    }
    retentionService.applyRetention();
  }

  @Test
  void aSettingsChangeIsAppliedToTheTtlIndexByTheNextCheck() {
    patchRetention("90");
    retentionService.reapplyOnChange();

    assertThat(ttlSeconds()).isEqualTo(90 * DAY);
  }

  @Test
  void anInstanceThatDidNotServeTheChangeAppliesItOnItsOwnCheck() {
    AuditRetentionService otherInstance =
        new AuditRetentionService(mongoTemplate, mongoConfiguration, settingsRepository);

    patchRetention("120");
    otherInstance.reapplyOnChange();

    assertThat(ttlSeconds()).isEqualTo(120 * DAY);
  }

  @Test
  void anUnchangedSettingLeavesTheIndexAlone() {
    patchRetention("90");
    retentionService.reapplyOnChange();
    setTtlSeconds(100 * DAY);

    retentionService.reapplyOnChange();

    assertThat(ttlSeconds()).isEqualTo(100 * DAY);
  }

  @Test
  void aValueBelowTheFloorIsAppliedAsTheFloor() {
    patchRetention("10");
    retentionService.reapplyOnChange();

    assertThat(ttlSeconds()).isEqualTo(AuditRetentionService.MIN_RETENTION_DAYS * DAY);
  }

  private void patchRetention(String days) {
    settingsService.patchSettings(
        MAPPER.readTree(
            "{\"" + AuditEventEmitter.SETTINGS_KEY + "\":{\"retentionDays\":\"" + days + "\"}}"));
  }

  private long ttlSeconds() {
    for (Document index :
        mongoTemplate
            .getCollection(mongoConfiguration.fullCollectionName("audit"))
            .listIndexes()) {
      if (AuditRetentionService.TTL_INDEX_NAME.equals(index.getString("name"))) {
        return ((Number) index.get("expireAfterSeconds")).longValue();
      }
    }
    throw new AssertionError("The audit collection has no " + AuditRetentionService.TTL_INDEX_NAME);
  }

  private void setTtlSeconds(long seconds) {
    mongoTemplate
        .getDb()
        .runCommand(
            new Document("collMod", mongoConfiguration.fullCollectionName("audit"))
                .append(
                    "index",
                    new Document("name", AuditRetentionService.TTL_INDEX_NAME)
                        .append("expireAfterSeconds", seconds)));
  }
}

package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.Setting;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.json.JsonMapper;

/**
 * A secured setting is ciphertext at rest, reads through the settings API as the redaction marker,
 * and survives a save that sends back the marker, a blank or the stored ciphertext. Internal reads
 * through {@link SettingsService#getSettingConfig} stay plaintext.
 */
class SettingsMaskingTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String LABEL = "crypt_v1{AESGCM|";
  private static final String KEY = "mask-test-settings";
  private static final String SECRET = "mask-secret-0001";

  @Autowired private SettingsService settingsService;
  @Autowired private MongoTemplate mongoTemplate;

  private String settingId;

  @BeforeEach
  void seedSetting() {
    SettingEntity existing = settingsRepository.findOneByKey(KEY);
    if (existing != null) {
      settingsRepository.delete(existing);
    }
    SettingEntity entity = new SettingEntity();
    entity.setKey(KEY);
    entity.setName("Mask test");
    entity.setType(ConfigurationType.ValuesList);
    entity.setConfig(
        new ArrayList<>(
            List.of(
                config("mask.token", "secured", SECRET),
                config("mask.unset", "secured", ""),
                config("mask.channel", "text", "#general"))));
    settingsService.updateSetting(entity);
    settingId = settingsRepository.findOneByKey(KEY).getId();
  }

  @Test
  void aSecuredSettingIsCiphertextAtRestAndMaskedOnTheApiRead() {
    assertThat(rawValue("mask.token")).startsWith(LABEL).doesNotContain(SECRET);

    List<Setting> settings = settingsService.getAllSettings();

    String json = MAPPER.writeValueAsString(settings);
    assertThat(json).doesNotContain(SECRET).doesNotContain("crypt_v1");
    assertThat(apiValue(settings, "mask.token")).isEqualTo(DataAdapterUtil.REDACTED);
    assertThat(apiValue(settings, "mask.unset")).isEmpty();
    assertThat(apiValue(settings, "mask.channel")).isEqualTo("#general");
    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue()).isEqualTo(SECRET);
  }

  @Test
  void savingTheMarkerABlankOrTheCiphertextKeepsTheStoredSecret() {
    String ciphertext = rawValue("mask.token");

    for (String sentBack : new String[] {DataAdapterUtil.REDACTED, "", null, ciphertext}) {
      List<Setting> response = save(sentBack, "#general");

      assertThat(MAPPER.writeValueAsString(response)).doesNotContain(SECRET).doesNotContain("crypt_v1");
      assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue())
          .as("sent back %s", sentBack)
          .isEqualTo(SECRET);
      assertThat(rawValue("mask.token")).startsWith(LABEL);
    }
  }

  @Test
  void savingANewValueReplacesTheSecretAndLeavesOtherEntriesAlone() {
    List<Setting> response = save("mask-secret-0002", "#builds");

    assertThat(MAPPER.writeValueAsString(response))
        .doesNotContain("mask-secret-0002")
        .doesNotContain(SECRET);
    assertThat(rawValue("mask.token")).startsWith(LABEL).doesNotContain("mask-secret-0002");
    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue())
        .isEqualTo("mask-secret-0002");
    assertThat(settingsService.getSettingConfig(KEY, "mask.channel").getValue()).isEqualTo("#builds");
    assertThat(rawValue("mask.channel")).isEqualTo("#builds");
  }

  @Test
  void aBlankNonSecuredEntryStillClears() {
    save(DataAdapterUtil.REDACTED, "");

    assertThat(settingsService.getSettingConfig(KEY, "mask.channel").getValue()).isEmpty();
    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue()).isEqualTo(SECRET);
  }

  private List<Setting> save(String token, String channel) {
    Setting setting = new Setting();
    setting.setId(settingId);
    setting.setKey(KEY);
    setting.setConfig(
        new ArrayList<>(
            List.of(config("mask.token", "secured", token), config("mask.channel", "text", channel))));
    return settingsService.updateSettings(List.of(setting));
  }

  private String rawValue(String configKey) {
    Document stored =
        mongoTemplate.findOne(
            Query.query(Criteria.where("key").is(KEY)),
            Document.class,
            mongoTemplate.getCollectionName(SettingEntity.class));
    assertThat(stored).isNotNull();
    return stored.getList("config", Document.class).stream()
        .filter(c -> configKey.equals(c.getString("key")))
        .findFirst()
        .orElseThrow()
        .getString("value");
  }

  private static String apiValue(List<Setting> settings, String configKey) {
    return settings.stream()
        .filter(s -> KEY.equals(s.getKey()))
        .findFirst()
        .orElseThrow()
        .getConfig()
        .stream()
        .filter(c -> configKey.equals(c.getKey()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static SettingConfig config(String key, String type, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setType(type);
    config.setValue(value);
    return config;
  }
}

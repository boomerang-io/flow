package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * A secured setting is ciphertext at rest and follows the one secret rule: it reads through the
 * settings API as the redaction marker when set and as nothing when unset, a patch sending the
 * marker or leaving the entry out keeps it, and a blank or a null clears it. Internal reads through
 * {@link SettingsService#getSettingConfig} stay plaintext.
 */
class SettingsMaskingTest extends AbstractEngineIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String LABEL = "crypt_v1{AESGCM|";
  private static final String KEY = "mask-test-settings";
  private static final String SECRET = "mask-secret-0001";

  @Autowired private SettingsService settingsService;
  @Autowired private MongoTemplate mongoTemplate;

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
  }

  @Test
  void aSecuredSettingIsCiphertextAtRestAndMaskedOnTheApiRead() {
    assertThat(rawValue("mask.token")).startsWith(LABEL).doesNotContain(SECRET);

    List<Setting> settings = settingsService.getAllSettings();

    String json = MAPPER.writeValueAsString(settings);
    assertThat(json).doesNotContain(SECRET).doesNotContain("crypt_v1");
    assertThat(apiValue(settings, "mask.token")).isEqualTo(DataAdapterUtil.REDACTED);
    assertThat(apiValue(settings, "mask.unset")).as("an unset secured entry reads as nothing").isNull();
    assertThat(apiValue(settings, "mask.channel")).isEqualTo("#general");
    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue()).isEqualTo(SECRET);
  }

  @Test
  void savingTheMarkerOrNoValueKeepsTheStoredSecret() {
    for (String sentBack : new String[] {DataAdapterUtil.REDACTED, null}) {
      List<Setting> response = save(sentBack, "#general");

      assertThat(MAPPER.writeValueAsString(response)).doesNotContain(SECRET).doesNotContain("crypt_v1");
      assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue())
          .as("sent back %s", sentBack)
          .isEqualTo(SECRET);
      assertThat(rawValue("mask.token")).startsWith(LABEL);
    }
  }

  @Test
  void savingABlankClearsTheSecret() {
    List<Setting> response = save("", "#general");

    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue()).isEmpty();
    assertThat(apiValue(response, "mask.token")).isNull();
  }

  @Test
  void aValueThatOnlyStartsWithTheLabelIsANewValueAndIsEncrypted() {
    save("crypt_v1-not-a-ciphertext", "#general");

    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue())
        .isEqualTo("crypt_v1-not-a-ciphertext");
    assertThat(rawValue("mask.token")).startsWith(LABEL).doesNotContain("not-a-ciphertext");
  }

  @Test
  void aStoredValueThatWillNotDecryptIsKeptAsStoredWhenItsGroupIsSaved() {
    String undecryptable = LABEL + "bm90LWEtcmVhbC1jaXBoZXJ0ZXh0}";
    setRawValue("mask.token", undecryptable);

    save(DataAdapterUtil.REDACTED, "#builds");

    assertThat(rawValue("mask.token")).isEqualTo(undecryptable);
    assertThat(apiValue(settingsService.getAllSettings(), "mask.token"))
        .isEqualTo(DataAdapterUtil.REDACTED);
  }

  @Test
  void lastModifiedMovesOnlyWhenAValueChanges() {
    save(DataAdapterUtil.REDACTED, "#general");
    java.util.Date before = settingsRepository.findOneByKey(KEY).getLastModiifed();

    save(DataAdapterUtil.REDACTED, "#general");
    assertThat(settingsRepository.findOneByKey(KEY).getLastModiifed()).isEqualTo(before);

    save(DataAdapterUtil.REDACTED, "#builds");
    assertThat(settingsRepository.findOneByKey(KEY).getLastModiifed()).isNotEqualTo(before);
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

  @Test
  void anExplicitNullClearsAnEntry() {
    settingsService.patchSettings(
        MAPPER.readTree("{\"" + KEY + "\":{\"mask.token\":null,\"mask.channel\":null}}"));

    assertThat(settingsService.getSettingConfig(KEY, "mask.token").getValue()).isEmpty();
    assertThat(settingsService.getSettingConfig(KEY, "mask.channel").getValue()).isEmpty();
  }

  @Test
  void anUnknownGroupOrKeyOrANonScalarValueRefusesTheWholePatch() {
    for (String patch :
        new String[] {
          "{\"" + KEY + "\":{\"mask.channel\":\"#builds\"},\"no-such-group\":{\"a\":\"b\"}}",
          "{\"" + KEY + "\":{\"mask.channel\":\"#builds\",\"no.such.key\":\"x\"}}",
          "{\"" + KEY + "\":{\"mask.channel\":{\"nested\":true}}}",
          "{\"" + KEY + "\":\"not-an-object\"}",
          "{\"internal\":{\"x\":\"y\"}}",
          "[]"
        }) {
      BoomerangException refused =
          assertThrows(
              BoomerangException.class, () -> settingsService.patchSettings(MAPPER.readTree(patch)));
      assertThat(refused.getCode()).as(patch).isEqualTo(BoomerangError.SETTINGS_INVALID_KEY.getCode());
    }
    assertThat(settingsService.getSettingConfig(KEY, "mask.channel").getValue())
        .as("nothing in a refused patch is written")
        .isEqualTo("#general");
  }

  // A merge patch of this test's group: a null argument leaves that entry out of the patch.
  private List<Setting> save(String token, String channel) {
    ObjectNode entries = MAPPER.createObjectNode();
    if (token != null) {
      entries.put("mask.token", token);
    }
    if (channel != null) {
      entries.put("mask.channel", channel);
    }
    ObjectNode patch = MAPPER.createObjectNode();
    patch.set(KEY, entries);
    return settingsService.patchSettings(patch);
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

  private void setRawValue(String configKey, String value) {
    Document stored =
        mongoTemplate.findOne(
            Query.query(Criteria.where("key").is(KEY)),
            Document.class,
            mongoTemplate.getCollectionName(SettingEntity.class));
    assertThat(stored).isNotNull();
    stored.getList("config", Document.class).stream()
        .filter(c -> configKey.equals(c.getString("key")))
        .findFirst()
        .orElseThrow()
        .put("value", value);
    mongoTemplate.save(stored, mongoTemplate.getCollectionName(SettingEntity.class));
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

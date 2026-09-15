package io.boomerang.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.EncryptionConfig;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.SettingsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Secured setting values are encrypted at rest and decrypted on read. Unit-level (mocked
 * repository, real {@link EncryptionConfig}) so the stored shape can be asserted on the entity
 * handed to the repository: prefixed ciphertext in, plaintext out, and a value stored as plaintext
 * by an earlier release still reads back unchanged.
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

  private static final String PREFIX = "crypt_v1{AESGCM|";

  @Mock private SettingsRepository settingsRepository;

  private SettingsService settingsService;

  @BeforeEach
  void setUp() {
    EncryptionConfig encryptionConfig = new EncryptionConfig();
    encryptionConfig.setSecretKey("test-secret");
    encryptionConfig.setSalt("test-salt");
    settingsService = new SettingsService(settingsRepository, encryptionConfig);
  }

  @Test
  void aSecuredValueIsStoredAsPrefixedCiphertextAndReadsBackAsPlaintext() {
    SettingEntity entity = settingWith(config("slack.signingSecret", "secured", "s3cret"),
        config("slack.channel", "text", "#general"));

    settingsService.updateSetting(entity);

    ArgumentCaptor<SettingEntity> saved = ArgumentCaptor.forClass(SettingEntity.class);
    verify(settingsRepository).save(saved.capture());
    String stored = valueOf(saved.getValue(), "slack.signingSecret");
    assertThat(stored).startsWith(PREFIX).endsWith("}").doesNotContain("s3cret");
    assertThat(valueOf(saved.getValue(), "slack.channel")).isEqualTo("#general");

    when(settingsRepository.findById("setting-1")).thenReturn(Optional.of(saved.getValue()));
    SettingEntity read = settingsService.getSettingById("setting-1");
    assertThat(valueOf(read, "slack.signingSecret")).isEqualTo("s3cret");
  }

  @Test
  void anAlreadyEncryptedValueIsNotEncryptedAgain() {
    SettingEntity entity = settingWith(config("slack.signingSecret", "secured", "s3cret"));
    settingsService.updateSetting(entity);
    String storedOnce = valueOf(entity, "slack.signingSecret");

    settingsService.updateSetting(entity);

    assertThat(valueOf(entity, "slack.signingSecret")).isEqualTo(storedOnce);
  }

  @Test
  void aValueStoredAsPlaintextByAnEarlierReleaseReadsBackUnchanged() {
    SettingEntity entity = settingWith(config("slack.signingSecret", "secured", "legacy-plain"));
    when(settingsRepository.findOneByKey("extensions")).thenReturn(entity);

    assertThat(settingsService.getSettingConfig("extensions", "slack.signingSecret").getValue())
        .isEqualTo("legacy-plain");
  }

  @Test
  void blankSecuredValuesPassThroughUntouched() {
    SettingEntity entity = settingWith(config("slack.token", "secured", ""),
        config("slack.clientSecret", "secured", null));

    settingsService.updateSetting(entity);
    when(settingsRepository.findById("setting-1")).thenReturn(Optional.of(entity));
    SettingEntity read = settingsService.getSettingById("setting-1");

    assertThat(valueOf(read, "slack.token")).isEmpty();
    assertThat(valueOf(read, "slack.clientSecret")).isNull();
    verify(settingsRepository).save(any());
  }

  private static SettingEntity settingWith(SettingConfig... configs) {
    SettingEntity entity = new SettingEntity();
    entity.setId("setting-1");
    entity.setKey("extensions");
    entity.setType(ConfigurationType.ValuesList);
    entity.setConfig(new ArrayList<>(List.of(configs)));
    return entity;
  }

  private static SettingConfig config(String key, String type, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setType(type);
    config.setValue(value);
    return config;
  }

  private static String valueOf(SettingEntity entity, String key) {
    return entity.getConfig().stream()
        .filter(config -> key.equals(config.getKey()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }
}

package io.boomerang.core;

import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DateUtil;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.Setting;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.SettingsRepository;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {

  private static final String SECURED_TYPE = "secured";

  @Value("${flow.workflow.webhook.url}")
  private String webhookUrl;

  @Value("${flow.workflow.wfe.url}")
  private String waitForEventUrl;

  @Value("${flow.workflow.event.url}")
  private String eventUrl;

  private final SettingsRepository settingsRepository;
  private final SecretCipher secretCipher;
  private final ParamLayerCache paramLayerCache;

  public SettingsService(
      SettingsRepository settingsRepository,
      SecretCipher secretCipher,
      ParamLayerCache paramLayerCache) {
    this.settingsRepository = settingsRepository;
    this.secretCipher = secretCipher;
    this.paramLayerCache = paramLayerCache;
  }

  /**
   * The settings as the API shows them: a secured entry that holds a value reads as {@link
   * DataAdapterUtil#REDACTED}, and one with nothing set stays blank. Internal callers read a value
   * through {@link #getSettingConfig}, which returns plaintext.
   */
  public List<Setting> getAllSettings() {
    final List<Setting> settingList = new LinkedList<>();
    final List<SettingEntity> entityList = settingsRepository.findAll();
    for (final SettingEntity entity : entityList) {
      if (!entity.getKey().equals("internal")) {
        showMaskedValues(entity);
        final Setting newSetting = new Setting(entity);
        settingList.add(newSetting);
      }
    }

    return settingList;
  }

  public List<Setting> updateSettings(List<Setting> settings) {
    for (final Setting setting : settings) {
      final SettingEntity entity = this.getSettingById(setting.getId());
      if (entity.getType() == ConfigurationType.ValuesList) {
        setConfigsValue(setting, entity);
      }
      entity.setLastModiifed(DateUtil.asDate(LocalDateTime.now()));

      this.updateSetting(entity);
    }

    return this.getAllSettings();
  }

  private void setConfigsValue(final Setting setting, final SettingEntity entity) {
    for (final SettingConfig config : setting.getConfig()) {
      final String newValue = config.getValue();
      final Optional<SettingConfig> result =
          entity.getConfig().stream()
              .parallel()
              .filter(x -> config.getKey().equals(x.getKey()))
              .findFirst();
      if (result.isPresent() && !keepsStoredValue(result.get(), newValue)) {
        result.get().setValue(newValue);
      }
    }
  }

  // A secured entry is read masked, so a client sends back the marker, a blank, or - from an older
  // client that read the stored form - the ciphertext. None of those is a new value: the stored
  // one is kept. Any other value replaces it and is encrypted on save.
  private static boolean keepsStoredValue(SettingConfig stored, String newValue) {
    return SECURED_TYPE.equalsIgnoreCase(stored.getType())
        && (DataAdapterUtil.isMasked(newValue) || SecretCipher.isEncrypted(newValue));
  }

  public String getWebhookURL() {
    return webhookUrl;
  }

  public String getEventURL() {
    return eventUrl;
  }

  public String getWFEURL() {
    return waitForEventUrl;
  }

  public SettingConfig getSettingConfig(String key, String name) {
    final SettingEntity settings = this.settingsRepository.findOneByKey(key);
    final List<SettingConfig> configList = settings.getConfig();
    final Optional<SettingConfig> result =
        configList.stream().parallel().filter(x -> name.equals(x.getKey())).findFirst();

    if (result.isPresent() && SECURED_TYPE.equalsIgnoreCase(result.get().getType())) {
      result.get().setValue(decrypt(result.get().getValue()));
    }

    return result.orElseThrow(
        () -> new IllegalArgumentException("Unable to find configuration object: " + name));
  }

  public SettingEntity getSettingById(String id) {
    Optional<SettingEntity> entity = settingsRepository.findById(id);
    if (entity.isPresent()) {
      showDecryptedValues(entity.get());
    }

    return entity.orElseThrow(
        () -> new IllegalArgumentException("Unable to find configuration with ID: " + id));
  }

  public SettingEntity getSettingByKey(String key) {
    final SettingEntity settingsEntity = settingsRepository.findOneByKey(key);
    if (settingsEntity != null) {
      showDecryptedValues(settingsEntity);
      return settingsEntity;
    } else {
      throw new IllegalArgumentException("Unable to find configuration key: " + key);
    }
  }

  public void updateSetting(SettingEntity configuration) {
    setEncryptedValues(configuration);

    this.settingsRepository.save(configuration);
    paramLayerCache.evictAll();
  }

  private void setEncryptedValues(SettingEntity configuration) {
    configuration.getConfig().stream()
        .filter(config -> SECURED_TYPE.equalsIgnoreCase(config.getType()))
        .forEach(c -> c.setValue(encrypt(c.getValue())));
  }

  private void showDecryptedValues(SettingEntity configuration) {
    configuration.getConfig().stream()
        .filter(config -> SECURED_TYPE.equalsIgnoreCase(config.getType()))
        .forEach(c -> c.setValue(decrypt(c.getValue())));
  }

  private static void showMaskedValues(SettingEntity configuration) {
    if (configuration.getConfig() == null) {
      return;
    }
    configuration.getConfig().stream()
        .filter(config -> SECURED_TYPE.equalsIgnoreCase(config.getType()))
        .filter(config -> config.getValue() != null && !config.getValue().isBlank())
        .forEach(c -> c.setValue(DataAdapterUtil.REDACTED));
  }

  // Blank values and values already carrying the label pass through; everything else is encrypted.
  private String encrypt(String value) {
    return secretCipher.encrypt(value);
  }

  // A value without the label was stored as plaintext by an earlier release and is returned as-is;
  // it is encrypted on its next save.
  private String decrypt(String value) {
    return secretCipher.decrypt(value);
  }
}

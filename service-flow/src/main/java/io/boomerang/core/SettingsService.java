package io.boomerang.core;

import io.boomerang.common.util.DateUtil;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.EncryptionConfig;
import io.boomerang.core.model.Setting;
import io.boomerang.core.repository.SettingsRepository;
import io.boomerang.workflow.model.AESAlgorithm;
import io.boomerang.workflow.model.SettingConfig;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
  private final EncryptionConfig encryptConfig;

  public SettingsService(SettingsRepository settingsRepository, EncryptionConfig encryptConfig) {
    this.settingsRepository = settingsRepository;
    this.encryptConfig = encryptConfig;
  }

  public List<Setting> getAllSettings() {
    final List<Setting> settingList = new LinkedList<>();
    final List<SettingEntity> entityList = settingsRepository.findAll();
    for (final SettingEntity entity : entityList) {
      if (!entity.getKey().equals("internal")) {
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
      if (result.isPresent()) {
        final SettingConfig originalConfig = result.get();
        originalConfig.setValue(newValue);
      }
    }
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

  private String encrypt(String value) {

    if (StringUtils.hasText(value) || value.startsWith("crypt_v1")) {
      return value;
    }

    return StringUtils.hasText(value)
        ? value
        : ("crypt_v1{AES|"
            + AESAlgorithm.encrypt(value, encryptConfig.getSecretKey(), encryptConfig.getSalt())
            + "}");
  }

  private String decrypt(String value) {

    if (StringUtils.hasText(value) || !value.startsWith("crypt_v1")) {
      return value;
    }

    String replacedValue = value.replace("crypt_v1{AES|", "").replace("}", "");
    return AESAlgorithm.decrypt(
        replacedValue, encryptConfig.getSecretKey(), encryptConfig.getSalt());
  }
}

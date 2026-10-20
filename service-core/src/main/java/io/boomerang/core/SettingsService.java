package io.boomerang.core;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DateUtil;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.ConfigurationType;
import io.boomerang.core.model.Setting;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.core.repository.SettingsRepository;
import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String SECURED_TYPE = "secured";

  // The group the API never shows or changes.
  private static final String INTERNAL_GROUP = "internal";

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
   * DataAdapterUtil#REDACTED}, and one with nothing set reads as nothing. Internal callers read a
   * value through {@link #getSettingConfig}, which returns plaintext.
   */
  public List<Setting> getAllSettings() {
    final List<Setting> settingList = new LinkedList<>();
    final List<SettingEntity> entityList = settingsRepository.findAll();
    for (final SettingEntity entity : entityList) {
      if (!entity.getKey().equals(INTERNAL_GROUP)) {
        showMaskedValues(entity);
        final Setting newSetting = new Setting(entity);
        settingList.add(newSetting);
      }
    }

    return settingList;
  }

  /**
   * Apply a merge patch to the settings, {@code {"<group>": {"<key>": value}}}: an absent entry is
   * unchanged, a {@code null} one is cleared to empty, any other scalar replaces it. A secured entry
   * sent as the redaction marker keeps the stored value. An unknown group or key, or a value that is
   * not a scalar, refuses the whole patch before anything is written. A group is saved, and its
   * modified time stamped, only when one of its values changed.
   */
  public List<Setting> patchSettings(JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw invalidSettings("The body must be an object of settings groups.");
    }
    List<SettingEntity> changed = new LinkedList<>();
    for (Map.Entry<String, JsonNode> group : patch.properties()) {
      SettingEntity entity =
          INTERNAL_GROUP.equals(group.getKey())
              ? null
              : settingsRepository.findOneByKey(group.getKey());
      if (entity == null || entity.getType() != ConfigurationType.ValuesList) {
        throw invalidSettings("Unknown settings group: " + group.getKey() + ".");
      }
      if (!group.getValue().isObject()) {
        throw invalidSettings("Group " + group.getKey() + " must be an object of keys.");
      }
      showDecryptedValues(entity);
      if (patchGroup(entity, group.getValue())) {
        entity.setLastModiifed(DateUtil.asDate(LocalDateTime.now()));
        changed.add(entity);
      }
    }
    changed.forEach(this::updateSetting);
    return this.getAllSettings();
  }

  // Whether any value of the group changed. A secured entry follows the one secret write rule
  // (DataAdapterUtil.keepSecretValue) for the marker; on a patch a null clears rather than keeps.
  private static boolean patchGroup(SettingEntity entity, JsonNode entries) {
    boolean changed = false;
    for (Map.Entry<String, JsonNode> entry : entries.properties()) {
      SettingConfig stored =
          entity.getConfig().stream()
              .filter(config -> entry.getKey().equals(config.getKey()))
              .findFirst()
              .orElseThrow(
                  () ->
                      invalidSettings(
                          "Unknown setting: " + entity.getKey() + "/" + entry.getKey() + "."));
      JsonNode value = entry.getValue();
      if (!value.isValueNode()) {
        throw invalidSettings(
            "Setting " + entity.getKey() + "/" + entry.getKey() + " must be a string, number or"
                + " boolean.");
      }
      String incoming = value.isNull() ? "" : value.asString();
      boolean secured = SECURED_TYPE.equalsIgnoreCase(stored.getType());
      String newValue =
          (secured && DataAdapterUtil.REDACTED.equals(incoming))
              ? (String) DataAdapterUtil.keepSecretValue(incoming, stored.getValue())
              : incoming;
      if (secured) {
        SecretChangeHolder.compare(
            entity.getKey() + "." + entry.getKey(), stored.getValue(), newValue);
      }
      changed |= !Objects.equals(stored.getValue(), newValue);
      stored.setValue(newValue);
    }
    return changed;
  }

  private static BoomerangException invalidSettings(String detail) {
    return new BoomerangException(BoomerangError.SETTINGS_INVALID_KEY, detail);
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
        .forEach(c -> c.setValue(decryptOrKeep(c.getValue())));
  }

  // The one read shape for a secret (DataAdapterUtil.maskSecretValue): a set secured entry reads as
  // the marker, an unset one as nothing.
  private static void showMaskedValues(SettingEntity configuration) {
    if (configuration.getConfig() == null) {
      return;
    }
    configuration.getConfig().stream()
        .filter(config -> SECURED_TYPE.equalsIgnoreCase(config.getType()))
        .forEach(c -> c.setValue((String) DataAdapterUtil.maskSecretValue(c.getValue())));
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

  // A value that will not decrypt - a different key, or a tampered value - is kept as stored, so
  // the next save of its group writes it back unchanged instead of nothing.
  private String decryptOrKeep(String value) {
    String plaintext = secretCipher.decrypt(value);
    if (plaintext == null && value != null) {
      LOGGER.error("A stored secured setting could not be decrypted; it is left as stored.");
      return value;
    }
    return plaintext;
  }
}

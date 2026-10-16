package io.boomerang.core;

import io.boomerang.core.model.AESAlgorithm;
import io.boomerang.core.model.EncryptionConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Encrypts a value at rest under the one key every install carries ({@link EncryptionConfig}),
 * and labels it {@code crypt_v1{AESGCM|<ciphertext>}} so a stored value says whether it is
 * encrypted. Shared by secured settings ({@link SettingsService}) and secret parameter values
 * ({@code SecretValueMongoListener}). The key is derived once, not per value.
 */
@Component
public class SecretCipher {

  private static final Logger LOGGER = LogManager.getLogger();

  /** The label every encrypted value starts with. */
  public static final String LABEL = "crypt_v1";

  private static final String PREFIX = "crypt_v1{AESGCM|";

  private final TextEncryptor encryptor;

  public SecretCipher(EncryptionConfig encryptConfig) {
    this.encryptor =
        AESAlgorithm.textEncryptorFor(encryptConfig.getSecretKey(), encryptConfig.getSalt());
  }

  /** Whether a stored value already carries the label. */
  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith(LABEL);
  }

  /**
   * The labelled ciphertext of a value. Blank values and values already carrying the label pass
   * through, so a value is never encrypted twice. Null when encryption fails, as {@link
   * AESAlgorithm#encrypt} answers.
   */
  public String encrypt(String value) {
    if (!StringUtils.hasText(value) || isEncrypted(value)) {
      return value;
    }
    try {
      return PREFIX + encryptor.encrypt(value) + "}";
    } catch (RuntimeException e) {
      LOGGER.error("A value could not be encrypted ({}).", e.getClass().getName());
      return null;
    }
  }

  /**
   * The plaintext of a labelled value. A value without the label was stored as plaintext by an
   * earlier release and is returned as-is; it is encrypted on its next save. Null when the value
   * cannot be decrypted - a different key, or a tampered value - as {@link AESAlgorithm#decrypt}
   * answers.
   */
  public String decrypt(String value) {
    if (!StringUtils.hasText(value) || !isEncrypted(value)) {
      return value;
    }
    // Only the AESGCM label exists in storage: _0022__ReencryptSettingsAesGcm rewrites the retired
    // CBC scheme as the context starts, before anything is served.
    String ciphertext = value.replace(PREFIX, "").replace("}", "");
    try {
      return encryptor.decrypt(ciphertext);
    } catch (RuntimeException e) {
      LOGGER.error("A stored value could not be decrypted ({}).", e.getClass().getName());
      return null;
    }
  }
}

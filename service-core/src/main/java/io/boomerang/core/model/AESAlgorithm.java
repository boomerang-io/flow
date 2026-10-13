package io.boomerang.core.model;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * Encrypt stored {@code Setting} values with AES-256-GCM via {@link Encryptors#delux}: random IV
 * per call, authenticated ciphertext, hex-encoded. Non-deterministic by design - stored values are
 * only ever decrypted for use, never compared; the scheme is identified by the
 * {@code crypt_v1{AESGCM|...}} label {@code SettingsService} wraps around this output.
 *
 * <p>{@code delux} requires a hex salt, so the operator-supplied salt from {@code EncryptionConfig}
 * is hex-encoded before use.
 */
public final class AESAlgorithm {

  private static final Logger LOGGER = Logger.getLogger(AESAlgorithm.class.getName());

  private AESAlgorithm() {
    // Do nothing
  }

  public static String encrypt(String strToEncrypt, String secret, String salt) {
    try {
      return textEncryptorFor(secret, salt).encrypt(strToEncrypt);
    } catch (RuntimeException e) {
      LOGGER.log(Level.SEVERE, "Error encrypt value: ", e);
    }
    return null;
  }

  public static String decrypt(String strToDecrypt, String secret, String salt) {
    try {
      return textEncryptorFor(secret, salt).decrypt(strToDecrypt);
    } catch (RuntimeException e) {
      LOGGER.log(Level.SEVERE, "Error decrypt value: ", e);
    }
    return null;
  }

  private static TextEncryptor textEncryptorFor(String secret, String salt) {
    String hexSalt = new String(Hex.encode(salt.getBytes(StandardCharsets.UTF_8)));
    return Encryptors.delux(secret, hexSalt);
  }
}

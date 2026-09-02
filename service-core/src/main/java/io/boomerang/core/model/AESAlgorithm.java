package io.boomerang.core.model;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * AES encryption for stored {@code Setting} values, via Spring Security's {@link
 * Encryptors#delux} - AES-256-GCM with a random IV generated per call, hex-encoded. ({@code
 * Encryptors.delegatingText}, which adds a version-tagged output for future algorithm migrations,
 * does not exist in the spring-security-crypto version on this classpath; {@code delux} is its
 * current GCM equivalent - the app-level {@code crypt_v1{AESGCM|...}} label in {@code
 * SettingsService} is what identifies the scheme instead.)
 *
 * <p>Replaces a hand-rolled AES/CBC/PKCS5Padding scheme that reused ONE hardcoded IV for every
 * value it ever encrypted (defeating CBC's own security argument) and carried no authentication
 * tag. {@code service-loader}'s {@code _0041__ReencryptSettingsAesGcm} change unit re-encrypts
 * every value stored under the retired scheme; nothing in {@code service-core} reads that scheme
 * any more.
 *
 * <p>{@code delux} requires its salt as a hex string; the configured salt (an arbitrary
 * operator-supplied string, see {@code EncryptionConfig}) is hex-encoded first so any existing
 * configuration keeps working unchanged.
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

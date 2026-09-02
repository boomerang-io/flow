package io.boomerang.loader;

/**
 * The same {@code mongo.encrypt.secret} / {@code mongo.encrypt.salt} values service-core's {@code
 * EncryptionConfig} resolves, read here from a system property or environment variable (mirroring
 * {@code flow.mongo.uri}) and defaulting to the same literals as {@code EncryptionConfig}'s
 * {@code @Value} defaults, so an install that never configured them behaves identically in both
 * places. Needed by {@code _0041__ReencryptSettingsAesGcm} to decrypt/re-encrypt stored settings
 * with the same key derivation service-core uses.
 */
public record EncryptionSecrets(String secret, String salt) {

  private static final String DEFAULT_SECRET = "secret";
  private static final String DEFAULT_SALT = "salt";

  public static EncryptionSecrets fromEnvironment() {
    return new EncryptionSecrets(
        setting("mongo.encrypt.secret", "MONGO_ENCRYPT_SECRET", DEFAULT_SECRET),
        setting("mongo.encrypt.salt", "MONGO_ENCRYPT_SALT", DEFAULT_SALT));
  }

  private static String setting(String property, String envVar, String defaultValue) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      value = System.getenv(envVar);
    }
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}

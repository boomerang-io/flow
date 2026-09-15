package io.boomerang.loader.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.loader.CollectionNames;
import io.boomerang.loader.EncryptionSecrets;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Rollback;
import io.flamingock.api.annotations.TargetSystem;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.Encryptors;

/**
 * Bring every {@code settings.config[].value} of a {@code secured}-typed config to AES-256-GCM
 * ({@code crypt_v1{AESGCM|...}}, the same {@code Encryptors.delux} call service-core's
 * {@code AESAlgorithm} makes). service-core only recognises the AESGCM label, so this unit must
 * complete before it starts. Two populations exist and both are handled in one pass:
 *
 * <ul>
 *   <li>values under the retired static-IV AES/CBC label ({@code crypt_v1{AES|...}}) are decrypted
 *       with the frozen copy of that cipher below and re-encrypted;
 *   <li>values carrying no label at all were written as plaintext by releases whose encryption
 *       guard was inverted, so the stored value is its own plaintext and is encrypted in place.
 * </ul>
 *
 * <p>The legacy decrypt below is a frozen copy of the retired cipher: service-loader takes no
 * dependency on service-core, and nothing else may use it.
 *
 * <p>Idempotent - a value already carrying a {@code crypt_v1} label other than the legacy one is
 * left alone, so a second run changes nothing. Blank values and configs of any other type are never
 * touched, matching {@code SettingsService}'s own encrypt guard.
 */
@Change(id = "0044-reencrypt-settings-aes-gcm", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0044__ReencryptSettingsAesGcm {

  private static final Logger LOG = LoggerFactory.getLogger(_0044__ReencryptSettingsAesGcm.class);

  private static final String SECURED_TYPE = "secured";
  private static final String LABEL_PREFIX = "crypt_v1";
  private static final String LEGACY_PREFIX = "crypt_v1{AES|";
  private static final String NEW_PREFIX = "crypt_v1{AESGCM|";
  private static final String SUFFIX = "}";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names, EncryptionSecrets secrets) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));

    int reencrypted = 0;
    int encrypted = 0;
    int skipped = 0;
    for (Document setting : settings.find()) {
      @SuppressWarnings("unchecked")
      List<Document> configs = (List<Document>) setting.get("config");
      if (configs == null) {
        continue;
      }

      boolean changed = false;
      for (Document config : configs) {
        if (!SECURED_TYPE.equalsIgnoreCase(config.getString("type"))) {
          continue;
        }
        String value = config.getString("value");
        if (value == null || value.isBlank()) {
          continue;
        }
        boolean legacy = value.startsWith(LEGACY_PREFIX) && value.endsWith(SUFFIX);
        if (!legacy && value.startsWith(LABEL_PREFIX)) {
          continue;
        }

        try {
          String plaintext =
              legacy
                  ? legacyDecrypt(
                      value.substring(LEGACY_PREFIX.length(), value.length() - SUFFIX.length()),
                      secrets.secret(),
                      secrets.salt())
                  : value;
          config.put(
              "value", NEW_PREFIX + newEncrypt(plaintext, secrets.secret(), secrets.salt()) + SUFFIX);
          changed = true;
          if (legacy) {
            reencrypted++;
          } else {
            encrypted++;
          }
        } catch (RuntimeException | GeneralSecurityException e) {
          LOG.error(
              "Unable to encrypt setting {} config '{}' - left unchanged: {}",
              setting.get("_id"),
              config.getString("key"),
              e.getMessage());
          skipped++;
        }
      }

      if (changed) {
        settings.updateOne(Filters.eq("_id", setting.get("_id")), Updates.set("config", configs));
      }
    }

    LOG.info(
        "Settings encryption: {} value(s) re-encrypted from the retired scheme, {} plaintext"
            + " value(s) encrypted, {} left unchanged after a failure",
        reencrypted,
        encrypted,
        skipped);
  }

  /** The current scheme - identical to {@code AESAlgorithm.encrypt}. */
  private static String newEncrypt(String value, String secret, String salt) {
    String hexSalt = new String(Hex.encode(salt.getBytes(StandardCharsets.UTF_8)));
    return Encryptors.delux(secret, hexSalt).encrypt(value);
  }

  // ---- Retired AES/CBC/PKCS5Padding scheme - frozen, read-only, this migration's use only ----

  private static final int LEGACY_PWD_ITERATIONS = 131072;
  private static final int LEGACY_KEY_SIZE = 256;
  private static final byte[] LEGACY_IV =
      {11, 112, 13, 117, 45, 68, 17, -55, -6, 77, 10, -13, -78, 4, -127, -61};
  private static final String LEGACY_KEY_ALGORITHM = "AES";
  private static final String LEGACY_CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
  private static final String LEGACY_SECRET_KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA1";

  private static String legacyDecrypt(String ciphertext, String secret, String salt)
      throws GeneralSecurityException {
    SecretKeyFactory factory = SecretKeyFactory.getInstance(LEGACY_SECRET_KEY_FACTORY_ALGORITHM);
    PBEKeySpec spec =
        new PBEKeySpec(
            secret.toCharArray(),
            salt.getBytes(StandardCharsets.UTF_8),
            LEGACY_PWD_ITERATIONS,
            LEGACY_KEY_SIZE);
    SecretKeySpec secretKey =
        new SecretKeySpec(factory.generateSecret(spec).getEncoded(), LEGACY_KEY_ALGORITHM);

    Cipher cipher = Cipher.getInstance(LEGACY_CIPHER_ALGORITHM); // NOSONAR
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(LEGACY_IV));
    return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
  }

  @Rollback
  public void rollback() {
    // Not reversible: the retired ciphertext and the plaintext this unit replaces are exactly
    // what it removes. Pre-migration backups hold the old values.
  }
}

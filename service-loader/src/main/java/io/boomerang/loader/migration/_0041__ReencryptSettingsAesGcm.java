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
 * Re-encrypts every {@code settings.config[].value} stored under the retired
 * AES/CBC/PKCS5Padding scheme ({@code crypt_v1{AES|...}}, ONE hardcoded IV shared by every value
 * ever encrypted with it, no authentication tag) with AES-256-GCM ({@code crypt_v1{AESGCM|...}},
 * the same {@code Encryptors.delux} call {@code service-core}'s {@code AESAlgorithm} now uses).
 * {@code SettingsService.decrypt} only recognises the {@code AESGCM} label going forward - this
 * unit is what guarantees no {@code AES}-labelled value survives a deploy.
 *
 * <p><b>Why the legacy cipher is reproduced here rather than reused from service-core.</b>
 * {@code service-loader} has no dependency on {@code service-core} (migrations must not be coupled
 * to evolving entity/service classes) - the legacy decrypt below is a frozen, read-only copy of
 * the retired {@code AESAlgorithm}, used nowhere else and never to be updated.
 *
 * <p><b>Idempotency.</b> Only a value whose stored string starts with {@code crypt_v1{AES|} is
 * touched; an already-migrated ({@code AESGCM}), plaintext, or otherwise-shaped value is left
 * untouched. A rerun therefore finds nothing left to do. Safe on a database with no encrypted
 * settings at all (nothing matches the legacy prefix, so nothing is read or written).
 */
@Change(id = "0041-reencrypt-settings-aes-gcm", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
public class _0041__ReencryptSettingsAesGcm {

  private static final Logger LOG = LoggerFactory.getLogger(_0041__ReencryptSettingsAesGcm.class);

  private static final String SECURED_TYPE = "secured";
  private static final String LEGACY_PREFIX = "crypt_v1{AES|";
  private static final String NEW_PREFIX = "crypt_v1{AESGCM|";
  private static final String SUFFIX = "}";

  @Apply
  public void execute(MongoDatabase db, CollectionNames names, EncryptionSecrets secrets) {
    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));

    int reencrypted = 0;
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
        if (value == null || !value.startsWith(LEGACY_PREFIX) || !value.endsWith(SUFFIX)) {
          continue;
        }

        String legacyCiphertext =
            value.substring(LEGACY_PREFIX.length(), value.length() - SUFFIX.length());
        try {
          String plaintext = legacyDecrypt(legacyCiphertext, secrets.secret(), secrets.salt());
          String reencryptedValue =
              NEW_PREFIX + newEncrypt(plaintext, secrets.secret(), secrets.salt()) + SUFFIX;
          config.put("value", reencryptedValue);
          changed = true;
          reencrypted++;
        } catch (RuntimeException | GeneralSecurityException e) {
          LOG.error(
              "Unable to re-encrypt setting {} config '{}' - left unchanged: {}",
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
        "Settings re-encryption: {} value(s) migrated to AES-GCM, {} left unchanged after a"
            + " failed decrypt",
        reencrypted,
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
    // Not reversible in place - the point of this unit is that the retired scheme's key material
    // (a single shared IV, no authentication tag) should not be reconstructed. An operator who
    // truly needs the old ciphertext back has it in a pre-migration backup.
  }
}

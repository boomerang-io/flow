package io.boomerang.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.boomerang.core.model.AESAlgorithm;
import io.flamingock.api.RecoveryStrategy;
import io.flamingock.api.annotations.Apply;
import io.flamingock.api.annotations.Change;
import io.flamingock.api.annotations.Recovery;
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
import org.springframework.core.env.Environment;

/**
 * Bring every secured settings value to the current cipher, AES-256-GCM under {@code
 * flow.encrypt.secret}/{@code flow.encrypt.salt}, labelled {@code crypt_v1{AESGCM|…}}. Two
 * populations exist: values a v3 install encrypted with the retired static-IV AES/CBC cipher under
 * {@code mongo.encrypt.secret}/{@code mongo.encrypt.salt} ({@code crypt_v1{AES|…}}), decrypted here
 * with a frozen copy of that cipher; and unlabelled values, stored as their own plaintext while the
 * service's encryption guard was inverted.
 *
 * <p>A legacy value that does not decrypt, or a blank key with something to encrypt, stops the
 * migration: this unit runs once, and the service reads only the new label, so a value left behind
 * would be unreadable for good. Blank values and entries of any other type are untouched, and a value
 * already under the new label is skipped, so a second run changes nothing.
 */
@Change(id = "0022-reencrypt-settings-aes-gcm", author = "boomerang", transactional = false)
@TargetSystem(id = "flow-mongodb")
@Recovery(strategy = RecoveryStrategy.ALWAYS_RETRY)
public class _0022__ReencryptSettingsAesGcm {

  private static final Logger LOG = LoggerFactory.getLogger(_0022__ReencryptSettingsAesGcm.class);

  static final String LEGACY_SECRET = "mongo.encrypt.secret";
  static final String LEGACY_SALT = "mongo.encrypt.salt";
  static final String SECRET = "flow.encrypt.secret";
  static final String SALT = "flow.encrypt.salt";

  private static final String SECURED_TYPE = "secured";
  private static final String LABEL_PREFIX = "crypt_v1";
  static final String LEGACY_PREFIX = "crypt_v1{AES|";
  static final String NEW_PREFIX = "crypt_v1{AESGCM|";
  private static final String SUFFIX = "}";

  @Apply
  public void execute(MongoDatabase db, Environment env) {
    CollectionNames names = CollectionNames.from(env);
    String secret = property(env, SECRET);
    String salt = property(env, SALT);
    String legacySecret = property(env, LEGACY_SECRET);
    String legacySalt = property(env, LEGACY_SALT);

    MongoCollection<Document> settings = db.getCollection(names.resolve("settings"));
    int reencrypted = 0;
    int encrypted = 0;
    for (Document setting : settings.find()) {
      List<Document> configs = setting.getList("config", Document.class);
      if (configs == null) {
        continue;
      }
      boolean changed = false;
      for (Document config : configs) {
        String value = config.getString("value");
        if (!SECURED_TYPE.equalsIgnoreCase(config.getString("type"))
            || value == null
            || value.isBlank()) {
          continue;
        }
        boolean legacy = value.startsWith(LEGACY_PREFIX) && value.endsWith(SUFFIX);
        if (!legacy && value.startsWith(LABEL_PREFIX)) {
          continue;
        }
        String where = "setting '" + setting.getString("key") + "' entry '" + config.getString("key") + "'";
        if (secret.isBlank() || salt.isBlank()) {
          throw new IllegalStateException(
              SECRET + " and " + SALT + " must be set: " + where + " needs encrypting");
        }
        String plaintext =
            legacy
                ? legacyDecrypt(
                    value.substring(LEGACY_PREFIX.length(), value.length() - SUFFIX.length()),
                    legacySecret,
                    legacySalt,
                    where)
                : value;
        String ciphertext = AESAlgorithm.encrypt(plaintext, secret, salt);
        if (ciphertext == null) {
          throw new IllegalStateException("Could not encrypt " + where);
        }
        config.put("value", NEW_PREFIX + ciphertext + SUFFIX);
        changed = true;
        if (legacy) {
          reencrypted++;
        } else {
          encrypted++;
        }
      }
      if (changed) {
        settings.updateOne(Filters.eq("_id", setting.get("_id")), Updates.set("config", configs));
      }
    }
    LOG.info(
        "Settings encryption: {} value(s) re-encrypted from the v3 cipher, {} plaintext value(s)"
            + " encrypted",
        reencrypted,
        encrypted);
  }

  private static String property(Environment env, String name) {
    String value = env.getProperty(name);
    return (value != null) ? value : "";
  }

  // ---- The retired AES/CBC/PKCS5Padding cipher: frozen, decrypt only, this unit's use only ----

  private static final int LEGACY_PWD_ITERATIONS = 131072;
  private static final int LEGACY_KEY_SIZE = 256;
  private static final byte[] LEGACY_IV =
      {11, 112, 13, 117, 45, 68, 17, -55, -6, 77, 10, -13, -78, 4, -127, -61};

  /**
   * Decrypt a v3 value, or stop: a wrong pair almost always fails the padding check, and the rare
   * decrypt that passes it yields bytes that are not text.
   */
  private static String legacyDecrypt(String ciphertext, String secret, String salt, String where) {
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      PBEKeySpec spec =
          new PBEKeySpec(
              secret.toCharArray(),
              salt.getBytes(StandardCharsets.UTF_8),
              LEGACY_PWD_ITERATIONS,
              LEGACY_KEY_SIZE);
      SecretKeySpec key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding"); // NOSONAR
      cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(LEGACY_IV));
      String plaintext =
          new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
      if (!isText(plaintext)) {
        throw new GeneralSecurityException("decrypted bytes are not text");
      }
      return plaintext;
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException(
          where
              + " was encrypted by v3 under a pair other than "
              + LEGACY_SECRET
              + "/"
              + LEGACY_SALT
              + "; set v3's pair and start again ("
              + e.getMessage()
              + ")",
          e);
    }
  }

  private static boolean isText(String value) {
    return value.chars()
        .noneMatch(c -> c == '�' || (c < 0x20 && c != '\t' && c != '\n' && c != '\r'));
  }

  @Rollback
  public void rollback() {
    // Not reversible: the v3 ciphertext and the plaintext this unit replaces are what it removes.
  }
}

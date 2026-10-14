package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.boomerang.core.model.AESAlgorithm;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Secured settings end under the current cipher and key: a v3 install's AES/CBC values are
 * decrypted with the v3 pair and re-encrypted, plaintext values are encrypted, and the migration
 * stops rather than leave a value it cannot decrypt or encrypt.
 */
class SettingsEncryptionMigrationTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  private static final String PREFIX = "flowtest";
  private static final String V3_SECRET = "v3-secret";
  private static final String V3_SALT = "v3-salt";

  private static MongoClient client;

  @BeforeAll
  static void startMongo() {
    MONGO.start();
    client = MongoClients.create(MONGO.getReplicaSetUrl());
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void v3CiphertextAndPlaintextSecuredValuesEndUnderTheNewKey() throws Exception {
    MongoDatabase db = v3Database("reencrypt");
    ObjectId id =
        seedIntegrationSettings(
            db, "crypt_v1{AES|" + legacyEncrypt("s3cr3t-value", V3_SECRET, V3_SALT) + "}");

    MockEnvironment env = MigrationRunner.environment(PREFIX);
    env.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SECRET, V3_SECRET);
    env.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SALT, V3_SALT);
    assertThatCode(() -> MigrationRunner.run(uriOf("reencrypt"), env)).doesNotThrowAnyException();

    List<Document> config = configOf(db, id);
    String migrated = valueOf(config, "slack.signingSecret");
    String encrypted = valueOf(config, "slack.token");
    assertThat(migrated).startsWith("crypt_v1{AESGCM|").endsWith("}");
    assertThat(encrypted)
        .as("a secured value stored as plaintext is encrypted, not left in the clear")
        .startsWith("crypt_v1{AESGCM|")
        .endsWith("}");
    assertThat(decryptNew(migrated)).as("under the new key, not the v3 pair").isEqualTo("s3cr3t-value");
    assertThat(decryptNew(encrypted)).isEqualTo("xoxb-never-encrypted");
    assertThat(valueOf(config, "slack.clientSecret")).as("a blank secured value is untouched").isEmpty();
    assertThat(valueOf(config, "slack.appId")).as("a non-secured value is untouched").isEqualTo("A123");

    // A second run of every unit finds nothing left under the v3 label or unlabelled.
    db.getCollection(PREFIX + "_sys_migration_changelog").drop();
    assertThatCode(() -> MigrationRunner.run(uriOf("reencrypt"), env)).doesNotThrowAnyException();
    assertThat(valueOf(configOf(db, id), "slack.signingSecret")).isEqualTo(migrated);
    assertThat(valueOf(configOf(db, id), "slack.token")).isEqualTo(encrypted);
  }

  @Test
  void aV3ValueThatDoesNotDecryptStopsTheMigrationUntilTheRightPairIsSet() throws Exception {
    MongoDatabase db = v3Database("wrongpair");
    String v3Value = "crypt_v1{AES|" + legacyEncrypt("s3cr3t-value", V3_SECRET, V3_SALT) + "}";
    ObjectId id = seedIntegrationSettings(db, v3Value);

    MockEnvironment wrong = MigrationRunner.environment(PREFIX);
    wrong.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SECRET, "not-the-v3-secret");
    wrong.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SALT, V3_SALT);
    assertThatThrownBy(() -> MigrationRunner.run(uriOf("wrongpair"), wrong)).isInstanceOf(Exception.class);
    assertThat(valueOf(configOf(db, id), "slack.signingSecret"))
        .as("the v3 value is left exactly as it was")
        .isEqualTo(v3Value);

    // The unit retries on the next start, so supplying the pair is enough to recover.
    MockEnvironment right = MigrationRunner.environment(PREFIX);
    right.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SECRET, V3_SECRET);
    right.setProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SALT, V3_SALT);
    assertThatCode(() -> MigrationRunner.run(uriOf("wrongpair"), right)).doesNotThrowAnyException();
    assertThat(decryptNew(valueOf(configOf(db, id), "slack.signingSecret"))).isEqualTo("s3cr3t-value");
  }

  /**
   * An install that no longer has v3's pair can still upgrade: asked to, the unit blanks each value
   * that does not decrypt, to be entered again in Settings, and migrates the rest.
   */
  @Test
  void aV3ValueThatDoesNotDecryptIsResetWhenTheInstallAsksForIt() throws Exception {
    MongoDatabase db = v3Database("resetunreadable");
    ObjectId id =
        seedIntegrationSettings(
            db, "crypt_v1{AES|" + legacyEncrypt("s3cr3t-value", V3_SECRET, V3_SALT) + "}");

    MockEnvironment env = MigrationRunner.environment(PREFIX);
    env.setProperty(_0022__ReencryptSettingsAesGcm.RESET_UNREADABLE, "true");
    assertThatCode(() -> MigrationRunner.run(uriOf("resetunreadable"), env)).doesNotThrowAnyException();

    List<Document> config = configOf(db, id);
    assertThat(valueOf(config, "slack.signingSecret")).as("reset, to be entered again").isEmpty();
    assertThat(decryptNew(valueOf(config, "slack.token")))
        .as("a value that needs no v3 pair still migrates")
        .isEqualTo("xoxb-never-encrypted");
    assertThat(valueOf(config, "slack.appId")).isEqualTo("A123");
  }

  /**
   * v3's chart generated its pair into a Secret as FLOW_ENCRYPT_SECRET and FLOW_ENCRYPT_SALT. With
   * only those set, both the v3 pair and the new key default to them, so the Secret upgrades as it is.
   */
  @Test
  void aV3InstallUpgradesWithItsChartSecretUnchanged() throws Exception {
    MongoDatabase db = v3Database("chartsecret");
    ObjectId id =
        seedIntegrationSettings(
            db, "crypt_v1{AES|" + legacyEncrypt("s3cr3t-value", V3_SECRET, V3_SALT) + "}");

    MockEnvironment env = new MockEnvironment().withProperty("flow.mongo.collection.prefix", PREFIX);
    env.getPropertySources()
        .addLast(new ResourcePropertySource("classpath:application.properties"));
    env.getPropertySources()
        .addLast(
            new MapPropertySource(
                "v3-chart-secret",
                Map.of("FLOW_ENCRYPT_SECRET", V3_SECRET, "FLOW_ENCRYPT_SALT", V3_SALT)));
    assertThat(env.getProperty(_0022__ReencryptSettingsAesGcm.LEGACY_SECRET)).isEqualTo(V3_SECRET);
    assertThat(env.getProperty(_0022__ReencryptSettingsAesGcm.SECRET)).isEqualTo(V3_SECRET);

    assertThatCode(() -> MigrationRunner.run(uriOf("chartsecret"), env)).doesNotThrowAnyException();

    String migrated = valueOf(configOf(db, id), "slack.signingSecret");
    assertThat(migrated).startsWith("crypt_v1{AESGCM|");
    assertThat(
            AESAlgorithm.decrypt(
                migrated.substring("crypt_v1{AESGCM|".length(), migrated.length() - 1),
                V3_SECRET,
                V3_SALT))
        .isEqualTo("s3cr3t-value");
  }

  @Test
  void aBlankKeyStopsTheMigrationWhenThereIsSomethingToEncrypt() {
    MongoDatabase db = v3Database("blankkey");
    ObjectId id = seedIntegrationSettings(db, "");

    MockEnvironment blank = MigrationRunner.environment(PREFIX);
    blank.setProperty(_0022__ReencryptSettingsAesGcm.SECRET, "");
    assertThatThrownBy(() -> MigrationRunner.run(uriOf("blankkey"), blank)).isInstanceOf(Exception.class);
    assertThat(valueOf(configOf(db, id), "slack.token")).isEqualTo("xoxb-never-encrypted");
  }

  @Test
  void anEmptyDatabaseHasNothingToEncryptAndNeedsNoV3Pair() {
    assertThatCode(() -> MigrationRunner.run(uriOf("emptykeys"), PREFIX)).doesNotThrowAnyException();
    for (Document setting : client.getDatabase("emptykeys").getCollection(PREFIX + "_settings").find()) {
      for (Document entry : setting.getList("config", Document.class)) {
        if ("secured".equalsIgnoreCase(entry.getString("type"))) {
          assertThat(entry.getString("value")).as("%s is seeded blank", entry.getString("key")).isEmpty();
        }
      }
    }
  }

  // ---- fixtures and helpers ----

  private static MongoDatabase v3Database(String name) {
    MongoDatabase db = client.getDatabase(name);
    db.getCollection(PREFIX + "_sys_changelog_flow").insertOne(new Document("changeId", "112"));
    return db;
  }

  /** A v3 "extensions" document: one value as given, one plaintext, one blank, one not secured. */
  private static ObjectId seedIntegrationSettings(MongoDatabase db, String signingSecretValue) {
    ObjectId id = new ObjectId("62a7bec0a6166d30aff64a5b");
    db.getCollection(PREFIX + "_settings")
        .insertOne(
            new Document("_id", id)
                .append("key", "extensions")
                .append(
                    "config",
                    List.of(
                        entry("slack.signingSecret", "secured", signingSecretValue),
                        entry("slack.token", "secured", "xoxb-never-encrypted"),
                        entry("slack.clientSecret", "secured", ""),
                        entry("slack.appId", "text", "A123"))));
    return id;
  }

  private static Document entry(String key, String type, String value) {
    return new Document("key", key).append("type", type).append("value", value);
  }

  private static List<Document> configOf(MongoDatabase db, ObjectId id) {
    return db.getCollection(PREFIX + "_settings")
        .find(Filters.eq("_id", id))
        .first()
        .getList("config", Document.class);
  }

  private static String valueOf(List<Document> config, String key) {
    return config.stream()
        .filter(c -> key.equals(c.getString("key")))
        .findFirst()
        .orElseThrow()
        .getString("value");
  }

  private static String uriOf(String database) {
    return MONGO.getReplicaSetUrl(database);
  }

  private static String decryptNew(String labelled) {
    String raw = labelled.substring("crypt_v1{AESGCM|".length(), labelled.length() - 1);
    return AESAlgorithm.decrypt(raw, MigrationRunner.TEST_SECRET, MigrationRunner.TEST_SALT);
  }

  /** The retired AES/CBC cipher as v3 ran it, test-only. */
  private static String legacyEncrypt(String value, String secret, String salt) throws Exception {
    byte[] iv = {11, 112, 13, 117, 45, 68, 17, -55, -6, 77, 10, -13, -78, 4, -127, -61};
    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
    PBEKeySpec spec =
        new PBEKeySpec(secret.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), 131072, 256);
    SecretKeySpec key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
    return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }
}

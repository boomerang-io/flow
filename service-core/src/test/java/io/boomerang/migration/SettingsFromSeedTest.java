package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The settings collection after the migration chain: the seed's documents, keys, wording and types
 * on an empty database and on a v3 database, holding the v3 install's own values.
 */
class SettingsFromSeedTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  private static final String PREFIX = "flowtest";

  private static final String USER_DEFAULTS_ID = "6123c1e20b07a54cdce637c0";
  private static final String ACTIVITY_ID = "60245957226920beece4fdf9";
  private static final String CONTROLLER_ID = "5f32cb19d09662744c0df51d";
  private static final String EXTENSIONS_ID = "62a7bec0a6166d30aff64a5b";
  private static final String TEAMS_ID = "61393f5966c5eea103dfe134";
  private static final String FEATURES_ID = "612904d60b07a54cdc4dc6a9";
  private static final String WORKFLOW_ID = "60245b56226920beece547e3";
  private static final String CUSTOMIZATIONS_ID = "62b0f1f5a6166d30af05fa5d";

  private static MongoClient client;

  @BeforeAll
  static void startMongo() {
    MONGO.start();
    client = MongoClients.create(MONGO.getReplicaSetUrl("admin"));
  }

  @AfterAll
  static void closeClient() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void emptyDatabaseEndsWithExactlyTheSeedDocuments() {
    MongoDatabase db = migrate("empty");

    List<Document> seed = seed();
    assertThat(settings(db).countDocuments()).isEqualTo(seed.size());
    for (Document seedDocument : seed) {
      Document stored = setting(db, seedDocument.getString("key"));
      if (!seedDocument.containsKey("_id")) {
        stored.remove("_id");
      }
      assertThat(stored).as(seedDocument.getString("key")).isEqualTo(seedDocument);
    }
  }

  @Test
  void v3InstallEndsKeyedAsTheSeedHoldingItsOwnValues() {
    MongoDatabase db = v3Install("v3settings");
    migrate("v3settings");

    List<Document> seed = seed();
    assertThat(settings(db).distinct("key", String.class).into(new ArrayList<>()))
        .containsExactlyInAnyOrderElementsOf(
            seed.stream().map(document -> document.getString("key")).toList());
    assertThat(settings(db).countDocuments()).isEqualTo(seed.size());
    for (Document seedDocument : seed) {
      assertShapedLikeSeed(setting(db, seedDocument.getString("key")), seedDocument);
    }

    // The v3 documents keep their _ids under their seed keys.
    assertThat(setting(db, "task").get("_id")).isEqualTo(new ObjectId(CONTROLLER_ID));
    assertThat(setting(db, "workflowrun").get("_id")).isEqualTo(new ObjectId(ACTIVITY_ID));
    assertThat(setting(db, "integration").get("_id")).isEqualTo(new ObjectId(EXTENSIONS_ID));
    assertThat(setting(db, "quotas").get("_id")).isEqualTo(new ObjectId(TEAMS_ID));
    assertThat(setting(db, "features").get("_id")).isEqualTo(new ObjectId(FEATURES_ID));
    assertThat(setting(db, "customizations").get("_id")).isEqualTo(new ObjectId(CUSTOMIZATIONS_ID));

    // Values carried through the earlier keys; the retired timeout and image take the seed's.
    assertThat(value(db, "task", "debug")).isEqualTo("false");
    assertThat(value(db, "task", "default.image")).isEqualTo("boomerangio/task-flow:3.1.0");
    assertThat(value(db, "task", "deletion.policy")).isEqualTo("Always");
    assertThat(value(db, "task", "edit.verified")).isEqualTo("true");
    assertThat(value(db, "task", "default.timeout")).isEqualTo("0");
    assertThat(value(db, "quotas", "max.workflowrun.concurrent")).isEqualTo("8");
    assertThat(value(db, "quotas", "max.workflow.count")).isEqualTo("25");
    assertThat(value(db, "quotas", "max.workflowrun.monthly")).isEqualTo("200");
    assertThat(value(db, "quotas", "max.workflowrun.duration")).isEqualTo("60");
    assertThat(value(db, "quotas", "max.workflow.storage")).isEqualTo("10Gi");
    assertThat(value(db, "quotas", "max.workflowrun.storage")).isEqualTo("2Gi");
    assertThat(value(db, "quotas", "max.artifact.storage")).isEqualTo("5Gi");
    assertThat(value(db, "features", "workspaceQuotas")).isEqualTo("false");
    assertThat(value(db, "features", "workspaceTasks")).isEqualTo("false");
    assertThat(value(db, "features", "insights")).isEqualTo("false");
    assertThat(value(db, "integration", "slack.token")).isEqualTo("crypt_v1{AES|slack-token}");
    assertThat(value(db, "integration", "slack.appId")).isEqualTo("A0123");
    assertThat(value(db, "customizations", "appName")).isEqualTo("Acme Flow");

    // A plaintext value under an entry the seed secures is reset, never decrypted as a secret.
    assertThat(value(db, "integration", "slack.signingSecret")).isEmpty();

    // An entry the seed does not define is kept after the seed's.
    List<Document> integration = configOf(setting(db, "integration"));
    assertThat(integration.get(integration.size() - 1).getString("key")).isEqualTo("slack.installURL");
    assertThat(value(db, "integration", "slack.installURL")).isEqualTo("https://slack.example/install");

    // Retired entries and documents are gone.
    assertThat(keysOf(setting(db, "workflowrun")))
        .containsExactly("max.nesting.depth", "max.foreach.items");
    assertThat(settings(db).find(Filters.eq("_id", new ObjectId(USER_DEFAULTS_ID))).first()).isNull();
    assertThat(settings(db).find(Filters.eq("_id", new ObjectId(WORKFLOW_ID))).first()).isNull();
    for (Document stored : settings(db).find()) {
      assertThat(stored.containsKey("_class")).as(stored.getString("key")).isFalse();
    }
  }

  @Test
  void secondRunChangesNothing() {
    for (MongoDatabase db : List.of(migrate("rerunempty"), migrateV3("rerunv3"))) {
      List<Document> before = snapshot(db);
      new _0021__BuildSettingsFromSeed().execute(db, new CollectionNames(PREFIX));
      assertThat(snapshot(db)).isEqualTo(before);
    }
  }

  @Test
  void taskDefaultTimeoutDropsTheShippedNinetyAndKeepsAnOperatorsOwnValue() {
    MongoDatabase shipped = v3Install("timeoutshipped");
    migrate("timeoutshipped");
    assertThat(value(shipped, "task", "default.timeout"))
        .as("the shipped 90 becomes 0 - a task inherits its run's timeout")
        .isEqualTo("0");
    assertThat(storedEntry(shipped, "task", "default.timeout").getString("label"))
        .isEqualTo("Maximum task duration");

    MongoDatabase operator = v3Install("timeoutoperator");
    setV3Value(operator, CONTROLLER_ID, "task.timeout.configuration", "45");
    migrate("timeoutoperator");
    assertThat(value(operator, "task", "default.timeout"))
        .as("45 is an operator choice, not the shipped default")
        .isEqualTo("45");
    assertThat(storedEntry(operator, "task", "default.timeout").getString("label"))
        .isEqualTo("Maximum task duration");
  }

  @Test
  void deletionPolicyThatIsNoLongerAnOptionTakesTheSeedDefault() {
    MongoDatabase db = v3Install("deletionpolicy");
    setV3Value(db, CONTROLLER_ID, "job.deletion.policy", "OnFailure");
    migrate("deletionpolicy");

    assertThat(value(db, "task", "deletion.policy")).isEqualTo("Never");
  }

  @Test
  void entriesUnderKeysALaterReleaseRenamedAreCarried() {
    MongoDatabase db = client.getDatabase("laterkeys");
    settings(db)
        .insertOne(
            new Document("_id", new ObjectId(TEAMS_ID))
                .append("key", "workspaces")
                .append("config", List.of(entry("max.workflow.count", "number", "15"))));
    settings(db)
        .insertOne(
            new Document("_id", new ObjectId(FEATURES_ID))
                .append("key", "features")
                .append("config", List.of(entry("teamQuotas", "boolean", "false"))));
    settings(db)
        .insertOne(
            new Document("_id", new ObjectId(EXTENSIONS_ID))
                .append("key", "integration")
                .append("config", List.of(entry("github.pem", "secured", "crypt_v1{AES|pem}"))));
    migrate("laterkeys");

    assertThat(value(db, "quotas", "max.workflow.count")).isEqualTo("15");
    assertThat(value(db, "features", "workspaceQuotas")).isEqualTo("false");
    assertThat(value(db, "integration", "github.jwt")).isEqualTo("crypt_v1{AES|pem}");
    assertThat(keysOf(setting(db, "integration"))).doesNotContain("github.pem");
  }

  /**
   * The v3 settings collection as the v3 loader left it: eight documents under their v3 keys, each
   * carrying the v3 {@code _class}, and a changelog holding the last v3 changeset.
   */
  private static MongoDatabase v3Install(String database) {
    MongoDatabase db = client.getDatabase(database);
    db.getCollection(PREFIX + "_sys_changelog_flow").insertOne(new Document("changeId", "112"));
    insertV3(
        db,
        USER_DEFAULTS_ID,
        "users",
        entry("max.user.workflow.count", "number", "10"),
        entry("max.user.workflow.storage", "text", "25Gi"));
    insertV3(
        db,
        ACTIVITY_ID,
        "activity",
        entry("storage.size", "text", "1Gi"),
        entry("storage.class", "text", ""),
        entry("storage.accessMode", "select", "ReadWriteMany"),
        entry("max.storage.size", "text", "5Gi"));
    insertV3(
        db,
        WORKFLOW_ID,
        "workflow",
        entry("storage.size", "text", "1Gi"),
        entry("storage.class", "text", ""),
        entry("storage.accessMode", "select", "ReadWriteMany"),
        entry("max.storage.size", "text", "5Gi"));
    insertV3(
        db,
        CONTROLLER_ID,
        "controller",
        entry("enable.debug", "boolean", "false"),
        entry("worker.image", "text", "boomerangio/worker-flow:2.11.15"),
        entry("job.deletion.policy", "select", "Always"),
        entry("enable.tasks", "boolean", "true"),
        entry("task.timeout.configuration", "number", "90"));
    insertV3(
        db,
        EXTENSIONS_ID,
        "extensions",
        entry("slack.token", "secured", "crypt_v1{AES|slack-token}"),
        entry("slack.appId", "text", "A0123"),
        entry("slack.clientId", "text", ""),
        entry("slack.clientSecret", "secured", ""),
        // v3 secures it; stored as text here, the type rule must not carry the plaintext.
        entry("slack.signingSecret", "text", "plaintext-signing-secret"),
        entry("slack.installURL", "text", "https://slack.example/install"));
    insertV3(
        db,
        TEAMS_ID,
        "teams",
        entry("max.team.concurrent.workflows", "number", "8"),
        entry("max.team.workflow.count", "number", "25"),
        entry("max.team.workflow.execution.monthly", "number", "200"),
        entry("max.team.workflow.duration", "number", "60"),
        entry("max.team.workflow.storage", "text", "10Gi"));
    insertV3(
        db,
        FEATURES_ID,
        "features",
        entry("activity", "boolean", "true"),
        entry("insights", "boolean", "false"),
        entry("workflowQuotas", "boolean", "false"),
        entry("teamParameters", "boolean", "true"),
        entry("teamManagement", "boolean", "true"),
        entry("teamTasks", "boolean", "false"));
    insertV3(
        db,
        CUSTOMIZATIONS_ID,
        "customizations",
        entry("appName", "text", "Acme Flow"),
        entry("platformName", "text", "Boomerang"));
    return db;
  }

  private static void insertV3(MongoDatabase db, String id, String key, Document... config) {
    settings(db)
        .insertOne(
            new Document("_id", new ObjectId(id))
                .append("key", key)
                .append("name", key + " (v3)")
                .append("type", "ValuesList")
                .append("config", List.of(config))
                .append("_class", "net.boomerangplatform.mongo.entity.FlowSettingsEntity"));
  }

  private static Document entry(String key, String type, String value) {
    return new Document("key", key)
        .append("label", key)
        .append("description", key)
        .append("type", type)
        .append("value", value)
        .append("readOnly", false);
  }

  private static void setV3Value(MongoDatabase db, String id, String key, String value) {
    settings(db)
        .updateOne(
            Filters.and(Filters.eq("_id", new ObjectId(id)), Filters.eq("config.key", key)),
            new Document("$set", new Document("config.$.value", value)));
  }

  private static MongoDatabase migrate(String database) {
    String uri = MONGO.getReplicaSetUrl(database);
    assertThatCode(() -> MigrationRunner.run(uri, PREFIX)).doesNotThrowAnyException();
    return client.getDatabase(database);
  }

  private static MongoDatabase migrateV3(String database) {
    v3Install(database);
    return migrate(database);
  }

  /** The stored document is the seed's, apart from its {@code _id}, its values and kept entries. */
  private static void assertShapedLikeSeed(Document stored, Document seedDocument) {
    String key = seedDocument.getString("key");
    assertThat(withoutFields(stored, "_id", "config"))
        .as(key)
        .isEqualTo(withoutFields(seedDocument, "_id", "config"));
    List<Document> storedConfig = configOf(stored);
    List<Document> seedConfig = configOf(seedDocument);
    assertThat(storedConfig.size()).as(key).isGreaterThanOrEqualTo(seedConfig.size());
    for (int i = 0; i < seedConfig.size(); i++) {
      assertThat(withoutFields(storedConfig.get(i), "value"))
          .as(key + " entry " + i)
          .isEqualTo(withoutFields(seedConfig.get(i), "value"));
    }
  }

  private static Document withoutFields(Document document, String... fields) {
    Document copy = new Document(document);
    for (String field : fields) {
      copy.remove(field);
    }
    return copy;
  }

  private static List<Document> snapshot(MongoDatabase db) {
    return settings(db).find().sort(Sorts.ascending("key")).into(new ArrayList<>());
  }

  private static List<Document> seed() {
    return SeedResources.load("seed/settings.json");
  }

  private static MongoCollection<Document> settings(MongoDatabase db) {
    return db.getCollection(PREFIX + "_settings");
  }

  private static Document setting(MongoDatabase db, String key) {
    Document setting = settings(db).find(Filters.eq("key", key)).first();
    assertThat(setting).as("settings '%s'", key).isNotNull();
    return setting;
  }

  private static Document storedEntry(MongoDatabase db, String key, String entryKey) {
    return configOf(setting(db, key)).stream()
        .filter(entry -> entryKey.equals(entry.getString("key")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + " has no " + entryKey));
  }

  private static String value(MongoDatabase db, String key, String entryKey) {
    return storedEntry(db, key, entryKey).getString("value");
  }

  private static List<Document> configOf(Document setting) {
    return setting.getList("config", Document.class);
  }

  private static List<String> keysOf(Document setting) {
    return configOf(setting).stream().map(entry -> entry.getString("key")).toList();
  }
}

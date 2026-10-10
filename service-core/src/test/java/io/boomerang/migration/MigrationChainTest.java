package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The chain against its two starting points, an empty database and a v3 install, and against the
 * databases it refuses. Each test runs the chain outside Spring on its own database.
 */
class MigrationChainTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  private static final String PREFIX = "flowtest";
  private static final Date EARLIER = Date.from(Instant.parse("2026-01-01T00:00:00Z"));
  private static final Date LATER = Date.from(Instant.parse("2026-01-02T00:00:00Z"));

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

  // ===================================================================================
  // An empty database
  // ===================================================================================

  @Test
  void anEmptyDatabaseIsSeededAndASecondRunChangesNothing() {
    MongoDatabase db = migrate("empty");

    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", "root:root")).first()).isNotNull();
    Document workspace = collection(db, "workspaces").find(Filters.eq("name", "system")).first();
    assertThat(workspace).isNotNull();
    assertThat(workspace.getString("type")).isEqualTo("system");
    String workspaceNode = "workspace:" + workspace.get("_id");
    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", workspaceNode)).first()).isNotNull();
    assertThat(
            collection(db, "rel_edges")
                .countDocuments(Filters.and(Filters.eq("from", "root:root"), Filters.eq("to", workspaceNode))))
        .isEqualTo(1);
    // No users yet, so no memberships.
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "memberOf"))).isZero();

    assertThat(collection(db, "roles").countDocuments()).isEqualTo(5);
    assertThat(collection(db, "settings").countDocuments()).isEqualTo(9);
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(90);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(133);
    assertThat(collection(db, "workflow_templates").countDocuments()).isEqualTo(2);
    assertThat(collection(db, "integration_templates").countDocuments()).isEqualTo(2);
    // Every task is in the global catalogue: a task node the root reaches by hasTask.
    assertThat(collection(db, "rel_nodes").countDocuments(Filters.eq("type", "task"))).isEqualTo(90);
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "hasTask"))).isEqualTo(90);
    assertAiTaskSeeded(db);
    assertArtifactTasksSeeded(db);
    assertIndexInventory(db);

    long nodes = collection(db, "rel_nodes").countDocuments();
    long edges = collection(db, "rel_edges").countDocuments();
    forgetHistory(db);
    migrate("empty");
    assertThat(collection(db, "roles").countDocuments()).isEqualTo(5);
    assertThat(collection(db, "settings").countDocuments()).isEqualTo(9);
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(90);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(133);
    assertThat(collection(db, "workspaces").countDocuments()).isEqualTo(1);
    assertThat(collection(db, "rel_nodes").countDocuments()).isEqualTo(nodes);
    assertThat(collection(db, "rel_edges").countDocuments()).isEqualTo(edges);
  }

  // ===================================================================================
  // A v3 install
  // ===================================================================================

  /**
   * A v3-shaped fixture: the legacy changelog's v3 marker, settings under their v3 ids, the v3
   * catalogue in {@code task_templates}, a task run on the old reference fields, and one team with
   * an approver group. The real-data counterpart is {@link V3DumpMigrationTest}.
   */
  @Test
  void aV3InstallIsMigratedInPlace() {
    MongoDatabase db = database("v3");
    markV3(db);
    for (String[] setting :
        new String[][] {
          {"5f32cb19d09662744c0df51d", "controller"},
          {"62a7bec0a6166d30aff64a5b", "extensions"},
          {"60245957226920beece4fdf9", "activity"},
          {"60245b56226920beece547e3", "execution"},
          {"612904d60b07a54cdc4dc6a9", "toggles"},
          {"61393f5966c5eea103dfe134", "quota"},
          {"62b0f1f5a6166d30af05fa5d", "branding"}
        }) {
      collection(db, "settings")
          .insertOne(new Document("_id", new ObjectId(setting[0])).append("key", setting[1]));
    }

    ObjectId minimalTaskId = new ObjectId();
    collection(db, "task_templates").insertOne(new Document("_id", minimalTaskId).append("name", "legacy-task"));
    ObjectId customTaskId = new ObjectId();
    collection(db, "task_templates")
        .insertOne(
            new Document("_id", customTaskId)
                .append("name", "Custom Task Example")
                .append("nodetype", "templateTask")
                .append("status", "active")
                .append("verified", true)
                .append("category", "Custom")
                .append("description", "A custom task example")
                .append("icon", "Add")
                .append("createdDate", EARLIER)
                .append(
                    "revisions",
                    List.of(
                        new Document("version", 1)
                            .append("image", "")
                            .append("command", List.of())
                            .append("arguments", List.of())
                            .append(
                                "config",
                                List.of(
                                    new Document("key", "path")
                                        .append("label", "Path")
                                        .append("type", "text")
                                        .append("description", "")
                                        .append("placeholder", "")
                                        .append("readOnly", false)))
                            .append(
                                "changelog",
                                new Document("userId", "5e831153d0827100011c29f6")
                                    .append("userName", "A Person")
                                    .append("reason", "")
                                    .append("date", EARLIER)))));
    ObjectId taskRunId = new ObjectId();
    collection(db, "task_runs")
        .insertOne(
            new Document("_id", taskRunId)
                .append("name", "step-1")
                .append("templateRef", "custom-task-example")
                .append("templateVersion", 1));
    ObjectId teamId = new ObjectId();
    collection(db, "teams")
        .insertOne(
            new Document("_id", teamId)
                .append("_class", "net.boomerangplatform.mongo.entity.TeamEntity")
                .append("name", "Platform Team")
                .append("isActive", true)
                .append(
                    "approverGroups",
                    List.of(
                        new Document("name", "Release approvers")
                            .append("approvers", List.of(new Document("userId", "user-1"))))));
    // A v3 team that happens to be called "system", the name the seeded system workspace owns.
    ObjectId systemTeamId = new ObjectId();
    collection(db, "teams")
        .insertOne(
            new Document("_id", systemTeamId)
                .append("_class", "net.boomerangplatform.mongo.entity.TeamEntity")
                .append("name", "System")
                .append("isActive", true));

    migrate("v3");

    // Settings: the seven v3 documents keep their ids under the seed's keys; auth, audit and
    // artifacts are new; the v3 workflow storage document is gone.
    MongoCollection<Document> settings = collection(db, "settings");
    assertThat(settings.countDocuments()).isEqualTo(9);
    assertThat(keyOf(settings, "5f32cb19d09662744c0df51d")).isEqualTo("task");
    assertThat(keyOf(settings, "62a7bec0a6166d30aff64a5b")).isEqualTo("integration");
    assertThat(keyOf(settings, "60245957226920beece4fdf9")).isEqualTo("workflowrun");
    assertThat(settings.find(Filters.eq("_id", new ObjectId("60245b56226920beece547e3"))).first()).isNull();

    // The catalogue: both v3 tasks migrated beside the 90 seeded ones; task_templates is gone.
    MongoCollection<Document> tasks = collection(db, "tasks");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    assertThat(tasks.countDocuments()).isEqualTo(92);
    assertThat(revisions.countDocuments()).isEqualTo(134);
    assertThat(collection(db, "task_templates").countDocuments()).isZero();
    assertThat(tasks.find(Filters.eq("_id", minimalTaskId)).first().getString("name")).isEqualTo("legacy-task");
    Document customTask = tasks.find(Filters.eq("_id", customTaskId)).first();
    assertThat(customTask.getString("name")).isEqualTo("custom-task-example");
    assertThat(customTask.getString("type")).isEqualTo("template");
    Document customRevision = revisions.find(Filters.eq("parentRef", customTaskId.toString())).first();
    assertThat(customRevision.getString("displayName")).isEqualTo("Custom Task Example");
    assertThat(customRevision.containsKey("config")).isFalse();
    Document changelog = customRevision.get("changelog", Document.class);
    assertThat(changelog.getString("author")).isEqualTo("5e831153d0827100011c29f6");
    assertThat(changelog.containsKey("userName")).as("the author's name is not carried").isFalse();
    List<Document> params = customRevision.get("spec", Document.class).getList("params", Document.class);
    assertThat(params).extracting(param -> param.getString("name")).containsExactly("path");

    // The task run resolves the old template reference and version.
    Document taskRun = collection(db, "task_runs").find(Filters.eq("_id", taskRunId)).first();
    assertThat(taskRun.getString("taskRef")).isEqualTo(customTaskId.toString());
    assertThat(taskRun.getInteger("taskVersion")).isEqualTo(1);
    assertThat(taskRun.containsKey("templateRef")).isFalse();
    assertThat(taskRun.containsKey("templateVersion")).isFalse();

    // Workspaces live in one collection: the team, the system workspace beside it.
    assertThat(database("v3").listCollectionNames().into(new ArrayList<>())).doesNotContain(PREFIX + "_teams");
    Document team = collection(db, "workspaces").find(Filters.eq("_id", teamId)).first();
    assertThat(team.getString("name")).isEqualTo("platform-team");
    // The seeded system workspace is the one named "system"; the v3 team of that name is renamed.
    List<Document> named =
        collection(db, "workspaces").find(Filters.eq("name", "system")).into(new ArrayList<>());
    assertThat(named).hasSize(1);
    assertThat(named.get(0).getString("type")).isEqualTo("system");
    assertThat(collection(db, "workspaces").find(Filters.eq("_id", systemTeamId)).first().getString("name"))
        .isEqualTo("system-team");

    // The approver group stays reachable from its workspace, and its hand-off field is gone.
    Document group = collection(db, "approver_groups").find(Filters.eq("name", "Release approvers")).first();
    assertThat(group.getList("approvers", String.class)).containsExactly("user-1");
    assertThat(group.containsKey("workspaceRef")).isFalse();
    assertThat(
            collection(db, "rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "workspace:" + teamId),
                        Filters.eq("label", "hasApproverGroup"),
                        Filters.eq("to", "approvergroup:" + group.get("_id")))))
        .isEqualTo(1);

    assertThat(collection(db, "workflow_templates").countDocuments()).isEqualTo(2);
    assertThat(collection(db, "integration_templates").countDocuments()).isEqualTo(2);
    assertThat(collection(db, "roles").countDocuments()).isEqualTo(5);
    assertThat(collection(db, "parameters").countDocuments()).isZero();
    assertIndexInventory(db);

    // Every unit again over the migrated database changes nothing.
    forgetHistory(db);
    migrate("v3");
    assertThat(settings.countDocuments()).isEqualTo(9);
    assertThat(tasks.countDocuments()).isEqualTo(92);
    assertThat(revisions.countDocuments()).isEqualTo(134);
    assertThat(collection(db, "approver_groups").countDocuments()).isEqualTo(1);
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "hasApproverGroup"))).isEqualTo(1);
    assertThat(collection(db, "task_runs").find(Filters.eq("_id", taskRunId)).first().getString("taskRef"))
        .isEqualTo(customTaskId.toString());
  }

  /**
   * Emails are lower-cased before the v3 graph is built, so user nodes are slugged by the stored
   * address. Two accounts that would share one address are left exactly as they are and reported.
   */
  @Test
  void userEmailsAreLowerCasedBeforeTheGraphAndCaseCollisionsAreLeftAlone() {
    MongoDatabase db = database("emails");
    markV3(db);
    MongoCollection<Document> users = collection(db, "users");
    ObjectId alreadyLower = insertUser(users, "ada.lovelace@example.com");
    ObjectId mixedCase = insertUser(users, "Grace.Hopper@Example.COM");
    ObjectId collidingUpper = insertUser(users, "Collide@example.com");
    ObjectId collidingLower = insertUser(users, "collide@example.com");
    ObjectId withoutEmail = new ObjectId();
    users.insertOne(new Document("_id", withoutEmail).append("name", "no email at all"));

    migrate("emails");

    assertThat(emailOf(users, alreadyLower)).isEqualTo("ada.lovelace@example.com");
    assertThat(emailOf(users, mixedCase)).isEqualTo("grace.hopper@example.com");
    assertThat(emailOf(users, collidingUpper)).isEqualTo("Collide@example.com");
    assertThat(emailOf(users, collidingLower)).isEqualTo("collide@example.com");
    assertThat(users.countDocuments()).as("no account is merged or deleted").isEqualTo(5);
    assertThat(users.find(Filters.eq("_id", withoutEmail)).first().containsKey("email")).isFalse();
    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", "user:" + mixedCase)).first().getString("slug"))
        .isEqualTo("grace.hopper@example.com");
    assertThat(indexesOf(db, "users").get("email_unique").getBoolean("unique")).isTrue();

    forgetHistory(db);
    migrate("emails");
    assertThat(emailOf(users, mixedCase)).isEqualTo("grace.hopper@example.com");
    assertThat(emailOf(users, collidingUpper)).isEqualTo("Collide@example.com");
    assertThat(users.countDocuments()).isEqualTo(5);
  }

  // ===================================================================================
  // Databases the chain refuses
  // ===================================================================================

  @Test
  void aBetaDatabaseIsRefusedUntilItIsReset() {
    MongoDatabase db = database("beta");
    collection(db, "sys_changelog_loader").insertOne(new Document("changeId", "0001-baseline"));

    // Flamingock logs the guard's message at ERROR and fails the run with its own exception.
    assertThatThrownBy(() -> MigrationRunner.run(uriOf("beta"), PREFIX)).isInstanceOf(Exception.class);
    assertThat(collection(db, "rel_nodes").countDocuments()).as("nothing is written").isZero();

    // The check reruns on the next start, so a reset database migrates.
    collection(db, "sys_changelog_loader").drop();
    migrate("beta");
    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", "root:root")).first()).isNotNull();
  }

  @Test
  void aV4DatabaseIsRefused() {
    MongoDatabase db = database("v4");
    collection(db, "sys_changelog_flow").insertOne(new Document("changeId", "112"));
    collection(db, "sys_changelog_flow").insertOne(new Document("changeId", "4000"));

    assertThatThrownBy(() -> MigrationRunner.run(uriOf("v4"), PREFIX)).isInstanceOf(Exception.class);
    assertThat(collection(db, "rel_nodes").countDocuments()).as("nothing is written").isZero();
    assertThat(collection(db, "settings").countDocuments()).isZero();
  }

  @Test
  void failsWhenMongoIsUnreachable() {
    assertThatThrownBy(
            () ->
                MigrationRunner.run(
                    "mongodb://localhost:1/boomerang?serverSelectionTimeoutMS=1500&connectTimeoutMS=1500",
                    PREFIX))
        .isInstanceOf(Exception.class);
  }

  // ===================================================================================
  // Units re-run after an interruption
  // ===================================================================================

  /**
   * A team is rewritten only after its approver groups are written. Interrupted in between, the
   * re-run finds the team still in its v3 shape and must not write its groups a second time.
   */
  @Test
  void aWorkspaceUnitReRunAfterItsGroupsWereWrittenDoesNotDuplicateThem() {
    MongoDatabase db = database("approver-rerun");
    markV3(db);
    Document team =
        new Document("_id", new ObjectId())
            .append("_class", "net.boomerangplatform.mongo.entity.TeamEntity")
            .append("name", "Platform Team")
            .append("isActive", true)
            .append(
                "approverGroups",
                List.of(
                    new Document("name", "Release approvers")
                        .append("approvers", List.of(new Document("userId", "user-1")))));
    collection(db, "workspaces").insertOne(team);
    Environment environment = MigrationRunner.environment(PREFIX);

    new _0007__V3MigrateWorkspaces().execute(db, environment);
    // The interruption: the groups are written, the team is not yet rewritten.
    collection(db, "workspaces").replaceOne(Filters.eq("_id", team.get("_id")), team);
    new _0007__V3MigrateWorkspaces().execute(db, environment);

    assertThat(collection(db, "approver_groups").countDocuments()).isEqualTo(1);
    assertThat(collection(db, "workspaces").find(Filters.eq("_id", team.get("_id"))).first())
        .doesNotContainKey("_class");
  }

  /**
   * A v3 task keeps the seed's id but takes its name from v3's display name. A task an install
   * renamed is still found by its id, rather than inserted again under that id.
   */
  @Test
  void theCatalogueSeedFindsARenamedV3TaskByItsId() {
    MongoDatabase db = database("renamed-task");
    ObjectId seededId = new ObjectId("600b2f5520a674b1d2cb4635");
    collection(db, "tasks")
        .insertOne(new Document("_id", seededId).append("name", "our-lock").append("status", "active"));

    assertThatCode(
            () -> new _0017__SeedTaskCatalogue().execute(db, MigrationRunner.environment(PREFIX)))
        .doesNotThrowAnyException();

    MongoCollection<Document> tasks = collection(db, "tasks");
    assertThat(tasks.countDocuments(Filters.eq("_id", seededId))).isEqualTo(1);
    assertThat(tasks.find(Filters.eq("_id", seededId)).first().getString("name")).isEqualTo("our-lock");
    assertThat(tasks.countDocuments(Filters.eq("name", "acquire-lock"))).isZero();
    assertThat(collection(db, "task_revisions").countDocuments(Filters.eq("parentRef", seededId.toString())))
        .isPositive();
  }

  // ===================================================================================
  // The index unit
  // ===================================================================================

  @Test
  void theIndexUnitDedupesAndReplacesOnlyConflictingIndexes() {
    MongoDatabase db = database("indexes");
    MongoCollection<Document> taskRuns = collection(db, "task_runs");
    ObjectId stillRunning = insertTaskRun(taskRuns, "run-1", "build", "running", EARLIER);
    ObjectId finished = insertTaskRun(taskRuns, "run-1", "build", "succeeded", LATER);
    MongoCollection<Document> actions = collection(db, "actions");
    ObjectId firstGate = insertAction(actions, "task-run-1", EARLIER);
    ObjectId repeatGate = insertAction(actions, "task-run-1", LATER);
    ObjectId nullRef = insertAction(actions, null, EARLIER);
    actions.updateOne(Filters.eq("_id", nullRef), new Document("$set", new Document("taskRunRef", null)));
    ObjectId noRef = new ObjectId();
    actions.insertOne(new Document("_id", noRef).append("creationDate", EARLIER));
    ObjectId anotherNoRef = new ObjectId();
    actions.insertOne(new Document("_id", anotherNoRef).append("creationDate", LATER));
    MongoCollection<Document> dispatchers = collection(db, "dispatchers");
    ObjectId staleRegistration = insertDispatcher(dispatchers, EARLIER);
    ObjectId latestRegistration = insertDispatcher(dispatchers, LATER);
    // An index an older tool built on the email keys, and one an operator added for themselves.
    collection(db, "users").createIndex(new Document("email", 1.0), new IndexOptions().name("email"));
    collection(db, "workflows").createIndex(new Document("owner", 1), new IndexOptions().name("operator_owner"));

    migrate("indexes");

    assertThat(taskRuns.find(Filters.eq("_id", finished)).first()).as("the finished run is kept").isNotNull();
    assertThat(taskRuns.find(Filters.eq("_id", stillRunning)).first()).isNull();
    assertThat(actions.find(Filters.eq("_id", firstGate)).first()).as("the earliest gate is kept").isNotNull();
    assertThat(actions.find(Filters.eq("_id", repeatGate)).first()).isNull();
    assertThat(actions.countDocuments(Filters.in("_id", nullRef, noRef, anotherNoRef)))
        .as("gates without a task run are kept")
        .isEqualTo(3);
    assertThat(actions.find(Filters.eq("_id", nullRef)).first().containsKey("taskRunRef")).isFalse();
    assertThat(dispatchers.find(Filters.eq("_id", latestRegistration)).first()).isNotNull();
    assertThat(dispatchers.find(Filters.eq("_id", staleRegistration)).first()).isNull();

    assertIndexInventory(db);
    assertThat(indexesOf(db, "users")).doesNotContainKey("email");
    assertThat(indexesOf(db, "workflows")).containsKey("operator_owner");
    assertThat(actions.find(Filters.eq("taskRunRef", "task-run-1")).explain().toJson())
        .as("a lookup by task run uses the partial index")
        .contains("\"indexName\": \"task_run\"");
  }

  // ===================================================================================
  // Seeded content and helpers
  // ===================================================================================

  /** The ai task: its params are its whole authoring surface, and the dispatcher picks its image. */
  private static void assertAiTaskSeeded(MongoDatabase db) {
    Document ai = collection(db, "tasks").find(Filters.eq("name", "ai")).first();
    assertThat(ai.getString("type")).isEqualTo("ai");
    Document revision =
        collection(db, "task_revisions")
            .find(Filters.and(Filters.eq("parentRef", ai.get("_id").toString()), Filters.eq("version", 1)))
            .first();
    assertThat(revision.getString("icon")).isEqualTo("AI");
    Document spec = revision.get("spec", Document.class);
    assertThat(spec.getString("image")).isEmpty();
    List<Document> params = spec.getList("params", Document.class);
    assertThat(params)
        .extracting(param -> param.getString("name"))
        .containsExactly(
            "endpoint", "token", "model", "systemPrompt", "prompt", "temperature", "maxTokens",
            "responseFormat", "jsonSchema", "seed", "files", "maxContextBytes");
    assertThat(params.get(1).getString("type")).as("the token is a secret").isEqualTo("password");
  }

  private static void assertArtifactTasksSeeded(MongoDatabase db) {
    for (String name : List.of("upload-artifact", "download-artifact")) {
      Document task = collection(db, "tasks").find(Filters.eq("name", name)).first();
      assertThat(task).as(name).isNotNull();
      Document revision =
          collection(db, "task_revisions").find(Filters.eq("parentRef", task.get("_id").toString())).first();
      Document path =
          revision.get("spec", Document.class).getList("params", Document.class).stream()
              .filter(param -> "path".equals(param.getString("name")))
              .findFirst()
              .orElseThrow();
      assertThat(path.getString("helpertext")).contains("Relative to /workspace");
    }
  }

  private static void assertIndexInventory(MongoDatabase db) {
    for (_0021__Indexes.Index index : _0021__Indexes.INVENTORY) {
      Document built = indexesOf(db, index.collection()).get(index.name());
      assertThat(built).as("%s.%s", index.collection(), index.name()).isNotNull();
      assertThat(Boolean.TRUE.equals(built.getBoolean("unique")))
          .as("%s.%s unique", index.collection(), index.name())
          .isEqualTo(index.options().isUnique());
    }
  }

  private static MongoDatabase migrate(String database) {
    assertThatCode(() -> MigrationRunner.run(uriOf(database), PREFIX)).doesNotThrowAnyException();
    return database(database);
  }

  /** Drop the change log so the next run executes every unit again. */
  private static void forgetHistory(MongoDatabase db) {
    collection(db, "sys_migration_changelog").drop();
  }

  private static void markV3(MongoDatabase db) {
    collection(db, "sys_changelog_flow").insertOne(new Document("changeId", "112"));
  }

  private static String uriOf(String database) {
    return MONGO.getReplicaSetUrl(database);
  }

  private static MongoDatabase database(String name) {
    return client.getDatabase(name);
  }

  private static MongoCollection<Document> collection(MongoDatabase db, String name) {
    return db.getCollection(PREFIX + "_" + name);
  }

  private static Map<String, Document> indexesOf(MongoDatabase db, String collection) {
    Map<String, Document> indexes = new HashMap<>();
    collection(db, collection).listIndexes().forEach(index -> indexes.put(index.getString("name"), index));
    return indexes;
  }

  private static String keyOf(MongoCollection<Document> settings, String id) {
    return settings.find(Filters.eq("_id", new ObjectId(id))).first().getString("key");
  }

  private static ObjectId insertUser(MongoCollection<Document> users, String email) {
    ObjectId id = new ObjectId();
    users.insertOne(new Document("_id", id).append("email", email).append("type", "user"));
    return id;
  }

  private static String emailOf(MongoCollection<Document> users, ObjectId id) {
    return users.find(Filters.eq("_id", id)).first().getString("email");
  }

  private static ObjectId insertTaskRun(
      MongoCollection<Document> taskRuns, String workflowRunRef, String name, String status, Date created) {
    ObjectId id = new ObjectId();
    taskRuns.insertOne(
        new Document("_id", id)
            .append("workflowRunRef", workflowRunRef)
            .append("name", name)
            .append("status", status)
            .append("creationDate", created));
    return id;
  }

  private static ObjectId insertAction(MongoCollection<Document> actions, String taskRunRef, Date created) {
    ObjectId id = new ObjectId();
    Document action = new Document("_id", id).append("creationDate", created);
    if (taskRunRef != null) {
      action.append("taskRunRef", taskRunRef);
    }
    actions.insertOne(action);
    return id;
  }

  private static ObjectId insertDispatcher(MongoCollection<Document> dispatchers, Date lastConnected) {
    ObjectId id = new ObjectId();
    dispatchers.insertOne(
        new Document("_id", id).append("name", "kube").append("host", "node-1").append("lastConnectedDate", lastConnected));
    return id;
  }
}

package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The chain against its two starting points, an empty database and a v3 install, and against the
 * databases it refuses. Each test runs the chain outside Spring on its own database.
 */
@Tag("smoke")
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
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(91);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(176);
    assertThat(collection(db, "workflow_templates").countDocuments()).isEqualTo(3);
    assertThat(collection(db, "integration_templates").countDocuments()).isEqualTo(2);
    // Every task is in the global catalogue: a task node the root reaches by hasTask.
    assertThat(collection(db, "rel_nodes").countDocuments(Filters.eq("type", "task"))).isEqualTo(91);
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "hasTask"))).isEqualTo(91);
    assertAiTaskFamilySeeded(db);
    assertArtifactTasksSeeded(db);
    assertIndexInventory(db);

    long nodes = collection(db, "rel_nodes").countDocuments();
    long edges = collection(db, "rel_edges").countDocuments();
    forgetHistory(db);
    migrate("empty");
    assertThat(collection(db, "roles").countDocuments()).isEqualTo(5);
    assertThat(collection(db, "settings").countDocuments()).isEqualTo(9);
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(91);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(176);
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
    // v3's settings documents, under the ids and keys the real v3 dump holds.
    for (String[] setting :
        new String[][] {
          {"5f32cb19d09662744c0df51d", "controller"},
          {"62a7bec0a6166d30aff64a5b", "extensions"},
          {"60245957226920beece4fdf9", "activity"},
          {"60245b56226920beece547e3", "workflow"},
          {"612904d60b07a54cdc4dc6a9", "features"},
          {"61393f5966c5eea103dfe134", "teams"},
          {"62b0f1f5a6166d30af05fa5d", "customizations"}
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

    // The catalogue: both v3 tasks migrated beside the 91 seeded ones; task_templates is gone.
    MongoCollection<Document> tasks = collection(db, "tasks");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    assertThat(tasks.countDocuments()).isEqualTo(93);
    assertThat(revisions.countDocuments()).isEqualTo(177);
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

    assertThat(collection(db, "workflow_templates").countDocuments()).isEqualTo(3);
    assertThat(collection(db, "integration_templates").countDocuments()).isEqualTo(2);
    assertThat(collection(db, "roles").countDocuments()).isEqualTo(5);
    assertThat(collection(db, "parameters").countDocuments()).isZero();
    assertIndexInventory(db);

    // Every unit again over the migrated database changes nothing.
    forgetHistory(db);
    migrate("v3");
    assertThat(settings.countDocuments()).isEqualTo(9);
    assertThat(tasks.countDocuments()).isEqualTo(93);
    assertThat(revisions.countDocuments()).isEqualTo(177);
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
   * v3 enforced a stored quota of 0 as a limit of zero; v5 reads a stored 0 as unlimited. The
   * migration drops a v3 0 so the workspace inherits the platform default, and copies every
   * positive value as it is.
   */
  @Test
  void aV3QuotaOfZeroIsDroppedSoTheWorkspaceInheritsTheDefault() {
    MongoDatabase db = database("quota-zero");
    markV3(db);
    Document team =
        new Document("_id", new ObjectId())
            .append("_class", "net.boomerangplatform.mongo.entity.TeamEntity")
            .append("name", "Locked Team")
            .append("isActive", true)
            .append(
                "quotas",
                new Document("maxWorkflowCount", 0)
                    .append("maxWorkflowExecutionMonthly", 50)
                    .append("maxWorkflowExecutionTime", 0)
                    .append("maxConcurrentWorkflows", 3));
    collection(db, "workspaces").insertOne(team);

    new _0007__V3MigrateWorkspaces().execute(db, MigrationRunner.environment(PREFIX));

    Document quotas =
        (Document)
            collection(db, "workspaces").find(Filters.eq("_id", team.get("_id"))).first().get("quotas");
    assertThat(quotas.getInteger("maxWorkflowCount")).isNull();
    assertThat(quotas.getInteger("maxWorkflowRunDuration")).isNull();
    assertThat(quotas.getInteger("maxWorkflowRunMonthly")).isEqualTo(50);
    assertThat(quotas.getInteger("maxConcurrentRuns")).isEqualTo(3);
  }

  /**
   * The seed matches catalogue tasks by name. A v3 catalogue task an install renamed is not
   * recognised: it is left as it is, and the seed adds its own copy beside it under a new id.
   */
  @Test
  void theCatalogueSeedAddsItsOwnCopyBesideARenamedV3Task() {
    MongoDatabase db = database("renamed-task");
    ObjectId v3Id = new ObjectId("600b2f5520a674b1d2cb4635");
    collection(db, "tasks")
        .insertOne(new Document("_id", v3Id).append("name", "our-lock").append("status", "active"));

    assertThatCode(
            () -> new _0017__SeedTaskCatalogue().execute(db, MigrationRunner.environment(PREFIX)))
        .doesNotThrowAnyException();

    MongoCollection<Document> tasks = collection(db, "tasks");
    assertThat(tasks.find(Filters.eq("_id", v3Id)).first().getString("name")).isEqualTo("our-lock");
    Document seeded = tasks.find(Filters.eq("name", "acquire-lock")).first();
    assertThat(seeded.getObjectId("_id")).isNotEqualTo(v3Id);
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    assertThat(revisions.countDocuments(Filters.eq("parentRef", seeded.get("_id").toString()))).isPositive();
    assertThat(revisions.countDocuments(Filters.eq("parentRef", v3Id.toString()))).isZero();
  }

  /**
   * A database seeded before ai-prompt and ai-agent joined the catalogue receives them from their
   * own unit, which the catalogue seed's name match never re-runs; a second run inserts nothing.
   */
  @Test
  void aDatabaseSeededBeforeTheAiTaskFamilyReceivesItFromItsOwnUnit() {
    MongoDatabase db = migrate("ai-family-upgrade");
    for (String name : _0023__SeedAiTaskFamily.TASKS) {
      Document task = collection(db, "tasks").find(Filters.eq("name", name)).first();
      String taskId = task.get("_id").toString();
      collection(db, "task_revisions").deleteMany(Filters.eq("parentRef", taskId));
      collection(db, "rel_edges").deleteMany(Filters.eq("to", "task:" + taskId));
      collection(db, "rel_nodes").deleteMany(Filters.eq("_id", "task:" + taskId));
      collection(db, "tasks").deleteOne(Filters.eq("_id", task.get("_id")));
    }
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(89);
    // The install's own ai task, from before the catalogue stopped shipping it, is never touched.
    ObjectId legacyAiId = new ObjectId();
    collection(db, "tasks")
        .insertOne(new Document("_id", legacyAiId).append("name", "ai").append("type", "ai"));
    collection(db, "task_revisions")
        .insertOne(new Document("parentRef", legacyAiId.toString()).append("version", 1));

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0023__SeedAiTaskFamily().execute(db, environment);
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(92);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(177);
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "hasTask"))).isEqualTo(91);
    assertAiTaskFamilySeeded(db);
    assertThat(collection(db, "tasks").find(Filters.eq("_id", legacyAiId)).first()).isNotNull();
    assertThat(collection(db, "task_revisions").countDocuments(Filters.eq("parentRef", legacyAiId.toString())))
        .isEqualTo(1);

    new _0023__SeedAiTaskFamily().execute(db, environment);
    assertThat(collection(db, "tasks").countDocuments()).isEqualTo(92);
    assertThat(collection(db, "task_revisions").countDocuments()).isEqualTo(177);
    assertThat(collection(db, "rel_edges").countDocuments(Filters.eq("label", "hasTask"))).isEqualTo(91);
  }

  /**
   * A database seeded before the AI example template joined the seed receives it from its own unit,
   * with its tasks pointing at live catalogue ids; a second run inserts nothing.
   */
  @Test
  void aDatabaseSeededBeforeTheAiWorkflowTemplateReceivesItFromItsOwnUnit() {
    MongoDatabase db = migrate("ai-template-upgrade");
    MongoCollection<Document> templates = collection(db, "workflow_templates");
    templates.deleteOne(Filters.eq("name", _0026__SeedAiWorkflowTemplate.TEMPLATE));
    assertThat(templates.countDocuments()).isEqualTo(2);

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0026__SeedAiWorkflowTemplate().execute(db, environment);
    assertThat(templates.countDocuments()).isEqualTo(3);
    Document template = templates.find(Filters.eq("name", _0026__SeedAiWorkflowTemplate.TEMPLATE)).first();
    String aiPromptId =
        collection(db, "tasks").find(Filters.eq("name", "ai-prompt")).first().get("_id").toString();
    assertThat(template.getList("tasks", Document.class))
        .anySatisfy(task -> assertThat(task.getString("taskRef")).isEqualTo(aiPromptId));

    new _0026__SeedAiWorkflowTemplate().execute(db, environment);
    assertThat(templates.countDocuments()).isEqualTo(3);
  }

  /**
   * A database that holds the AI tasks at revision 1 only receives revision 2 (image input) from
   * its own unit, leaves revision 1 as it was, adds nothing for a task the install does not hold,
   * and inserts nothing on a second run.
   */
  @Test
  void aDatabaseWithRevisionOneOfTheAiTasksReceivesRevisionTwoFromItsOwnUnit() {
    MongoDatabase db = migrate("ai-revision-two");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    Document prompt = collection(db, "tasks").find(Filters.eq("name", "ai-prompt")).first();
    Document agent = collection(db, "tasks").find(Filters.eq("name", "ai-agent")).first();
    for (Document task : List.of(prompt, agent)) {
      revisions.deleteOne(
          Filters.and(Filters.eq("parentRef", task.get("_id").toString()), Filters.eq("version", 2)));
    }
    // An install that dropped ai-agent: its revision 2 has no task to attach to.
    String agentId = agent.get("_id").toString();
    revisions.deleteMany(Filters.eq("parentRef", agentId));
    collection(db, "tasks").deleteOne(Filters.eq("_id", agent.get("_id")));
    Document promptRevisionOne =
        revisions
            .find(Filters.and(Filters.eq("parentRef", prompt.get("_id").toString()), Filters.eq("version", 1)))
            .first();
    assertThat(revisions.countDocuments()).isEqualTo(173);

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0024__SeedAiImageInputRevisions().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(174);
    Document promptRevisionTwo =
        revisions
            .find(Filters.and(Filters.eq("parentRef", prompt.get("_id").toString()), Filters.eq("version", 2)))
            .first();
    Document spec = promptRevisionTwo.get("spec", Document.class);
    assertThat(spec.getString("image")).isEqualTo("boomerangio/task-ai:2.1.0");
    assertThat(spec.getList("params", Document.class))
        .extracting(param -> param.getString("name"))
        .contains("images", "imageDetail", "maxImageBytes");
    assertThat(
            revisions
                .find(Filters.and(Filters.eq("parentRef", prompt.get("_id").toString()), Filters.eq("version", 1)))
                .first())
        .as("revision 1 is left as it was")
        .isEqualTo(promptRevisionOne);
    assertThat(revisions.countDocuments(Filters.eq("parentRef", agentId))).isZero();
    assertThat(collection(db, "tasks").find(Filters.eq("name", "ai-agent")).first()).isNull();

    new _0024__SeedAiImageInputRevisions().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(174);
  }

  /**
   * A database whose Slack tasks take their credential as plain text, holding revision 1 alone,
   * receives from its own unit the revision that makes it a password field and every later seed
   * revision, leaves revision 1 as it was, adds nothing for a task the install does not hold, and
   * inserts nothing on a second run.
   */
  @Test
  void aDatabaseWithPlainTextSlackCredentialsReceivesThePasswordRevisions() {
    MongoDatabase db = migrate("slack-credential-revisions");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    MongoCollection<Document> tasks = collection(db, "tasks");
    for (String name : _0030__SeedSlackCredentialRevisions.TASKS) {
      String taskId = tasks.find(Filters.eq("name", name)).first().get("_id").toString();
      revisions.deleteMany(Filters.and(Filters.eq("parentRef", taskId), Filters.gt("version", 1)));
    }
    // An install that dropped get-slack-channels: its revisions have no task to attach to.
    Document dropped = tasks.find(Filters.eq("name", "get-slack-channels")).first();
    revisions.deleteMany(Filters.eq("parentRef", dropped.get("_id").toString()));
    tasks.deleteOne(Filters.eq("_id", dropped.get("_id")));
    String channelTaskId = tasks.find(Filters.eq("name", "create-slack-channel")).first().get("_id").toString();
    Document channelRevisionOne =
        revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 1))).first();
    assertThat(revisions.countDocuments()).isEqualTo(150);

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0030__SeedSlackCredentialRevisions().execute(db, environment);
    // The password revision lands as 2 although the seed's newest is the later sharing revision.
    assertThat(revisions.countDocuments()).isEqualTo(173);
    Document channelRevisionTwo =
        revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 2))).first();
    assertThat(channelRevisionTwo.get("spec", Document.class).getList("params", Document.class))
        .filteredOn(param -> "token".equals(param.getString("name")))
        .extracting(param -> param.getString("type"), param -> param.getString("defaultValue"))
        .containsExactly(tuple("password", ""));
    String webhookTaskId =
        tasks.find(Filters.eq("name", "send-simple-slack-message")).first().get("_id").toString();
    Document webhookRevisionTwo =
        revisions.find(Filters.and(Filters.eq("parentRef", webhookTaskId), Filters.eq("version", 2))).first();
    assertThat(webhookRevisionTwo.get("spec", Document.class).getList("params", Document.class))
        .filteredOn(param -> "url".equals(param.getString("name")))
        .extracting(param -> param.getString("type"))
        .containsExactly("password");
    assertThat(
            revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 1))).first())
        .as("revision 1 is left as it was")
        .isEqualTo(channelRevisionOne);
    assertThat(tasks.find(Filters.eq("name", "get-slack-channels")).first()).isNull();

    new _0030__SeedSlackCredentialRevisions().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(173);
  }

  /**
   * A database from before Slack shared its bot token receives, from its own unit, the active Slack
   * template typed as its installations are, with its install link and parameter field, and each
   * Slack token task's revision whose token defaults to that parameter; earlier revisions stay as
   * they were, a task the install does not hold gets nothing, and a second run changes nothing.
   */
  @Test
  void aDatabaseWithoutSlackIntegrationParametersReceivesThemFromItsOwnUnit() {
    MongoDatabase db = migrate("slack-integration-parameters");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    MongoCollection<Document> tasks = collection(db, "tasks");
    MongoCollection<Document> templates = collection(db, "integration_templates");
    // The Slack template as it was seeded before it was a workspace integration.
    templates.updateOne(
        Filters.eq("type", "slack_auth"),
        new Document(
                "$set",
                new Document("type", "slack")
                    .append("status", "inactive")
                    .append("description", "Send a message to Slack")
                    .append("instructions", "")
                    .append("link", ""))
            .append("$unset", new Document("fields", "")));
    for (String name : _0032__SeedSlackIntegrationParameters.TASKS) {
      String taskId = tasks.find(Filters.eq("name", name)).first().get("_id").toString();
      Document newest =
          revisions.find(Filters.eq("parentRef", taskId)).sort(new Document("version", -1)).first();
      revisions.deleteOne(Filters.eq("_id", newest.get("_id")));
    }
    // An install that dropped get-slack-channels: its revisions have no task to attach to.
    Document dropped = tasks.find(Filters.eq("name", "get-slack-channels")).first();
    revisions.deleteMany(Filters.eq("parentRef", dropped.get("_id").toString()));
    tasks.deleteOne(Filters.eq("_id", dropped.get("_id")));
    String channelTaskId = tasks.find(Filters.eq("name", "create-slack-channel")).first().get("_id").toString();
    Document channelRevisionTwo =
        revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 2))).first();
    assertThat(revisions.countDocuments()).isEqualTo(164);

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0032__SeedSlackIntegrationParameters().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(173);
    assertThat(templates.countDocuments(Filters.eq("type", "slack"))).isZero();
    Document slack = templates.find(Filters.eq("type", "slack_auth")).first();
    assertThat(slack.getString("status")).isEqualTo("active");
    assertThat(slack.getString("link")).startsWith("https://slack.com/oauth/v2/authorize?client_id={client_id}");
    assertThat(slack.getList("fields", Document.class))
        .extracting(field -> field.getString("name"), field -> field.getString("type"))
        .containsExactly(tuple("slack-token", "password"));
    Document channelRevisionThree =
        revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 3))).first();
    assertThat(channelRevisionThree.get("spec", Document.class).getList("params", Document.class))
        .filteredOn(param -> "token".equals(param.getString("name")))
        .extracting(param -> param.getString("type"), param -> param.getString("defaultValue"))
        .containsExactly(tuple("password", "$(workspace.params.slack-token)"));
    assertThat(
            revisions.find(Filters.and(Filters.eq("parentRef", channelTaskId), Filters.eq("version", 2))).first())
        .as("revision 2 is left as it was")
        .isEqualTo(channelRevisionTwo);
    assertThat(tasks.find(Filters.eq("name", "get-slack-channels")).first()).isNull();

    new _0032__SeedSlackIntegrationParameters().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(173);
    assertThat(templates.countDocuments(Filters.eq("type", "slack_auth"))).isEqualTo(1);
  }

  /**
   * A database from before the GitHub integration shared its credentials receives, from its own
   * unit, the template's parameter fields and each GitHub task's revision whose token and URL default
   * to them; earlier revisions stay as they were, a task the install does not hold gets nothing, and
   * a second run changes nothing.
   */
  @Test
  void aDatabaseWithoutGitHubIntegrationParametersReceivesThemFromItsOwnUnit() {
    MongoDatabase db = migrate("github-integration-parameters");
    MongoCollection<Document> revisions = collection(db, "task_revisions");
    MongoCollection<Document> tasks = collection(db, "tasks");
    MongoCollection<Document> templates = collection(db, "integration_templates");
    templates.updateOne(Filters.eq("type", "github_app"), new Document("$unset", new Document("fields", "")));
    for (String name : _0031__SeedGitHubIntegrationParameters.TASKS) {
      String taskId = tasks.find(Filters.eq("name", name)).first().get("_id").toString();
      Document newest =
          revisions.find(Filters.eq("parentRef", taskId)).sort(new Document("version", -1)).first();
      revisions.deleteOne(Filters.eq("_id", newest.get("_id")));
    }
    // An install that dropped make-repositories-private: its revisions have no task to attach to.
    Document dropped = tasks.find(Filters.eq("name", "make-repositories-private")).first();
    revisions.deleteMany(Filters.eq("parentRef", dropped.get("_id").toString()));
    tasks.deleteOne(Filters.eq("_id", dropped.get("_id")));
    String repositoryTaskId =
        tasks.find(Filters.eq("name", "get-github-repository")).first().get("_id").toString();
    Document repositoryRevisionTwo =
        revisions.find(Filters.and(Filters.eq("parentRef", repositoryTaskId), Filters.eq("version", 2))).first();
    assertThat(revisions.countDocuments()).isEqualTo(158);

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0031__SeedGitHubIntegrationParameters().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(174);
    assertThat(templates.find(Filters.eq("type", "github_app")).first().getList("fields", Document.class))
        .extracting(field -> field.getString("name"), field -> field.getString("type"))
        .containsExactly(tuple("github-token", "password"), tuple("github-url", "text"));
    Document repositoryRevisionThree =
        revisions.find(Filters.and(Filters.eq("parentRef", repositoryTaskId), Filters.eq("version", 3))).first();
    assertThat(repositoryRevisionThree.get("spec", Document.class).getList("params", Document.class))
        .filteredOn(param -> List.of("token", "url").contains(param.getString("name")))
        .extracting(param -> param.getString("name"), param -> param.getString("defaultValue"))
        .containsExactlyInAnyOrder(
            tuple("token", "$(workspace.params.github-token)"),
            tuple("url", "$(workspace.params.github-url)"));
    assertThat(
            revisions.find(Filters.and(Filters.eq("parentRef", repositoryTaskId), Filters.eq("version", 2))).first())
        .as("revision 2 is left as it was")
        .isEqualTo(repositoryRevisionTwo);
    assertThat(tasks.find(Filters.eq("name", "make-repositories-private")).first()).isNull();

    new _0031__SeedGitHubIntegrationParameters().execute(db, environment);
    assertThat(revisions.countDocuments()).isEqualTo(174);
    assertThat(templates.find(Filters.eq("type", "github_app")).first().getList("fields", Document.class))
        .hasSize(2);
  }

  /**
   * A completed task run keeps no lease: the unit clears one left from before completion cleared
   * it, leaves a live claim's lease alone, and finds nothing on a second run.
   */
  @Test
  void completedTaskRunsLoseTheirLeaseAndLiveClaimsKeepIt() {
    MongoDatabase db = migrate("completed-task-leases");
    MongoCollection<Document> taskRuns = collection(db, "task_runs");
    java.util.Date lease = new java.util.Date();
    taskRuns.insertOne(
        new Document("_id", "done")
            .append("workflowRunRef", "run-1")
            .append("name", "done")
            .append("phase", "completed")
            .append("claim", new Document("by", "d1").append("leaseExpiresAt", lease)));
    taskRuns.insertOne(
        new Document("_id", "live")
            .append("workflowRunRef", "run-1")
            .append("name", "live")
            .append("phase", "running")
            .append("claim", new Document("by", "d1").append("leaseExpiresAt", lease)));

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0025__ClearCompletedTaskLeases().execute(db, environment);
    new _0025__ClearCompletedTaskLeases().execute(db, environment);

    Document doneClaim = taskRuns.find(Filters.eq("_id", "done")).first().get("claim", Document.class);
    assertThat(doneClaim).doesNotContainKey("leaseExpiresAt").containsEntry("by", "d1");
    Document liveClaim = taskRuns.find(Filters.eq("_id", "live")).first().get("claim", Document.class);
    assertThat(liveClaim).containsKey("leaseExpiresAt");
  }

  /**
   * A database that built the claim index before claim.by joined it has it re-keyed; the next run
   * finds the new keys and changes nothing.
   */
  @Test
  void anOldClaimIndexIsReKeyedWithTheClaimant() {
    MongoDatabase db = migrate("claim-index-keys");
    MongoCollection<Document> taskRuns = collection(db, "task_runs");
    taskRuns.dropIndex("claim_page");
    taskRuns.createIndex(
        new Document("type", 1).append("status", 1).append("phase", 1).append("creationDate", 1),
        new IndexOptions().name("claim_page"));

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0029__ClaimIndexKeysTheClaimant().execute(db, environment);
    new _0029__ClaimIndexKeysTheClaimant().execute(db, environment);

    Document claimPage =
        taskRuns.listIndexes().into(new ArrayList<>()).stream()
            .filter(index -> "claim_page".equals(index.getString("name")))
            .findFirst()
            .orElseThrow();
    assertThat(claimPage.get("key", Document.class).keySet())
        .containsExactly("type", "status", "phase", "claim.by", "creationDate");
  }

  /**
   * The engine's run annotations go - a user's stay - a pre-approved TaskRun keeps its status as
   * the typed field, and a held lock keyed by a workspace's name is re-keyed by its id; a second
   * run changes nothing.
   */
  @Test
  void runAnnotationsMoveOutAndLocksAreKeyedByWorkspaceId() {
    MongoDatabase db = migrate("run-settings-out-of-annotations");
    MongoCollection<Document> runs = collection(db, "workflow_runs");
    MongoCollection<Document> taskRuns = collection(db, "task_runs");
    MongoCollection<Document> locks = collection(db, "task_locks");
    ObjectId workspaceId = new ObjectId();
    collection(db, "workspaces").insertOne(new Document("_id", workspaceId).append("name", "payments"));
    runs.insertOne(
        new Document("_id", "run-1")
            .append(
                "annotations",
                new Document("boomerang#io/workspace-name", "payments")
                    .append("boomerang#io/task-timeout", "30")
                    .append("boomerang#io/task-default-image", "img")
                    .append("boomerang#io/task-deletion", "Never")
                    .append("boomerang#io/global-params", new Document())
                    .append("example#com/note", "kept")));
    taskRuns.insertOne(
        new Document("_id", "early-failed")
            .append("workflowRunRef", "run-1")
            .append("name", "early-failed")
            .append("preApproved", true)
            .append("annotations", new Document("boomerang#io/status", "failed")));
    taskRuns.insertOne(
        new Document("_id", "early-default")
            .append("workflowRunRef", "run-1")
            .append("name", "early-default")
            .append("preApproved", true));
    taskRuns.insertOne(
        new Document("_id", "plain")
            .append("workflowRunRef", "run-1")
            .append("name", "plain")
            .append("preApproved", false)
            .append(
                "annotations",
                new Document("boomerang#io/workspace-name", "payments")
                    .append("boomerang#io/position", new Document("x", 1))));
    locks.insertOne(new Document("_id", "payments:deploy").append("holder", "t1"));
    locks.insertOne(new Document("_id", "renamed-away:deploy").append("holder", "t2"));
    locks.insertOne(new Document("_id", "bare-key").append("holder", "t3"));

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0028__RunSettingsOutOfAnnotations().execute(db, environment);
    new _0028__RunSettingsOutOfAnnotations().execute(db, environment);

    assertThat(runs.find(Filters.eq("_id", "run-1")).first().get("annotations", Document.class))
        .containsOnlyKeys("example#com/note");
    Document failed = taskRuns.find(Filters.eq("_id", "early-failed")).first();
    assertThat(failed).containsEntry("preApprovedStatus", "failed").doesNotContainKey("preApproved");
    assertThat(failed.get("annotations", Document.class)).doesNotContainKey("boomerang#io/status");
    assertThat(taskRuns.find(Filters.eq("_id", "early-default")).first())
        .containsEntry("preApprovedStatus", "succeeded");
    Document plain = taskRuns.find(Filters.eq("_id", "plain")).first();
    assertThat(plain).doesNotContainKey("preApproved").doesNotContainKey("preApprovedStatus");
    assertThat(plain.get("annotations", Document.class)).containsOnlyKeys("boomerang#io/position");
    assertThat(locks.find().map(lock -> lock.getString("_id")).into(new ArrayList<>()))
        .containsExactlyInAnyOrder(
            workspaceId.toHexString() + ":deploy", "renamed-away:deploy", "bare-key");
    assertThat(locks.find(Filters.eq("_id", workspaceId.toHexString() + ":deploy")).first())
        .containsEntry("holder", "t1");
  }

  /**
   * A workspace's own task node written as {@code teamtask} moves to {@code workspacetask} under its
   * new id, with its fields kept and its edge re-pointed; other nodes and edges are untouched, and a
   * second run changes nothing.
   */
  @Test
  void aTeamTaskNodeIsRenamedWithItsEdges() {
    MongoDatabase db = migrate("workspace-task-nodes");
    MongoCollection<Document> nodes = collection(db, "rel_nodes");
    MongoCollection<Document> edges = collection(db, "rel_edges");
    nodes.insertOne(
        new Document("_id", "teamtask:t1")
            .append("type", "teamtask")
            .append("ref", "t1")
            .append("slug", "my-task")
            .append("data", new Document("k", "v")));
    edges.insertOne(
        new Document("from", "workspace:w1").append("label", "hasTask").append("to", "teamtask:t1"));
    long otherNodes = nodes.countDocuments(Filters.ne("type", "teamtask"));
    long otherEdges = edges.countDocuments(Filters.ne("to", "teamtask:t1"));

    Environment environment = MigrationRunner.environment(PREFIX);
    new _0027__RenameWorkspaceTaskNodes().execute(db, environment);
    new _0027__RenameWorkspaceTaskNodes().execute(db, environment);

    assertThat(nodes.countDocuments(Filters.eq("type", "teamtask"))).isZero();
    Document node = nodes.find(Filters.eq("_id", "workspacetask:t1")).first();
    assertThat(node).isNotNull().containsEntry("type", "workspacetask").containsEntry("slug", "my-task");
    assertThat(node.get("data", Document.class)).containsEntry("k", "v");
    assertThat(edges.countDocuments(Filters.regex("to", "^teamtask:"))).isZero();
    assertThat(
            edges.countDocuments(
                Filters.and(Filters.eq("from", "workspace:w1"), Filters.eq("to", "workspacetask:t1"))))
        .isEqualTo(1);
    assertThat(nodes.countDocuments(Filters.ne("type", "workspacetask"))).isEqualTo(otherNodes);
    assertThat(edges.countDocuments(Filters.ne("to", "workspacetask:t1"))).isEqualTo(otherEdges);
  }

  /**
   * The seed carries no ids: every seeded document takes one from MongoDB, and a revision or a
   * template task points at its catalogue task by that task's live id.
   */
  @Test
  void seedsCarryNoIdsAndJoinByName() {
    MongoDatabase db = migrate("natural-keys");
    MongoCollection<Document> tasks = collection(db, "tasks");
    Document shell = tasks.find(Filters.eq("name", "execute-shell")).first();
    assertThat(shell.getObjectId("_id")).isNotEqualTo(new ObjectId("5c3907a1352b1b51412ed079"));
    assertThat(collection(db, "task_revisions").countDocuments(Filters.eq("parentRef", shell.get("_id").toString())))
        .isPositive();
    for (Document revision : collection(db, "task_revisions").find()) {
      assertThat(tasks.find(Filters.eq("_id", new ObjectId(revision.getString("parentRef")))).first())
          .as("revision %s v%s points at a live task", revision.getString("displayName"), revision.get("version"))
          .isNotNull();
    }
    for (Document template : collection(db, "workflow_templates").find()) {
      for (Document task : template.getList("tasks", Document.class)) {
        if (task.getString("taskRef") != null) {
          assertThat(tasks.find(Filters.eq("_id", new ObjectId(task.getString("taskRef")))).first())
              .as("template %s task %s points at a live task", template.getString("name"), task.getString("name"))
              .isNotNull();
        }
      }
    }
    Document system =
        collection(db, "workspaces").find(Filters.eq("name", "system")).first();
    assertThat(system.getObjectId("_id")).isNotEqualTo(new ObjectId("5eed00000000000000000001"));
    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", "workspace:" + system.get("_id"))).first())
        .isNotNull();
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

  /**
   * The ai-prompt and ai-agent tasks, copied from the tasks repository's definition files: each
   * revision names its own image and command, which the dispatcher runs as declared. Revision 2
   * adds image input on task-ai 2.1.0; revision 1 stays on 2.0.0 for workflows pinned to it.
   */
  private static void assertAiTaskFamilySeeded(MongoDatabase db) {
    assertAiFamilyTask(db, "ai-prompt", 1, "boomerangio/task-ai:2.0.0", "prompt", 17, 9);
    assertAiFamilyTask(db, "ai-prompt", 2, "boomerangio/task-ai:2.1.0", "prompt", 20, 9);
    assertAiFamilyTask(db, "ai-agent", 1, "boomerangio/task-ai:2.0.0", "agent", 26, 11);
    assertAiFamilyTask(db, "ai-agent", 2, "boomerangio/task-ai:2.1.0", "agent", 29, 11);
  }

  private static void assertAiFamilyTask(
      MongoDatabase db, String name, int version, String image, String command, int params, int results) {
    Document task = collection(db, "tasks").find(Filters.eq("name", name)).first();
    assertThat(task).as("%s is seeded", name).isNotNull();
    assertThat(task.getString("type")).isEqualTo("ai");
    Document revision =
        collection(db, "task_revisions")
            .find(Filters.and(Filters.eq("parentRef", task.get("_id").toString()), Filters.eq("version", version)))
            .first();
    assertThat(revision).as("%s version %s is seeded", name, version).isNotNull();
    Document spec = revision.get("spec", Document.class);
    assertThat(spec.getString("image")).isEqualTo(image);
    assertThat(spec.getList("command", String.class)).containsExactly(command);
    assertThat(spec.getList("params", Document.class)).hasSize(params);
    assertThat(spec.getList("results", Document.class)).hasSize(results);
    assertThat(
            spec.getList("params", Document.class).stream()
                .filter(param -> "token".equals(param.getString("name")))
                .map(param -> param.getString("type")))
        .as("the token is a secret")
        .containsExactly("password");
    assertThat(collection(db, "rel_nodes").find(Filters.eq("_id", "task:" + task.get("_id"))).first())
        .as("%s is reachable from the root", name)
        .isNotNull();
  }

  private static void assertArtifactTasksSeeded(MongoDatabase db) {
    for (String name : List.of("upload-artifact", "download-artifact")) {
      Document task = collection(db, "tasks").find(Filters.eq("name", name)).first();
      assertThat(task).as(name).isNotNull();
      Document revision =
          collection(db, "task_revisions").find(Filters.eq("parentRef", task.get("_id").toString())).first();
      Document spec = revision.get("spec", Document.class);
      List<Document> params = spec.getList("params", Document.class);
      assertThat(seededParam(params, "path").getString("helpertext"))
          .contains("Relative to /workspace");
      // Both tasks take a scope: a select with a run default.
      Document scope = seededParam(params, "scope");
      assertThat(scope.getString("type")).as("%s scope type", name).isEqualTo("select");
      assertThat(scope.get("defaultValue")).as("%s scope default", name).isEqualTo("run");
      assertThat(scope.getList("options", Document.class))
          .extracting(option -> option.getString("key"), option -> option.getString("value"))
          .containsExactly(tuple("run", "run"), tuple("workflow", "workflow"));
      if ("download-artifact".equals(name)) {
        Document optional = seededParam(params, "optional");
        assertThat(optional.getString("type")).isEqualTo("boolean");
        assertThat(optional.get("defaultValue")).isEqualTo("false");
        assertThat(spec.getList("results", Document.class))
            .extracting(result -> result.getString("name"))
            .containsExactly("found");
      } else {
        assertThat(params).extracting(param -> param.getString("name")).doesNotContain("optional");
        assertThat(spec.getList("results", Document.class)).isEmpty();
      }
    }
  }

  private static Document seededParam(List<Document> params, String name) {
    return params.stream()
        .filter(param -> name.equals(param.getString("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no seeded param " + name));
  }

  private static void assertIndexInventory(MongoDatabase db) {
    for (_0021__Indexes.Index index : _0021__Indexes.INVENTORY) {
      Document built = indexesOf(db, index.collection()).get(index.name());
      assertThat(built).as("%s.%s", index.collection(), index.name()).isNotNull();
      assertThat(Boolean.TRUE.equals(built.getBoolean("unique")))
          .as("%s.%s unique", index.collection(), index.name())
          .isEqualTo(index.options().isUnique());
      // A partial index is built with its filter, and a plain one without.
      assertThat(built.get("partialFilterExpression"))
          .as("%s.%s partial filter", index.collection(), index.name())
          .isEqualTo(index.options().getPartialFilterExpression());
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

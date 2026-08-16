package io.boomerang.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the loader main flow against a Testcontainers MongoDB seeded like an existing
 * installation (legacy loader changelog, duplicate task_runs/actions/agents) and asserts the
 * migrated schema: expected indexes, correct dedupe outcomes, and a clean no-op second run.
 */
class LoaderMigrationTest {

  private static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  private static final String PREFIX = "flowtest";
  private static final Date EARLIER = Date.from(Instant.parse("2026-01-01T00:00:00Z"));
  private static final Date LATER = Date.from(Instant.parse("2026-01-02T00:00:00Z"));

  private static MongoClient client;
  private static MongoDatabase db;

  private static ObjectId taskARunning;
  private static ObjectId taskASucceeded;
  private static ObjectId taskBFirst;
  private static ObjectId taskBSecond;
  private static ObjectId soloTask;
  private static ObjectId earliestGate;
  private static ObjectId latestConnectedAgent;
  private static ObjectId taskRunWithAgentRef;
  private static ObjectId workflowRunWithAgentRef;
  private static ObjectId tokenWithTeamScope;
  private static ObjectId roleWithTeamType;
  private static ObjectId adminUser;
  private static ObjectId regularUser;

  @BeforeAll
  static void seedExistingInstallation() {
    MONGO.start();
    client = MongoClients.create(MONGO.getReplicaSetUrl("boomerang"));
    db = client.getDatabase("boomerang");

    collection("sys_changelog_flow").insertOne(new Document("changeId", "001"));
    collection("workflows").insertOne(new Document("name", "wf"));

    seedTeamRelationshipGraph();
    tokenWithTeamScope = insertToken("team");
    roleWithTeamType = insertRole("team");
    adminUser = insertUser("admin@example.com", "admin");
    regularUser = insertUser("user@example.com", "user");

    taskARunning = insertTaskRun("wfr1", "task-a", "running", EARLIER);
    taskASucceeded = insertTaskRun("wfr1", "task-a", "succeeded", LATER);
    taskBFirst = insertTaskRun("wfr1", "task-b", "running", EARLIER);
    taskBSecond = insertTaskRun("wfr1", "task-b", "running", LATER);
    soloTask = insertTaskRun("wfr2", "task-a", "succeeded", EARLIER);

    earliestGate = insertAction("tr1", EARLIER);
    insertAction("tr1", LATER);
    insertAction("tr2", EARLIER);

    insertAgent("agent-1", "host-a", EARLIER);
    latestConnectedAgent = insertAgent("agent-1", "host-a", LATER);
    insertAgent("agent-2", "host-a", EARLIER);

    taskRunWithAgentRef = insertTaskRunWithAgentRef("wfr-claimed", "agent-1", EARLIER);
    workflowRunWithAgentRef = insertWorkflowRunWithAgentRef("agent-1", EARLIER);
  }

  @AfterAll
  static void closeClient() {
    if (client != null) {
      client.close();
    }
  }

  @Test
  void migratesSeededDatabaseAndSecondRunIsANoOp() {
    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl("boomerang"), PREFIX))
        .doesNotThrowAnyException();

    assertTaskRunIndexes();
    assertWorkflowRunIndexes();
    assertUniqueIndex("actions", "task_run", List.of("taskRunRef"));
    assertUniqueIndex("dispatchers", "registration", List.of("name", "host"));
    assertEventCollectionIndexes();
    assertLockAndWorkflowIndexes();
    assertTaskRunDedupe();
    assertActionDedupe();
    assertAgentDedupe();
    assertAgentsCollectionRenamed();
    assertDispatcherRefRenamed();
    assertWorkspaceRenameApplied();
    assertRootNodeSeeded();
    assertSystemWorkspaceSeeded();
    assertRolesSeeded();
    assertSettingsSeeded();
    assertTaskCatalogueSeeded();
    assertTemplatesSeeded();
    assertThat(collection("sys_changelog_loader").countDocuments()).isGreaterThanOrEqualTo(18);

    List<Document> taskRunsBefore = snapshot("task_runs");
    List<Document> workflowRunsBefore = snapshot("workflow_runs");
    List<Document> actionsBefore = snapshot("actions");
    List<Document> dispatchersBefore = snapshot("dispatchers");
    List<Document> relNodesBefore = snapshotByStringId("rel_nodes");
    List<Document> relEdgesBefore = snapshot("rel_edges");
    List<Document> tokensBefore = snapshot("tokens");
    List<Document> rolesBefore = snapshot("roles");
    List<Document> settingsBefore = snapshot("settings");
    List<Document> tasksBefore = snapshot("tasks");
    List<Document> taskRevisionsBefore = snapshot("task_revisions");
    List<Document> workspacesBefore = snapshot("teams");
    List<Document> workflowTemplatesBefore = snapshot("workflow_templates");
    List<Document> integrationTemplatesBefore = snapshot("integration_templates");

    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl("boomerang"), PREFIX))
        .doesNotThrowAnyException();

    assertThat(snapshot("task_runs")).isEqualTo(taskRunsBefore);
    assertThat(snapshot("workflow_runs")).isEqualTo(workflowRunsBefore);
    assertThat(snapshot("actions")).isEqualTo(actionsBefore);
    assertThat(snapshot("dispatchers")).isEqualTo(dispatchersBefore);
    assertThat(snapshotByStringId("rel_nodes")).isEqualTo(relNodesBefore);
    assertThat(snapshot("rel_edges")).isEqualTo(relEdgesBefore);
    assertThat(snapshot("tokens")).isEqualTo(tokensBefore);
    assertThat(snapshot("roles")).isEqualTo(rolesBefore);
    // The seed change units are insert-if-absent, so a second run adds nothing anywhere.
    assertThat(snapshot("settings")).isEqualTo(settingsBefore);
    assertThat(snapshot("tasks")).isEqualTo(tasksBefore);
    assertThat(snapshot("task_revisions")).isEqualTo(taskRevisionsBefore);
    assertThat(snapshot("teams")).isEqualTo(workspacesBefore);
    assertThat(snapshot("workflow_templates")).isEqualTo(workflowTemplatesBefore);
    assertThat(snapshot("integration_templates")).isEqualTo(integrationTemplatesBefore);

    // The run above was skipped wholesale by Flamingock's audit log, so it proves the pipeline is
    // re-entrant but not that the change units themselves are. Drop the audit log and run again:
    // every unit re-executes against the already-migrated, already-seeded database, which is the
    // module's stated contract ("every subsequent change unit is idempotent regardless of prior
    // state") and the guarantee the seed units' insert-if-absent guards exist to provide.
    collection("sys_changelog_loader").drop();
    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl("boomerang"), PREFIX))
        .doesNotThrowAnyException();

    assertThat(snapshotByStringId("rel_nodes")).isEqualTo(relNodesBefore);
    assertThat(snapshot("rel_edges")).isEqualTo(relEdgesBefore);
    assertThat(snapshot("roles")).isEqualTo(rolesBefore);
    assertThat(snapshot("settings")).isEqualTo(settingsBefore);
    assertThat(snapshot("tasks")).isEqualTo(tasksBefore);
    assertThat(snapshot("task_revisions")).isEqualTo(taskRevisionsBefore);
    assertThat(snapshot("teams")).isEqualTo(workspacesBefore);
    assertThat(snapshot("workflow_templates")).isEqualTo(workflowTemplatesBefore);
    assertThat(snapshot("integration_templates")).isEqualTo(integrationTemplatesBefore);
  }

  private void assertRootNodeSeeded() {
    Document root = collection("rel_nodes").find(Filters.eq("_id", "root:root")).first();
    assertThat(root).isNotNull();
    assertThat(root.getString("type")).isEqualTo("root");
    assertThat(root.getString("ref")).isEqualTo("root");
    assertThat(root.getString("slug")).isEqualTo("root");
    assertThat(root.get("data", Document.class)).isEmpty();
    assertThat(root.getDate("creationDate")).isNotNull();
  }

  private void assertSystemWorkspaceSeeded() {
    Document workspace = collection("teams").find(Filters.eq("name", "system")).first();
    assertThat(workspace).isNotNull();
    assertThat(workspace.getString("displayName")).isEqualTo("System and Administration");
    assertThat(workspace.getString("type")).isEqualTo("system");
    assertThat(workspace.getString("status")).isEqualTo("active");
    assertThat(workspace.get("quotas", Document.class))
        .containsOnlyKeys(
            "maxWorkflowCount",
            "maxWorkflowRunMonthly",
            "maxWorkflowStorage",
            "maxWorkflowRunStorage",
            "maxWorkflowRunDuration",
            "maxConcurrentRuns");
    assertThat(workspace.get("quotas", Document.class).getInteger("maxWorkflowCount"))
        .isEqualTo(Integer.MAX_VALUE);

    // The graph WorkspaceService.create would have written: a workspace node slugged by name,
    // reachable from the root.
    String workspaceId = workspace.get("_id").toString();
    Document node = collection("rel_nodes").find(Filters.eq("_id", "workspace:" + workspaceId)).first();
    assertThat(node).isNotNull();
    assertThat(node.getString("type")).isEqualTo("workspace");
    assertThat(node.getString("ref")).isEqualTo(workspaceId);
    assertThat(node.getString("slug")).isEqualTo("system");
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "root:root"),
                        Filters.eq("label", "contains"),
                        Filters.eq("to", "workspace:" + workspaceId))))
        .isEqualTo(1);

    // The pre-seeded admin user joins it; the non-admin does not.
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "user:" + adminUser.toString()),
                        Filters.eq("label", "memberOf"),
                        Filters.eq("to", "workspace:" + workspaceId))))
        .isEqualTo(1);
    Document membership =
        collection("rel_edges")
            .find(
                Filters.and(
                    Filters.eq("from", "user:" + adminUser.toString()),
                    Filters.eq("to", "workspace:" + workspaceId)))
            .first();
    assertThat(membership.get("data", Document.class).getString("role")).isEqualTo("owner");
    assertThat(
            collection("rel_edges")
                .countDocuments(Filters.eq("from", "user:" + regularUser.toString())))
        .isZero();
  }

  private void assertRolesSeeded() {
    // The pre-existing "workspace/owner" role (migrated from type "team" by _0016) is matched by
    // the natural key and left alone rather than duplicated.
    assertThat(collection("roles").countDocuments()).isEqualTo(5);
    assertThat(collection("roles").countDocuments(Filters.eq("name", "owner"))).isEqualTo(1);
    assertThat(collection("roles").find(Filters.eq("_id", roleWithTeamType)).first())
        .isNotNull();

    Document reader =
        collection("roles")
            .find(Filters.and(Filters.eq("type", "workspace"), Filters.eq("name", "reader")))
            .first();
    assertThat(reader).isNotNull();
    assertThat(reader.getList("permissions", String.class)).containsExactly("**/read");

    Document admin =
        collection("roles")
            .find(Filters.and(Filters.eq("type", "global"), Filters.eq("name", "admin")))
            .first();
    assertThat(admin).isNotNull();
    assertThat(admin.getList("permissions", String.class)).containsExactly("**/**");
    // No role kept the pre-DD-01 scope value.
    assertThat(collection("roles").countDocuments(Filters.eq("type", "team"))).isZero();
  }

  private void assertSettingsSeeded() {
    assertThat(collection("settings").countDocuments()).isEqualTo(7);
    List<String> keys =
        collection("settings").distinct("key", String.class).into(new ArrayList<>());
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "customizations", "features", "integration", "task", "teams", "workflow", "workflowrun");

    // The quota defaults WorkspaceService.setDefaultQuotas resolves at read time.
    Document quotas = collection("settings").find(Filters.eq("key", "teams")).first();
    assertThat(
            quotas.getList("config", Document.class).stream()
                .map(config -> config.getString("key"))
                .toList())
        .contains(
            "max.workflow.count",
            "max.workflow.storage",
            "max.workflowrun.concurrent",
            "max.workflowrun.monthly",
            "max.workflowrun.duration",
            "max.workflowrun.storage");
  }

  private void assertTaskCatalogueSeeded() {
    assertThat(collection("tasks").countDocuments()).isEqualTo(87);
    assertThat(collection("task_revisions").countDocuments()).isEqualTo(130);

    Document sleep = collection("tasks").find(Filters.eq("name", "sleep")).first();
    assertThat(sleep).isNotNull();
    assertThat(sleep.getString("type")).isEqualTo("sleep");
    assertThat(sleep.getString("status")).isEqualTo("active");
    assertThat(sleep.getBoolean("verified")).isTrue();
    // Map keys keep the legacy "#"-for-"." escaping MongoConfiguration still applies.
    assertThat(sleep.get("annotations", Document.class).getString("boomerang#io/kind"))
        .isEqualTo("Task");

    // Every revision points at a real task, and the split carries the versioned fields.
    String sleepId = sleep.get("_id").toString();
    Document revision =
        collection("task_revisions").find(Filters.eq("parentRef", sleepId)).first();
    assertThat(revision).isNotNull();
    assertThat(revision.getString("displayName")).isEqualTo("Sleep");
    assertThat(revision.getInteger("version")).isEqualTo(1);
    // Legacy changeset 4043 folded the v4 config[] into spec.params[]; the UI metadata rides on
    // the param, which is TaskRevisionEntity.spec.params (AbstractParam).
    Document duration =
        revision.get("spec", Document.class).getList("params", Document.class).get(0);
    assertThat(duration.getString("name")).isEqualTo("duration");
    assertThat(duration.getString("label")).isEqualTo("Duration");

    // Global catalogue graph: every task is a task: node reachable from root by hasTask.
    assertThat(collection("rel_nodes").countDocuments(Filters.eq("type", "task"))).isEqualTo(87);
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(Filters.eq("from", "root:root"), Filters.eq("label", "hasTask"))))
        .isEqualTo(87);
    Document sleepNode =
        collection("rel_nodes").find(Filters.eq("_id", "task:" + sleepId)).first();
    assertThat(sleepNode).isNotNull();
    assertThat(sleepNode.getString("slug")).isEqualTo("sleep");
    assertThat(sleepNode.getString("ref")).isEqualTo(sleepId);
  }

  private void assertTemplatesSeeded() {
    assertThat(collection("workflow_templates").countDocuments()).isEqualTo(2);
    assertThat(
            collection("workflow_templates")
                .countDocuments(Filters.eq("name", "mongodb-email-query-results")))
        .isEqualTo(1);

    assertThat(collection("integration_templates").countDocuments()).isEqualTo(2);
    Document github =
        collection("integration_templates").find(Filters.eq("name", "GitHub")).first();
    assertThat(github).isNotNull();
    assertThat(github.getString("type")).isEqualTo("github_app");
    assertThat(github.getString("status")).isEqualTo("active");
  }

  /**
   * The fresh-install path: an empty database, no legacy loader history. Everything the running
   * services need to bootstrap has to come out of the seed change units, and nothing in the
   * application creates the root node or the system workspace.
   */
  @Test
  void seedsAnEmptyDatabase() {
    String uri = MONGO.getReplicaSetUrl("freshinstall");
    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();

    MongoDatabase fresh = client.getDatabase("freshinstall");
    assertThat(fresh.getCollection(PREFIX + "_rel_nodes").find(Filters.eq("_id", "root:root")).first())
        .isNotNull();

    Document workspace =
        fresh.getCollection(PREFIX + "_teams").find(Filters.eq("name", "system")).first();
    assertThat(workspace).isNotNull();
    assertThat(workspace.getString("type")).isEqualTo("system");
    String workspaceNode = "workspace:" + workspace.get("_id");
    assertThat(fresh.getCollection(PREFIX + "_rel_nodes").find(Filters.eq("_id", workspaceNode)).first())
        .isNotNull();
    assertThat(
            fresh.getCollection(PREFIX + "_rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "root:root"), Filters.eq("to", workspaceNode))))
        .isEqualTo(1);
    // No users on a fresh install, so no membership edges - the admin bootstrap is a no-op here.
    assertThat(fresh.getCollection(PREFIX + "_rel_edges").countDocuments(Filters.eq("label", "memberOf")))
        .isZero();

    assertThat(fresh.getCollection(PREFIX + "_roles").countDocuments()).isEqualTo(5);
    assertThat(fresh.getCollection(PREFIX + "_settings").countDocuments()).isEqualTo(7);
    assertThat(fresh.getCollection(PREFIX + "_tasks").countDocuments()).isEqualTo(87);
    assertThat(fresh.getCollection(PREFIX + "_task_revisions").countDocuments()).isEqualTo(130);
    assertThat(fresh.getCollection(PREFIX + "_workflow_templates").countDocuments()).isEqualTo(2);
    assertThat(fresh.getCollection(PREFIX + "_integration_templates").countDocuments()).isEqualTo(2);
    // Every task is in the global catalogue: a task: node reachable from root by hasTask.
    assertThat(fresh.getCollection(PREFIX + "_rel_nodes").countDocuments(Filters.eq("type", "task")))
        .isEqualTo(87);
    assertThat(fresh.getCollection(PREFIX + "_rel_edges").countDocuments(Filters.eq("label", "hasTask")))
        .isEqualTo(87);

    // Re-running the change units against the seeded database inserts nothing.
    fresh.getCollection(PREFIX + "_sys_changelog_loader").drop();
    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();
    assertThat(fresh.getCollection(PREFIX + "_roles").countDocuments()).isEqualTo(5);
    assertThat(fresh.getCollection(PREFIX + "_settings").countDocuments()).isEqualTo(7);
    assertThat(fresh.getCollection(PREFIX + "_tasks").countDocuments()).isEqualTo(87);
    assertThat(fresh.getCollection(PREFIX + "_task_revisions").countDocuments()).isEqualTo(130);
    assertThat(fresh.getCollection(PREFIX + "_teams").countDocuments()).isEqualTo(1);
    assertThat(fresh.getCollection(PREFIX + "_rel_nodes").countDocuments()).isEqualTo(89);
    assertThat(fresh.getCollection(PREFIX + "_rel_edges").countDocuments()).isEqualTo(88);
  }

  /**
   * The v3-shaped fixture: a legacy loader changelog carrying changeset {@code "112"} (the v3
   * chain) but not {@code "4000"} (the v4 chain), the seven legacy settings {@code _id}s already
   * present under their v3-era {@code key} values, and a non-empty {@code task_templates}
   * collection (the v3 catalogue location; {@code tasks} is empty, matching a real v3 database).
   * Before {@link InstallGeneration} existed, the settings seed threw {@code
   * DuplicateKeyException} on exactly this shape and aborted the whole migration run. This
   * asserts the run now completes; that {@code _0005__V3MigrateSettings} (Phase 2) DOES migrate
   * the three real-keyed settings documents (controller/extensions/activity) in place - the other
   * four carry fictional placeholder v3 keys ("execution"/"toggles"/"quota"/"branding") this
   * fixture never claimed matched any real v3 install, so {@code _0005} leaves their {@code key}
   * field alone (it only renames the top-level key for the three documents whose v3 key actually
   * changes: controller/activity/extensions); that {@code _0021__SeedSettings}/{@code
   * _0022__SeedTaskCatalogue} (Phase 5, ungated) insert NOTHING new over this v3-migrated data
   * (their insert-if-absent guards match the already-migrated documents); and that {@code
   * _0023__SeedTemplates} (Phase 5, still v3-gated) skips itself outright.
   */
  @Test
  void v3InstallSkipsGenerationBlindSeedsAndMigratesCleanly() {
    String uri = MONGO.getReplicaSetUrl("v3install");
    MongoDatabase v3 = client.getDatabase("v3install");

    v3.getCollection(PREFIX + "_sys_changelog_flow").insertOne(new Document("changeId", "112"));

    // The seven legacy settings ids, each already present under its v3-era key - a different key
    // than the v5 seed uses for the same _id.
    insertV3Setting(v3, "5f32cb19d09662744c0df51d", "controller");
    insertV3Setting(v3, "62a7bec0a6166d30aff64a5b", "extensions");
    insertV3Setting(v3, "60245957226920beece4fdf9", "activity");
    insertV3Setting(v3, "60245b56226920beece547e3", "execution");
    insertV3Setting(v3, "612904d60b07a54cdc4dc6a9", "toggles");
    insertV3Setting(v3, "61393f5966c5eea103dfe134", "quota");
    insertV3Setting(v3, "62b0f1f5a6166d30af05fa5d", "branding");

    // v3's task catalogue location - empty "tasks" (v5 shape), non-empty "task_templates" (the
    // pre-v4-split collection the seed _ids would otherwise collide with). One minimal legacy
    // doc missing every optional field (exercises _0006's null-handling), one fuller doc with a
    // revision (exercises the config->params merge and provides a real name for the task_runs
    // fixture below).
    ObjectId minimalTaskId = new ObjectId();
    v3.getCollection(PREFIX + "_task_templates")
        .insertOne(new Document("_id", minimalTaskId).append("name", "legacy-task"));
    ObjectId customTaskId = new ObjectId();
    v3.getCollection(PREFIX + "_task_templates")
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
                                    .append("userName", "Tyson Lawrie")
                                    .append("reason", "")
                                    .append("date", EARLIER)))));

    // v3's task_runs referencing that task by its (already-slugified, per legacy convention)
    // name and the OLD templateVersion key - exercises _0006's rename + its 4033-bug fix.
    ObjectId taskRunId = new ObjectId();
    v3.getCollection(PREFIX + "_task_runs")
        .insertOne(
            new Document("_id", taskRunId)
                .append("name", "step-1")
                .append("templateRef", "custom-task-example")
                .append("templateVersion", 1));

    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();

    // _0005__V3MigrateSettings (Phase 2) DID migrate the 7 documents in place - same count, but
    // the three real-keyed ones now carry their v5 keys. _0021__SeedSettings (Phase 5, ungated)
    // then inserts nothing new: its OR-guard matches every one of the 7 already-migrated
    // documents by _id.
    MongoCollection<Document> settings = v3.getCollection(PREFIX + "_settings");
    assertThat(settings.countDocuments()).isEqualTo(7);
    assertThat(
            settings
                .find(Filters.eq("_id", new ObjectId("5f32cb19d09662744c0df51d")))
                .first()
                .getString("key"))
        .isEqualTo("task");
    assertThat(
            settings
                .find(Filters.eq("_id", new ObjectId("62a7bec0a6166d30aff64a5b")))
                .first()
                .getString("key"))
        .isEqualTo("integration");
    assertThat(
            settings
                .find(Filters.eq("_id", new ObjectId("60245957226920beece4fdf9")))
                .first()
                .getString("key"))
        .isEqualTo("workflowrun");

    // _0006__V3MigrateTaskCatalogue (Phase 2) migrated the 2 fixture docs (2 tasks, 1 revision -
    // the minimal doc has none) directly and dropped task_templates. _0022__SeedTaskCatalogue
    // (Phase 5, ungated) then reconciles the 87-task/130-revision seed catalogue on top by NAME:
    // neither fixture task's name ("legacy-task"/"custom-task-example") matches any of the 87
    // canonical catalogue names, so none of the 87 pre-exist under this fixture and all 87 tasks
    // + 130 revisions are freshly inserted by the seed - 89 tasks / 131 revisions total, the same
    // outcome the former _0034__V3ReconcileCatalogue unit (dropped, folded into this seed's own
    // insert-if-absent logic - see _0022's javadoc) used to produce by matching on _id instead.
    MongoCollection<Document> tasks = v3.getCollection(PREFIX + "_tasks");
    MongoCollection<Document> taskRevisions = v3.getCollection(PREFIX + "_task_revisions");
    assertThat(tasks.countDocuments()).isEqualTo(89);
    assertThat(taskRevisions.countDocuments()).isEqualTo(131);
    assertThat(v3.getCollection(PREFIX + "_task_templates").countDocuments()).isZero();

    // The minimal fixture doc (every optional field absent) migrated without throwing.
    Document minimalTask = tasks.find(Filters.eq("_id", minimalTaskId)).first();
    assertThat(minimalTask).isNotNull();
    assertThat(minimalTask.getString("name")).isEqualTo("legacy-task");

    // The fuller fixture doc: slugified name, templateTask->template mapping, config folded into
    // spec.params (key->name, label/type/placeholder/readOnly carried straight across).
    Document customTask = tasks.find(Filters.eq("_id", customTaskId)).first();
    assertThat(customTask).isNotNull();
    assertThat(customTask.getString("name")).isEqualTo("custom-task-example");
    assertThat(customTask.getString("type")).isEqualTo("template");
    Document customRevision = taskRevisions.find(Filters.eq("parentRef", customTaskId.toString())).first();
    assertThat(customRevision).isNotNull();
    assertThat(customRevision.getString("displayName")).isEqualTo("Custom Task Example");
    assertThat(customRevision.containsKey("config")).isFalse();
    Document customChangelog = (Document) customRevision.get("changelog");
    assertThat(customChangelog.getString("author")).isEqualTo("5e831153d0827100011c29f6");
    assertThat(customChangelog.containsKey("userName")).isFalse();
    Document customSpec = (Document) customRevision.get("spec");
    @SuppressWarnings("unchecked")
    List<Document> customParams = (List<Document>) customSpec.get("params");
    assertThat(customParams).hasSize(1);
    assertThat(customParams.get(0).getString("name")).isEqualTo("path");
    assertThat(customParams.get(0).getString("label")).isEqualTo("Path");

    // _0006__V3MigrateTaskCatalogue's task_runs sub-step (formerly _0026__V3MigrateTaskRunRefs):
    // task_runs migrated - templateRef resolved to the new task's id, templateVersion correctly
    // read into taskVersion (the legacy 4033 bug read the new, absent key instead).
    Document taskRun = v3.getCollection(PREFIX + "_task_runs").find(Filters.eq("_id", taskRunId)).first();
    assertThat(taskRun).isNotNull();
    assertThat(taskRun.getString("taskRef")).isEqualTo(customTaskId.toString());
    assertThat(taskRun.containsKey("templateRef")).isFalse();
    assertThat(taskRun.getInteger("taskVersion")).isEqualTo(1);
    assertThat(taskRun.containsKey("templateVersion")).isFalse();

    // _0023__SeedTemplates (Phase 5, still v3-gated) skipped: no starter templates were seeded
    // either.
    assertThat(v3.getCollection(PREFIX + "_workflow_templates").countDocuments()).isZero();
    assertThat(v3.getCollection(PREFIX + "_integration_templates").countDocuments()).isZero();

    // _0002/_0003/_0020 are NOT v3-skipped: the graph root, system workspace and roles are seeded
    // exactly as on a fresh/v4 install.
    assertThat(v3.getCollection(PREFIX + "_rel_nodes").find(Filters.eq("_id", "root:root")).first())
        .isNotNull();
    assertThat(v3.getCollection(PREFIX + "_teams").find(Filters.eq("name", "system")).first())
        .isNotNull();
    assertThat(v3.getCollection(PREFIX + "_roles").countDocuments()).isEqualTo(5);

    // _0005 has neither global_config nor global_params to migrate on this fixture - no-op,
    // nothing thrown, "parameters" stays empty.
    assertThat(v3.getCollection(PREFIX + "_parameters").countDocuments()).isZero();

    // Re-running against the same v3-shaped database stays a no-op skip, not a second attempt to
    // seed - _0005/_0006's own re-runs are no-op rewrites/insert-if-absent passes over the
    // already-migrated documents (task_templates is already gone, so _0006 iterates nothing), and
    // _0022__SeedTaskCatalogue's own re-run inserts nothing further (every task/revision it
    // reconciled on the first run already exists).
    v3.getCollection(PREFIX + "_sys_changelog_loader").drop();
    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();
    assertThat(settings.countDocuments()).isEqualTo(7);
    assertThat(tasks.countDocuments()).isEqualTo(89);
    assertThat(taskRevisions.countDocuments()).isEqualTo(131);
    Document taskRunAfterSecondRun =
        v3.getCollection(PREFIX + "_task_runs").find(Filters.eq("_id", taskRunId)).first();
    assertThat(taskRunAfterSecondRun.getString("taskRef")).isEqualTo(customTaskId.toString());
    assertThat(taskRunAfterSecondRun.containsKey("templateRef")).isFalse();
    assertThat(v3.getCollection(PREFIX + "_workflow_templates").countDocuments()).isZero();
  }

  private static void insertV3Setting(MongoDatabase db, String id, String v3Key) {
    db.getCollection(PREFIX + "_settings")
        .insertOne(new Document("_id", new ObjectId(id)).append("key", v3Key));
  }

  /**
   * V4-shaped fixture for {@code _0024__V4RepairTaskVersions}/{@code
   * _0025__V4RepairWorkflowAudit} — the two "best-effort v4 repair" units from maintainer ruling
   * M-2 (see "v3 → v5 migration consolidation" in {@code specifications/merge-execution-plan.md}).
   * Unlike every other test in this class, this fixture must be v4-shaped, not v3-shaped: the
   * real v3 dump {@code V3DumpMigrationTest} runs against never exercises these two units at all
   * (they are gated {@code InstallGeneration.V4}), so a synthetic fixture is the only way to prove
   * them.
   */
  @Test
  void v4InstallRepairsTaskVersionsAndWorkflowAudit() {
    String uri = MONGO.getReplicaSetUrl("v4install");
    MongoDatabase v4 = client.getDatabase("v4install");

    // changeId "4000" is the v4-chain marker InstallGeneration keys V4 detection on directly
    // (see that enum's javadoc) - "112" is included too since a real v4 install always completed
    // the v3 chain first.
    v4.getCollection(PREFIX + "_sys_changelog_flow").insertOne(new Document("changeId", "112"));
    v4.getCollection(PREFIX + "_sys_changelog_flow").insertOne(new Document("changeId", "4000"));

    // ---- _0024__V4RepairTaskVersions fixture ----
    // Three tasks exercising the three possible outcomes: exactly one task_revisions match
    // (repairable), more than one (genuinely ambiguous - left null), and zero (unresolved - left
    // null). None of these tasks need a "tasks" document of their own - the repair unit only
    // consults task_revisions.parentRef, matching the class javadoc's "What IS recoverable"
    // section.
    ObjectId taskSingleRev = new ObjectId();
    ObjectId taskMultiRev = new ObjectId();
    ObjectId taskNoRev = new ObjectId();
    v4.getCollection(PREFIX + "_task_revisions")
        .insertOne(new Document("parentRef", taskSingleRev.toString()).append("version", 3));
    v4.getCollection(PREFIX + "_task_revisions")
        .insertOne(new Document("parentRef", taskMultiRev.toString()).append("version", 1));
    v4.getCollection(PREFIX + "_task_revisions")
        .insertOne(new Document("parentRef", taskMultiRev.toString()).append("version", 2));

    ObjectId wfRevId = new ObjectId();
    v4.getCollection(PREFIX + "_workflow_revisions")
        .insertOne(
            new Document("_id", wfRevId)
                .append("workflowRef", new ObjectId().toString())
                .append("version", 1)
                .append(
                    "tasks",
                    List.of(
                        new Document("name", "stepSingle")
                            .append("taskRef", taskSingleRev.toString())
                            .append("taskVersion", null),
                        new Document("name", "stepMulti")
                            .append("taskRef", taskMultiRev.toString())
                            .append("taskVersion", null),
                        new Document("name", "stepNoRev")
                            .append("taskRef", taskNoRev.toString())
                            .append("taskVersion", null),
                        // Already carries a version - must never be recomputed/overwritten.
                        new Document("name", "stepAlready")
                            .append("taskRef", taskSingleRev.toString())
                            .append("taskVersion", 5),
                        // No taskRef at all (start/end nodes) - must be skipped, never crash.
                        new Document("name", "start").append("taskVersion", null))));

    ObjectId wfTemplateId = new ObjectId();
    v4.getCollection(PREFIX + "_workflow_templates")
        .insertOne(
            new Document("_id", wfTemplateId)
                .append(
                    "tasks",
                    List.of(
                        new Document("name", "tstep")
                            .append("taskRef", taskSingleRev.toString())
                            .append("taskVersion", null))));

    ObjectId taskRunId = new ObjectId();
    v4.getCollection(PREFIX + "_task_runs")
        .insertOne(
            new Document("_id", taskRunId)
                .append("name", "run-step")
                .append("workflowRunRef", "wfr-v4-repair")
                .append("status", "succeeded")
                .append("taskRef", taskSingleRev.toString())
                .append("taskVersion", null));

    // ---- _0025__V4RepairWorkflowAudit fixture ----
    // A workspace whose TEAM audit record already exists (v4's own 4038 workspace half worked),
    // one workflow that resolves cleanly (the repair case), one whose hasWorkflow edge is
    // missing (skip case 1), and one whose edge resolves but whose workspace has no audit record
    // at all (skip case 2 - defensive, should not happen on a genuine v4 install).
    ObjectId workspaceId = new ObjectId();
    v4.getCollection(PREFIX + "_teams")
        .insertOne(new Document("_id", workspaceId).append("name", "acme-v4").append("type", "hobby"));
    ObjectId workspaceAuditId = new ObjectId();
    v4.getCollection(PREFIX + "_audit")
        .insertOne(
            new Document("_id", workspaceAuditId)
                .append("scope", "TEAM")
                .append("selfRef", workspaceId.toString())
                .append("selfName", "acme-v4")
                .append("creationDate", EARLIER)
                .append("events", List.of())
                .append("data", new Document("name", "acme-v4")));

    ObjectId workflowId = new ObjectId();
    v4.getCollection(PREFIX + "_workflows").insertOne(new Document("_id", workflowId).append("name", "v4-workflow"));
    v4.getCollection(PREFIX + "_rel_edges")
        .insertOne(
            new Document("from", "workspace:" + workspaceId)
                .append("label", "hasWorkflow")
                .append("to", "workflow:" + workflowId)
                .append("data", new Document()));

    ObjectId workflowNoEdgeId = new ObjectId();
    v4.getCollection(PREFIX + "_workflows")
        .insertOne(new Document("_id", workflowNoEdgeId).append("name", "orphan-workflow"));

    ObjectId workspaceNoAuditId = new ObjectId();
    v4.getCollection(PREFIX + "_teams")
        .insertOne(new Document("_id", workspaceNoAuditId).append("name", "no-audit-workspace-v4").append("type", "hobby"));
    ObjectId workflowNoWorkspaceAuditId = new ObjectId();
    v4.getCollection(PREFIX + "_workflows")
        .insertOne(new Document("_id", workflowNoWorkspaceAuditId).append("name", "orphan-workspace-workflow"));
    v4.getCollection(PREFIX + "_rel_edges")
        .insertOne(
            new Document("from", "workspace:" + workspaceNoAuditId)
                .append("label", "hasWorkflow")
                .append("to", "workflow:" + workflowNoWorkspaceAuditId)
                .append("data", new Document()));

    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();

    assertV4TaskVersionsRepaired(v4, wfRevId, wfTemplateId, taskRunId, taskSingleRev, taskMultiRev);
    assertV4WorkflowAuditRepaired(v4, workflowId, workspaceAuditId, workflowNoEdgeId, workflowNoWorkspaceAuditId);

    // Idempotency - a second run repairs nothing further (already-repaired entries are no longer
    // null) and creates no duplicate audit records.
    assertThatCode(() -> LoaderApplication.execute(uri, PREFIX)).doesNotThrowAnyException();
    assertV4TaskVersionsRepaired(v4, wfRevId, wfTemplateId, taskRunId, taskSingleRev, taskMultiRev);
    assertV4WorkflowAuditRepaired(v4, workflowId, workspaceAuditId, workflowNoEdgeId, workflowNoWorkspaceAuditId);
  }

  @SuppressWarnings("unchecked")
  private void assertV4TaskVersionsRepaired(
      MongoDatabase v4,
      ObjectId wfRevId,
      ObjectId wfTemplateId,
      ObjectId taskRunId,
      ObjectId taskSingleRev,
      ObjectId taskMultiRev) {
    Document revision =
        v4.getCollection(PREFIX + "_workflow_revisions").find(Filters.eq("_id", wfRevId)).first();
    List<Document> tasks = (List<Document>) revision.get("tasks");
    assertThat(taskNamed(tasks, "stepSingle").getInteger("taskVersion"))
        .as("exactly one task_revisions match - unambiguously repairable")
        .isEqualTo(3);
    assertThat(taskNamed(tasks, "stepMulti").get("taskVersion"))
        .as("more than one task_revisions match - genuinely ambiguous, left null")
        .isNull();
    assertThat(taskNamed(tasks, "stepNoRev").get("taskVersion"))
        .as("no task_revisions match at all - unresolved, left null")
        .isNull();
    assertThat(taskNamed(tasks, "stepAlready").getInteger("taskVersion"))
        .as("already carried a version - must never be recomputed")
        .isEqualTo(5);
    assertThat(taskNamed(tasks, "start").get("taskVersion"))
        .as("no taskRef at all - skipped, never crashes")
        .isNull();

    Document template =
        v4.getCollection(PREFIX + "_workflow_templates").find(Filters.eq("_id", wfTemplateId)).first();
    List<Document> templateTasks = (List<Document>) template.get("tasks");
    assertThat(taskNamed(templateTasks, "tstep").getInteger("taskVersion")).isEqualTo(3);

    Document taskRun = v4.getCollection(PREFIX + "_task_runs").find(Filters.eq("_id", taskRunId)).first();
    assertThat(taskRun.getInteger("taskVersion")).isEqualTo(3);
  }

  private Document taskNamed(List<Document> tasks, String name) {
    return tasks.stream()
        .filter(t -> name.equals(t.getString("name")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No task named " + name));
  }

  private void assertV4WorkflowAuditRepaired(
      MongoDatabase v4,
      ObjectId workflowId,
      ObjectId workspaceAuditId,
      ObjectId workflowNoEdgeId,
      ObjectId workflowNoWorkspaceAuditId) {
    MongoCollection<Document> audit = v4.getCollection(PREFIX + "_audit");

    assertThat(audit.countDocuments(Filters.eq("scope", "WORKFLOW")))
        .as("only the one cleanly-resolvable workflow gets an audit record")
        .isEqualTo(1);

    Document repaired =
        audit.find(Filters.and(Filters.eq("scope", "WORKFLOW"), Filters.eq("selfRef", workflowId.toString()))).first();
    assertThat(repaired).isNotNull();
    assertThat(repaired.getString("selfName")).isEqualTo("v4-workflow");
    assertThat(repaired.getString("parent"))
        .as("parent must be the workspace's OWN AUDIT RECORD id, not its domain _id")
        .isEqualTo(workspaceAuditId.toString());
    assertThat(((Document) repaired.get("data")).getString("name")).isEqualTo("v4-workflow");

    assertThat(
            audit
                .find(Filters.and(Filters.eq("scope", "WORKFLOW"), Filters.eq("selfRef", workflowNoEdgeId.toString())))
                .first())
        .as("no hasWorkflow edge - skipped, never fabricated")
        .isNull();
    assertThat(
            audit
                .find(
                    Filters.and(
                        Filters.eq("scope", "WORKFLOW"),
                        Filters.eq("selfRef", workflowNoWorkspaceAuditId.toString())))
                .first())
        .as("edge resolves but the workspace has no TEAM audit record - skipped, never fabricated")
        .isNull();
  }

  @Test
  void failsWhenMongoIsUnreachable() {
    assertThatThrownBy(
            () ->
                LoaderApplication.execute(
                    "mongodb://localhost:1/boomerang?serverSelectionTimeoutMS=1500&connectTimeoutMS=1500",
                    PREFIX))
        .isInstanceOf(Exception.class);
  }

  private void assertTaskRunIndexes() {
    Map<String, Document> indexes = indexesByName("task_runs");
    assertThat(indexes.get("claim_page").get("key", Document.class).keySet())
        .containsExactly("type", "status", "phase", "creationDate");
    assertThat(indexes.get("run_tasks").get("key", Document.class).keySet())
        .containsExactly("workflowRunRef", "status", "name");
    assertThat(indexes.get("lease_sweep").get("key", Document.class).keySet())
        .containsExactly("claim.leaseExpiresAt");
    assertThat(indexes.get("lease_sweep").getBoolean("sparse")).isTrue();
    assertThat(indexes.get("timeout_sweep").getBoolean("sparse")).isTrue();
    assertThat(indexes.get("wait_sweep").getBoolean("sparse")).isTrue();
    assertThat(indexes.get("node_uniqueness").get("key", Document.class).keySet())
        .containsExactly("workflowRunRef", "name");
    assertThat(indexes.get("node_uniqueness").getBoolean("unique")).isTrue();
  }

  private void assertWorkflowRunIndexes() {
    Map<String, Document> indexes = indexesByName("workflow_runs");
    assertThat(indexes.get("claim_page").get("key", Document.class).keySet())
        .containsExactly("status", "phase", "creationDate");
    assertThat(indexes.get("timeout_sweep").getBoolean("sparse")).isTrue();
    assertThat(indexes.get("paused_lookup").get("key", Document.class).keySet())
        .containsExactly("pauseRequestedAt");
    assertThat(indexes.get("paused_lookup").getBoolean("sparse")).isTrue();
  }

  private void assertEventCollectionIndexes() {
    Map<String, Document> outbox = indexesByName("events_outbox");
    assertThat(outbox.get("dispatch_page").get("key", Document.class).keySet())
        .containsExactly("status", "occurredAt");
    assertThat(ttlSeconds(outbox.get("sent_ttl"))).isEqualTo(TimeUnit.DAYS.toSeconds(7));

    Map<String, Document> inbox = indexesByName("events_inbox");
    assertThat(ttlSeconds(inbox.get("received_ttl"))).isEqualTo(TimeUnit.DAYS.toSeconds(7));
    assertThat(inbox.get("redrive_page").get("key", Document.class).keySet())
        .containsExactly("status", "receivedAt");
  }

  private void assertLockAndWorkflowIndexes() {
    Map<String, Document> locks = indexesByName("task_locks");
    assertThat(locks.get("lease_ttl").get("key", Document.class).keySet())
        .containsExactly("expiresAt");
    assertThat(ttlSeconds(locks.get("lease_ttl"))).isZero();

    Map<String, Document> workflows = indexesByName("workflows");
    assertThat(workflows.get("status_lookup").get("key", Document.class).keySet())
        .containsExactly("status");
  }

  private static long ttlSeconds(Document index) {
    return ((Number) index.get("expireAfterSeconds")).longValue();
  }

  private void assertUniqueIndex(String collection, String indexName, List<String> keys) {
    Document index = indexesByName(collection).get(indexName);
    assertThat(index).isNotNull();
    assertThat(index.get("key", Document.class).keySet()).containsExactlyElementsOf(keys);
    assertThat(index.getBoolean("unique")).isTrue();
  }

  private void assertTaskRunDedupe() {
    // task-a — the terminal (succeeded) document is kept even though it is the later one; the
    // duplicate is deleted.
    assertThat(findTaskRun(taskASucceeded)).isNotNull();
    assertThat(findTaskRun(taskARunning)).isNull();

    // task-b — no terminal document, so the earliest created is kept and the later one deleted.
    assertThat(findTaskRun(taskBFirst)).isNotNull();
    assertThat(findTaskRun(taskBSecond)).isNull();

    // Singleton untouched.
    assertThat(findTaskRun(soloTask)).isNotNull();
  }

  private void assertActionDedupe() {
    List<Document> gatesForTr1 = new ArrayList<>();
    collection("actions").find(Filters.eq("taskRunRef", "tr1")).into(gatesForTr1);
    assertThat(gatesForTr1).hasSize(1);
    assertThat(gatesForTr1.get(0).getObjectId("_id")).isEqualTo(earliestGate);
    assertThat(collection("actions").countDocuments(Filters.eq("taskRunRef", "tr2"))).isEqualTo(1);
  }

  private void assertAgentDedupe() {
    // Phase 3's rename unit (_0015__DispatcherRename) renames "agents" -> "dispatchers" BEFORE
    // Phase 4's dedupe unit (_0019__DomainIndexes) runs - the opposite order from the old chain,
    // where the dedupe ran against the still-legacy "agents" name ahead of the rename. _0019's
    // dedupe logic now targets "dispatchers" directly (see that unit's javadoc), so the deduped
    // rows are asserted under that same post-rename name either way.
    List<Document> registrations = new ArrayList<>();
    collection("dispatchers")
        .find(Filters.and(Filters.eq("name", "agent-1"), Filters.eq("host", "host-a")))
        .into(registrations);
    assertThat(registrations).hasSize(1);
    assertThat(registrations.get(0).getObjectId("_id")).isEqualTo(latestConnectedAgent);
    assertThat(collection("dispatchers").countDocuments()).isEqualTo(2);
  }

  private void assertAgentsCollectionRenamed() {
    List<String> names = new ArrayList<>();
    db.listCollectionNames().into(names);
    assertThat(names).doesNotContain(PREFIX + "_agents");
    assertThat(names).contains(PREFIX + "_dispatchers");
  }

  private void assertDispatcherRefRenamed() {
    Document taskRun = collection("task_runs").find(Filters.eq("_id", taskRunWithAgentRef)).first();
    assertThat(taskRun).isNotNull();
    assertThat(taskRun.getString("dispatcherRef")).isEqualTo("agent-1");
    assertThat(taskRun.containsKey("agentRef")).isFalse();

    Document workflowRun =
        collection("workflow_runs").find(Filters.eq("_id", workflowRunWithAgentRef)).first();
    assertThat(workflowRun).isNotNull();
    assertThat(workflowRun.getString("dispatcherRef")).isEqualTo("agent-1");
    assertThat(workflowRun.containsKey("agentRef")).isFalse();
  }

  private void assertWorkspaceRenameApplied() {
    // rel_nodes: re-keyed from "team:t1"/type=team to "workspace:t1"/type=workspace.
    assertThat(collection("rel_nodes").find(Filters.eq("_id", "team:t1")).first()).isNull();
    Document node = collection("rel_nodes").find(Filters.eq("_id", "workspace:t1")).first();
    assertThat(node).isNotNull();
    assertThat(node.getString("type")).isEqualTo("workspace");
    assertThat(node.getString("ref")).isEqualTo("t1");
    assertThat(node.getString("slug")).isEqualTo("acme");

    // rel_edges: every "team:" prefixed from/to becomes "workspace:"; unrelated node types
    // (root, user, workflow) are untouched.
    assertThat(collection("rel_edges").countDocuments(Filters.regex("from", "^team:")))
        .isZero();
    assertThat(collection("rel_edges").countDocuments(Filters.regex("to", "^team:"))).isZero();
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(Filters.eq("from", "root:root"), Filters.eq("to", "workspace:t1"))))
        .isEqualTo(1);
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "user:u1"), Filters.eq("to", "workspace:t1"))))
        .isEqualTo(1);
    assertThat(
            collection("rel_edges")
                .countDocuments(
                    Filters.and(
                        Filters.eq("from", "workspace:t1"), Filters.eq("to", "workflow:w1"))))
        .isEqualTo(1);

    // tokens.type / roles.type: "team" -> "workspace".
    Document token = collection("tokens").find(Filters.eq("_id", tokenWithTeamScope)).first();
    assertThat(token).isNotNull();
    assertThat(token.getString("type")).isEqualTo("workspace");

    Document role = collection("roles").find(Filters.eq("_id", roleWithTeamType)).first();
    assertThat(role).isNotNull();
    assertThat(role.getString("type")).isEqualTo("workspace");
  }

  private static MongoCollection<Document> collection(String name) {
    return db.getCollection(PREFIX + "_" + name);
  }

  private static Document findTaskRun(ObjectId id) {
    return collection("task_runs").find(Filters.eq("_id", id)).first();
  }

  private Map<String, Document> indexesByName(String collection) {
    Map<String, Document> indexes = new java.util.HashMap<>();
    collection(collection).listIndexes().forEach(index -> indexes.put(index.getString("name"), index));
    return indexes;
  }

  private List<Document> snapshot(String collection) {
    return collection(collection).find().into(new ArrayList<>()).stream()
        .sorted(java.util.Comparator.comparing(doc -> doc.getObjectId("_id")))
        .collect(Collectors.toList());
  }

  // rel_nodes carries a plain-string "type:ref" _id (not an ObjectId), so it needs its own
  // sort key.
  private List<Document> snapshotByStringId(String collection) {
    return collection(collection).find().into(new ArrayList<>()).stream()
        .sorted(java.util.Comparator.comparing(doc -> doc.getString("_id")))
        .collect(Collectors.toList());
  }

  private static ObjectId insertTaskRun(
      String workflowRunRef, String name, String status, Date creationDate) {
    ObjectId id = new ObjectId();
    collection("task_runs")
        .insertOne(
            new Document("_id", id)
                .append("workflowRunRef", workflowRunRef)
                .append("name", name)
                .append("status", status)
                .append("creationDate", creationDate));
    return id;
  }

  private static ObjectId insertAction(String taskRunRef, Date creationDate) {
    ObjectId id = new ObjectId();
    collection("actions")
        .insertOne(
            new Document("_id", id)
                .append("taskRunRef", taskRunRef)
                .append("creationDate", creationDate));
    return id;
  }

  private static ObjectId insertAgent(String name, String host, Date lastConnectedDate) {
    ObjectId id = new ObjectId();
    collection("agents")
        .insertOne(
            new Document("_id", id)
                .append("name", name)
                .append("host", host)
                .append("lastConnectedDate", lastConnectedDate));
    return id;
  }

  private static ObjectId insertTaskRunWithAgentRef(
      String workflowRunRef, String agentRef, Date creationDate) {
    ObjectId id = new ObjectId();
    collection("task_runs")
        .insertOne(
            new Document("_id", id)
                .append("workflowRunRef", workflowRunRef)
                .append("name", "claimed-task")
                .append("status", "running")
                .append("creationDate", creationDate)
                .append("agentRef", agentRef));
    return id;
  }

  private static ObjectId insertWorkflowRunWithAgentRef(String agentRef, Date creationDate) {
    ObjectId id = new ObjectId();
    collection("workflow_runs")
        .insertOne(
            new Document("_id", id)
                .append("status", "running")
                .append("creationDate", creationDate)
                .append("agentRef", agentRef));
    return id;
  }

  /**
   * Seeds a pre-DD-01 relationship fixture: a "team" node (root -> team:t1) with a member edge
   * (user:u1 -> team:t1) and an owned-workflow edge (team:t1 -> workflow:w1), so the migration's
   * node re-key and edge from/to prefix rewrite both have "team:" data to act on.
   */
  private static void seedTeamRelationshipGraph() {
    collection("rel_nodes")
        .insertOne(
            new Document("_id", "team:t1")
                .append("type", "team")
                .append("ref", "t1")
                .append("slug", "acme")
                .append("data", new Document()));
    collection("rel_edges")
        .insertOne(
            new Document("_id", new ObjectId())
                .append("from", "root:root")
                .append("label", "contains")
                .append("to", "team:t1")
                .append("data", new Document()));
    collection("rel_edges")
        .insertOne(
            new Document("_id", new ObjectId())
                .append("from", "user:u1")
                .append("label", "memberOf")
                .append("to", "team:t1")
                .append("data", new Document("role", "owner")));
    collection("rel_edges")
        .insertOne(
            new Document("_id", new ObjectId())
                .append("from", "team:t1")
                .append("label", "hasWorkflow")
                .append("to", "workflow:w1")
                .append("data", new Document()));
  }

  /**
   * A user plus its relationship node, as an install that has run the legacy loader would carry.
   * The seed change unit adds {@code admin} users to the system workspace and leaves the rest.
   */
  private static ObjectId insertUser(String email, String type) {
    ObjectId id = new ObjectId();
    collection("users").insertOne(new Document("_id", id).append("email", email).append("type", type));
    collection("rel_nodes")
        .insertOne(
            new Document("_id", "user:" + id)
                .append("type", "user")
                .append("ref", id.toString())
                .append("slug", email)
                .append("data", new Document()));
    return id;
  }

  private static ObjectId insertToken(String type) {
    ObjectId id = new ObjectId();
    collection("tokens")
        .insertOne(
            new Document("_id", id)
                .append("type", type)
                .append("name", "legacy-team-token")
                .append("principal", "t1")
                .append("permissions", new ArrayList<>()));
    return id;
  }

  private static ObjectId insertRole(String type) {
    ObjectId id = new ObjectId();
    collection("roles")
        .insertOne(
            new Document("_id", id)
                .append("type", type)
                .append("name", "owner")
                .append("permissions", new ArrayList<>()));
    return id;
  }
}

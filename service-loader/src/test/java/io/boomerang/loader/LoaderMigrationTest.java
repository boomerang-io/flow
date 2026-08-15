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
    assertThat(collection("sys_changelog_loader").countDocuments()).isGreaterThanOrEqualTo(12);

    List<Document> taskRunsBefore = snapshot("task_runs");
    List<Document> workflowRunsBefore = snapshot("workflow_runs");
    List<Document> actionsBefore = snapshot("actions");
    List<Document> dispatchersBefore = snapshot("dispatchers");
    List<Document> relNodesBefore = snapshotByStringId("rel_nodes");
    List<Document> relEdgesBefore = snapshot("rel_edges");
    List<Document> tokensBefore = snapshot("tokens");
    List<Document> rolesBefore = snapshot("roles");

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
    // The dedupe changeunit (_0006) runs against the still-legacy "agents" name, before the
    // rename changeunit (_0011) renames the collection - so the deduped rows are asserted under
    // their post-rename name, "dispatchers".
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

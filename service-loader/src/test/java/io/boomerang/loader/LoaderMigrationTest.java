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

  @BeforeAll
  static void seedExistingInstallation() {
    MONGO.start();
    client = MongoClients.create(MONGO.getReplicaSetUrl("boomerang"));
    db = client.getDatabase("boomerang");

    collection("sys_changelog_flow").insertOne(new Document("changeId", "001"));
    collection("workflows").insertOne(new Document("name", "wf"));

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
    assertUniqueIndex("agents", "registration", List.of("name", "host"));
    assertEventCollectionIndexes();
    assertTaskRunDedupe();
    assertActionDedupe();
    assertAgentDedupe();
    assertThat(collection("sys_changelog_loader").countDocuments()).isGreaterThanOrEqualTo(8);

    List<Document> taskRunsBefore = snapshot("task_runs");
    List<Document> actionsBefore = snapshot("actions");
    List<Document> agentsBefore = snapshot("agents");

    assertThatCode(() -> LoaderApplication.execute(MONGO.getReplicaSetUrl("boomerang"), PREFIX))
        .doesNotThrowAnyException();

    assertThat(snapshot("task_runs")).isEqualTo(taskRunsBefore);
    assertThat(snapshot("actions")).isEqualTo(actionsBefore);
    assertThat(snapshot("agents")).isEqualTo(agentsBefore);
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
    assertThat(indexes.get("node_generation").get("key", Document.class).keySet())
        .containsExactly("workflowRunRef", "name", "mapIndex", "attempt");
    assertThat(indexes.get("node_generation").getBoolean("unique")).isTrue();
  }

  private void assertWorkflowRunIndexes() {
    Map<String, Document> indexes = indexesByName("workflow_runs");
    assertThat(indexes.get("claim_page").get("key", Document.class).keySet())
        .containsExactly("status", "phase", "creationDate");
    assertThat(indexes.get("timeout_sweep").getBoolean("sparse")).isTrue();
    assertThat(indexes.get("paused_lookup").get("key", Document.class).keySet())
        .containsExactly("pauseRequestedAt");
    assertThat(indexes.get("paused_lookup").getBoolean("sparse")).isTrue();
    assertPartialUnique(indexes.get("idempotency_key"), "idempotencyKey");
    assertPartialUnique(indexes.get("created_by_task_run"), "createdByTaskRunRef");
    assertPartialUnique(indexes.get("retry_attempt"), "retryOfRef");
    assertThat(indexes.get("retry_attempt").get("key", Document.class).keySet())
        .containsExactly("retryOfRef", "retryAttempt");
  }

  private void assertEventCollectionIndexes() {
    Map<String, Document> outbox = indexesByName("events_outbox");
    assertThat(outbox.get("dispatch_page").get("key", Document.class).keySet())
        .containsExactly("status", "occurredAt");
    assertThat(ttlSeconds(outbox.get("sent_ttl"))).isEqualTo(TimeUnit.DAYS.toSeconds(7));

    Map<String, Document> ingress = indexesByName("events_ingress");
    assertThat(ttlSeconds(ingress.get("received_ttl"))).isEqualTo(TimeUnit.DAYS.toSeconds(7));
    assertThat(ingress.get("redrive_page").get("key", Document.class).keySet())
        .containsExactly("status", "receivedAt");
  }

  private static long ttlSeconds(Document index) {
    return ((Number) index.get("expireAfterSeconds")).longValue();
  }

  private void assertPartialUnique(Document index, String filteredField) {
    assertThat(index.getBoolean("unique")).isTrue();
    assertThat(index.get("partialFilterExpression", Document.class).keySet())
        .containsExactly(filteredField);
  }

  private void assertUniqueIndex(String collection, String indexName, List<String> keys) {
    Document index = indexesByName(collection).get(indexName);
    assertThat(index).isNotNull();
    assertThat(index.get("key", Document.class).keySet()).containsExactlyElementsOf(keys);
    assertThat(index.getBoolean("unique")).isTrue();
  }

  private void assertTaskRunDedupe() {
    // task-a — the terminal (succeeded) document is kept even though it is the later one.
    Document keptA = findTaskRun(taskASucceeded);
    assertThat(keptA.get("superseded")).isNull();
    Document supersededA = findTaskRun(taskARunning);
    assertThat(supersededA.get("superseded", Document.class).getString("by"))
        .isEqualTo("migration");
    assertThat(supersededA.getInteger("attempt")).isEqualTo(1);

    // task-b — no terminal document, so the earliest created is kept.
    assertThat(findTaskRun(taskBFirst).get("superseded")).isNull();
    Document supersededB = findTaskRun(taskBSecond);
    assertThat(supersededB.get("superseded", Document.class).get("at")).isInstanceOf(Date.class);
    assertThat(supersededB.getInteger("attempt")).isEqualTo(1);

    // Singleton untouched.
    Document solo = findTaskRun(soloTask);
    assertThat(solo.get("superseded")).isNull();
    assertThat(solo.get("attempt")).isNull();
  }

  private void assertActionDedupe() {
    List<Document> gatesForTr1 = new ArrayList<>();
    collection("actions").find(Filters.eq("taskRunRef", "tr1")).into(gatesForTr1);
    assertThat(gatesForTr1).hasSize(1);
    assertThat(gatesForTr1.get(0).getObjectId("_id")).isEqualTo(earliestGate);
    assertThat(collection("actions").countDocuments(Filters.eq("taskRunRef", "tr2"))).isEqualTo(1);
  }

  private void assertAgentDedupe() {
    List<Document> registrations = new ArrayList<>();
    collection("agents")
        .find(Filters.and(Filters.eq("name", "agent-1"), Filters.eq("host", "host-a")))
        .into(registrations);
    assertThat(registrations).hasSize(1);
    assertThat(registrations.get(0).getObjectId("_id")).isEqualTo(latestConnectedAgent);
    assertThat(collection("agents").countDocuments()).isEqualTo(2);
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
}

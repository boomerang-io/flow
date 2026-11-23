package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * What recording a large run's cumulative duration costs its completion: one find of the run's
 * task runs on {@code run_tasks}, read in batches, which never returns a for-each parent's item
 * array.
 */
class CumulativeDurationCostTest extends AbstractEngineIntegrationTest {

  private static final int TASK_RUNS = 5_000;

  @Autowired private WorkflowRunAuditBridge bridge;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void captureOn() {
    if (settingsRepository.findOneByKey("audit") == null) {
      SettingEntity settings = new SettingEntity();
      settings.setKey("audit");
      settings.setName("Audit");
      settings.setConfig(List.of(config("enabled", "true"), config("level", "WRITE")));
      settingsRepository.save(settings);
    }
  }

  @AfterEach
  void captureOff() {
    SettingEntity settings = settingsRepository.findOneByKey("audit");
    if (settings != null) {
      settingsRepository.delete(settings);
    }
  }

  @Test
  void aRunOfFiveThousandTaskRunsIsSummedInOneFindWithoutTheItems() {
    String runId = "cumulative-cost-run-" + System.nanoTime();
    String workflowRef = "cumulative-cost-workflow";
    long now = System.currentTimeMillis();
    List<Document> taskRuns = new ArrayList<>();
    List<String> items = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      items.add("x".repeat(1024));
    }
    taskRuns.add(
        new Document("workflowRunRef", runId)
            .append("type", "template")
            .append("status", "succeeded")
            .append("name", "fan-out")
            .append("phase", "completed")
            .append("startTime", new Date(now - 600_000L))
            .append("duration", 600_000L)
            .append("foreach", new Document("items", items)));
    for (int i = 1; i < TASK_RUNS; i++) {
      Document taskRun =
          new Document("workflowRunRef", runId)
              .append("type", "template")
              .append("status", "succeeded")
              .append("name", "task-" + i)
              .append("phase", "completed")
              .append("startTime", new Date(now - 1_000L))
              .append("duration", 1_000L);
      if (i % 10 == 0) {
        taskRun.append(
            "attempts",
            List.of(
                new Document("number", 1)
                    .append("claimedAt", new Date(now - 10_000L))
                    .append("endedAt", new Date(now - 5_000L))
                    .append("duration", 2_000L)
                    .append("status", "failed")
                    .append("statusReason", "JobFailed")));
      }
      taskRuns.add(taskRun);
    }
    mongoTemplate.getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class)).insertMany(taskRuns);
    WorkflowRunTransition completion =
        new WorkflowRunTransition(
            runId, workflowRef, RunStatus.running, RunPhase.running, RunStatus.succeeded, RunPhase.completed);

    try {
      long started = System.nanoTime();
      DatabaseCommands.Recorded<Object> recorded =
          DatabaseCommands.record(
              () -> {
                bridge.onWorkflowRunTransition(completion);
                return null;
              });
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

      List<DatabaseCommands.Command> onTaskRuns =
          recorded.commands().stream().filter(c -> c.collection().endsWith("task_runs")).toList();
      long replyBytes = onTaskRuns.stream().mapToLong(DatabaseCommands.Command::replyBytes).sum();
      System.out.printf(
          "Cumulative duration of %d task runs: %d ms on the completing thread, %d commands on"
              + " task_runs, %d documents, %d bytes returned%n",
          TASK_RUNS, elapsedMillis, onTaskRuns.size(), recorded.documentsRead("task_runs"), replyBytes);

      assertThat(onTaskRuns.get(0).name()).isEqualTo("find");
      assertThat(recorded.documentsRead("task_runs")).isEqualTo(TASK_RUNS);
      awaitEngine("the completion's audit event")
          .untilAsserted(
              () ->
                  assertThat(
                          mongoTemplate
                              .findOne(
                                  Query.query(
                                      Criteria.where("resourceId").is(runId).and("action").is("UPDATE")),
                                  AuditEventEntity.class)
                              .getPayload())
                      .containsEntry(
                          "cumulativeDuration", (TASK_RUNS - 1) * 1_000L + (TASK_RUNS / 10 - 1) * 2_000L));
    } finally {
      mongoTemplate
          .getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class))
          .deleteMany(new Document("workflowRunRef", runId));
    }
  }

  @Test
  void aForEachParentsItemsAreNeverRead() {
    String runId = "cumulative-items-run-" + System.nanoTime();
    List<String> items = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      items.add("x".repeat(1024));
    }
    mongoTemplate
        .getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class))
        .insertOne(
            new Document("workflowRunRef", runId)
                .append("type", "template")
                .append("status", "succeeded")
                .append("name", "fan-out")
                .append("phase", "completed")
                .append("duration", 600_000L)
                .append("foreach", new Document("items", items)));
    try {
      DatabaseCommands.Recorded<Object> recorded =
          DatabaseCommands.record(
              () -> {
                bridge.onWorkflowRunTransition(
                    new WorkflowRunTransition(
                        runId,
                        "cumulative-items-workflow",
                        RunStatus.running,
                        RunPhase.running,
                        RunStatus.succeeded,
                        RunPhase.completed));
                return null;
              });

      assertThat(
              recorded.commands().stream()
                  .filter(c -> c.collection().endsWith("task_runs"))
                  .mapToLong(DatabaseCommands.Command::replyBytes)
                  .sum())
          .as("the parent's 256 KB of items stay in the database")
          .isLessThan(1024);
    } finally {
      mongoTemplate
          .getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class))
          .deleteMany(new Document("workflowRunRef", runId));
    }
  }

  private static SettingConfig config(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }
}

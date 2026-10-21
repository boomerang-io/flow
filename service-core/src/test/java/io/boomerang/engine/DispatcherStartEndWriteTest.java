package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doAnswer;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * A dispatcher's start and end write only the fields the request carries, and only while the
 * TaskRun is unfinished and still held by that dispatcher. A whole-document save of the copy the
 * route read would undo a cancel or a timeout that completed the TaskRun meanwhile.
 */
class DispatcherStartEndWriteTest extends AbstractSpiedEngineIntegrationTest {

  private static final String DISPATCHER = "start-end-write-dispatcher";

  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void aStartWritesTheFieldsItCarriesWithSecretsSealed() {
    String taskRunId = claimedTask("start-fields");
    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(DISPATCHER);
    start.getLabels().put("example.com/team", "payments");
    start.getAnnotations().put("example.com/note", "from the dispatcher");
    start.setParams(
        List.of(
            new RunParam("apiToken", "s3cret-value", ParamType.secret),
            new RunParam("region", "ap-southeast-2", ParamType.string)));
    start.setTimeout(7L);

    taskRunService.start(taskRunId, Optional.of(start));

    TaskRunEntity stored = taskRunRepository.findById(taskRunId).orElseThrow();
    assertThat(stored.getLabels()).containsEntry("example.com/team", "payments");
    assertThat(stored.getAnnotations()).containsEntry("example.com/note", "from the dispatcher");
    assertThat(stored.getParams())
        .extracting(RunParam::getName, RunParam::getValue)
        .contains(
            tuple("apiToken", "s3cret-value"), tuple("region", "ap-southeast-2"));
    assertThat(stored.getTimeout()).isEqualTo(7L);

    Document raw = raw(taskRunId);
    assertThat(raw.get("annotations", Document.class)).containsKey("example#com/note");
    assertThat(
            raw.getList("params", Document.class).stream()
                .filter(p -> "apiToken".equals(p.getString("name")))
                .findFirst()
                .orElseThrow()
                .getString("value"))
        .as("a secret sent on start is stored encrypted")
        .startsWith("crypt_v1");
  }

  @Test
  void aCancelThatLandsDuringAStartStands() {
    String taskRunId = claimedTask("start-race");
    cancelAfterTheRoutesRead(taskRunId);
    TaskRunStartRequest start = new TaskRunStartRequest();
    start.setDispatcherRef(DISPATCHER);
    start.setParams(List.of(new RunParam("late", "value", ParamType.string)));

    taskRunService.start(taskRunId, Optional.of(start));

    TaskRunEntity stored = taskRunRepository.findById(taskRunId).orElseThrow();
    assertThat(stored.getPhase()).isEqualTo(RunPhase.completed);
    assertThat(stored.getStatus()).isEqualTo(RunStatus.cancelled);
    assertThat(stored.getParams()).extracting(RunParam::getName).doesNotContain("late");
  }

  @Test
  void aCancelThatLandsDuringAnEndStands() {
    String taskRunId = claimedTask("end-race");
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update().set("status", RunStatus.running).set("phase", RunPhase.running),
        TaskRunEntity.class);
    cancelAfterTheRoutesRead(taskRunId);
    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setDispatcherRef(DISPATCHER);
    end.setStatus(RunStatus.succeeded);
    end.setResults(List.of(new RunResult("output", "late")));

    taskRunService.end(taskRunId, Optional.of(end));

    TaskRunEntity stored = taskRunRepository.findById(taskRunId).orElseThrow();
    assertThat(stored.getPhase()).isEqualTo(RunPhase.completed);
    assertThat(stored.getStatus()).isEqualTo(RunStatus.cancelled);
    assertThat(stored.getResults()).extracting(RunResult::getName).doesNotContain("output");
  }

  // A TaskRun this test's dispatcher has claimed and not yet started.
  private String claimedTask(String name) {
    WorkflowRunEntity run =
        savedWorkflowRun("start-end-write-wf-" + name, RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            name, TaskType.template, RunStatus.ready, RunPhase.queued, run.getWorkflowRef(), run.getId());
    RunClaim claim = new RunClaim();
    claim.setBy(DISPATCHER);
    claim.setAt(new Date());
    claim.setLeaseExpiresAt(new Date(System.currentTimeMillis() + 90_000));
    claim.setSeq(1L);
    task.setClaim(claim);
    return taskRunRepository.save(task).getId();
  }

  // The run is cancelled - the TaskRun completed cancelled - just after the route reads it. The
  // route is handed the copy it would have read; every later read sees the cancel.
  private void cancelAfterTheRoutesRead(String taskRunId) {
    AtomicBoolean cancelled = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Optional<TaskRunEntity> read =
                  Optional.ofNullable(mongoTemplate.findById(taskRunId, TaskRunEntity.class));
              if (cancelled.compareAndSet(false, true)) {
                mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(taskRunId)),
                    new Update().set("status", RunStatus.cancelled).set("phase", RunPhase.completed),
                    TaskRunEntity.class);
              }
              return read;
            })
        .when(spiedTaskRunRepository)
        .findById(taskRunId);
  }

  private Document raw(String taskRunId) {
    return mongoTemplate.execute(
        TaskRunEntity.class,
        collection -> collection.find(new Document("_id", new ObjectId(taskRunId))).first());
  }
}

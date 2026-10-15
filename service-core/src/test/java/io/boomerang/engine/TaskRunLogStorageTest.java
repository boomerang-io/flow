package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunClaim;
import io.boomerang.workflow.ArtifactServiceTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The log a dispatcher stores for a task: fenced on its claim, capped, replaced by a later
 * upload, and served ahead of any live read.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class TaskRunLogStorageTest extends AbstractEngineIntegrationTest {

  private TaskRunEntity task;

  @BeforeEach
  void claimedTask() {
    WorkflowRunEntity run = savedWorkflowRun("trlog-store-wf", RunStatus.running, RunPhase.running);
    task =
        savedTaskRun(
            "trlog-store", TaskType.template, RunStatus.running, RunPhase.running, run.getWorkflowRef(), run.getId());
    RunClaim claim = new RunClaim();
    claim.setBy("d-1");
    claim.setSeq(1L);
    task.setClaim(claim);
    task = taskRunRepository.save(task);
  }

  private static ByteArrayInputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private String served() throws IOException {
    StreamingResponseBody body = taskRunService.streamLog(task.getId());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    body.writeTo(out);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void theStoredLogIsServedAheadOfTheLiveRead() throws IOException {
    taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("step one\nstep two\n"));

    assertThat(served()).isEqualTo("step one\nstep two\n");

    taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("attempt two\n"));

    assertThat(served()).isEqualTo("attempt two\n");
  }

  @Test
  void aSupersededClaimantsLogIsRefusedAndNothingIsStored() {
    assertThatThrownBy(() -> taskRunService.storeLog(task.getId(), Optional.of("d-2"), bytes("stale")))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("TASKRUN_CLAIM_SUPERSEDED"));
  }

  @Test
  void aLogOverTheLimitIsRefusedWhole() {
    Object limit = ReflectionTestUtils.getField(taskRunService, "logMaxBytes");
    ReflectionTestUtils.setField(taskRunService, "logMaxBytes", 8);
    try {
      assertThatThrownBy(
              () -> taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("nine bytes")))
          .isInstanceOfSatisfying(
              BoomerangException.class,
              e -> assertThat(e.getReason()).isEqualTo("TASKRUN_LOG_TOO_LARGE"));
      taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("8 bytes!"));
    } finally {
      ReflectionTestUtils.setField(taskRunService, "logMaxBytes", limit);
    }
  }

  @Test
  void anUnknownTaskRunIsRefused() {
    assertThatThrownBy(() -> taskRunService.storeLog("no-such-task", Optional.of("d-1"), bytes("x")))
        .isInstanceOfSatisfying(
            BoomerangException.class,
            e -> assertThat(e.getReason()).isEqualTo("TASKRUN_INVALID_REFERENCE"));
  }
}

package io.boomerang.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunClaim;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.workflow.ArtifactServiceTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The log a dispatcher stores for a task: fenced on its claim, capped, replaced by a later
 * upload. While the task runs the live read comes first and this attempt's stored copy answers only
 * without one; once it has ended, the stored copy comes first.
 */
@Import(ArtifactServiceTest.InMemoryStoreConfiguration.class)
class TaskRunLogStorageTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherRepository dispatcherRepository;

  private TaskRunEntity task;
  private HttpServer dispatcherRoute;

  @BeforeEach
  void claimedTask() {
    WorkflowRunEntity run = savedWorkflowRun("trlog-store-wf", RunStatus.running, RunPhase.running);
    task =
        savedTaskRun(
            "trlog-store", TaskType.template, RunStatus.running, RunPhase.running, run.getWorkflowRef(), run.getId());
    RunClaim claim = new RunClaim();
    claim.setBy("d-1");
    claim.setAt(new Date(System.currentTimeMillis() - 60_000));
    claim.setSeq(1L);
    task.setClaim(claim);
    task = taskRunRepository.save(task);
  }

  @AfterEach
  void stopDispatcherRoute() {
    if (dispatcherRoute != null) {
      dispatcherRoute.stop(0);
    }
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
  void aRunningTaskWithNoLiveRouteServesTheLatestStoredCopy() throws IOException {
    taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("step one\nstep two\n"));

    assertThat(served()).isEqualTo("step one\nstep two\n");

    taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("attempt two\n"));

    assertThat(served()).isEqualTo("attempt two\n");
  }

  @Test
  void aRunningTaskWithAStoredSnapshotStreamsLive() throws IOException {
    claimByDispatcherServing("live-line\n");
    taskRunService.storeLog(task.getId(), Optional.of(task.getClaim().getBy()), bytes("snapshot\n"));

    assertThat(served()).isEqualTo("live-line\n");
  }

  @Test
  void anEndedTaskServesItsStoredCopyAheadOfTheLiveRead() throws IOException {
    claimByDispatcherServing("live-line\n");
    taskRunService.storeLog(task.getId(), Optional.of(task.getClaim().getBy()), bytes("whole log\n"));
    task.setStatus(RunStatus.succeeded);
    task.setPhase(RunPhase.completed);
    task = taskRunRepository.save(task);

    assertThat(served()).isEqualTo("whole log\n");
  }

  @Test
  void anEarlierAttemptsStoredCopyIsNotServedWhileTheTaskRunsAgain() throws IOException {
    taskRunService.storeLog(task.getId(), Optional.of("d-1"), bytes("attempt one\n"));
    // Requeued and claimed again after that log was stored.
    task.getClaim().setAt(new Date(System.currentTimeMillis() + 60_000));
    task = taskRunRepository.save(task);

    assertThat(served()).isEqualTo(LogClient.NO_SOURCE_MESSAGE);
  }

  // Claims the task for a registered dispatcher whose live route answers with {@code line}.
  private void claimByDispatcherServing(String line) throws IOException {
    dispatcherRoute = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    dispatcherRoute.createContext(
        "/logs/stream",
        exchange -> {
          byte[] body = line.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    dispatcherRoute.start();
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId("trlog-live-" + System.nanoTime());
    dispatcher.setLogsUrl(
        "http://127.0.0.1:" + dispatcherRoute.getAddress().getPort() + "/logs/stream");
    dispatcherRepository.save(dispatcher);
    task.getClaim().setBy(dispatcher.getId());
    task = taskRunRepository.save(task);
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

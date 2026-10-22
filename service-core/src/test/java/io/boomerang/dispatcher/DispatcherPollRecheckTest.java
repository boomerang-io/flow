package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The held poll's re-check interval is {@code flow.dispatcher.poll.recheck-ms}: at its floor, a
 * task that becomes claimable while a poll is held is claimed within that interval, not within the
 * one-second default.
 */
@TestPropertySource(properties = "flow.dispatcher.poll.recheck-ms=100")
class DispatcherPollRecheckTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  @Test
  void aTaskAdmittedMidHoldIsClaimedWithinTheRecheckInterval() throws Exception {
    String dispatcher =
        dispatcherService.register(
            new DispatcherRegistrationRequest(
                "recheck-floor", "recheck-floor.local", List.of("template")));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(dispatcher, null, null, null, null));
      // The poll's first look finds nothing and it is held; the task becomes claimable mid-hold.
      Thread.sleep(150);
      long admittedAt = System.nanoTime();
      String taskId =
          savedTaskRun(
                  "recheck-floor-task",
                  TaskType.template,
                  RunStatus.ready,
                  RunPhase.pending,
                  "recheck-floor-wf",
                  "recheck-floor-wfrun")
              .getId();

      ResponseEntity<List<TaskRun>> response = poll.get(5, TimeUnit.SECONDS);
      long waitedMs = Duration.ofNanos(System.nanoTime() - admittedAt).toMillis();

      assertTrue(containsId(response, taskId), "the held poll must claim the task");
      // One 100 ms re-check plus the claim; the one-second default would hold it about 850 ms.
      assertTrue(waitedMs < 400, "claimed " + waitedMs + " ms after admission, expected < 400");
    } finally {
      executor.shutdownNow();
    }
  }
}

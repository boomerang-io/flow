package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

/**
 * Measures whether the agent long-poll's blocking points - the Mongo claim query and the idle
 * wait between cycles - pin virtual threads on this runtime. A pinned carrier would starve the
 * common pool and defeat running the poll on virtual threads; a clean run confirms the claim path
 * stays unmounted, so the poll can scale past the platform-thread ceiling without a rewrite.
 */
class DispatcherPollerVirtualThreadTest extends AbstractEngineIntegrationTest {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int POLLERS = 200;
  private static final int CYCLES_PER_POLLER = 10;

  @Test
  void claimPathDoesNotPinVirtualThreads() throws Exception {
    // Seed more than a claim page of eligible TaskRuns so findClaimable exercises the real Mongo
    // query and result marshalling, not an empty read.
    WorkflowRunEntity run = savedWorkflowRun("vt-poll-wf", RunStatus.running, RunPhase.running);
    for (int i = 0; i < 40; i++) {
      savedTaskRun(
          "claimable-" + i,
          TaskType.template,
          RunStatus.ready,
          RunPhase.pending,
          run.getWorkflowRef(),
          run.getId());
    }

    // Warm the path outside the recording window so one-time class initialisation cannot be
    // mistaken for a runtime pin.
    runPollCycles(4, 2);

    Path jfr = Files.createTempFile("agent-poller-pinning", ".jfr");
    long pinEvents;
    long claims;
    long elapsedMs;
    try (Recording recording = new Recording()) {
      recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO);
      recording.start();

      long startNanos = System.nanoTime();
      claims = runPollCycles(POLLERS, CYCLES_PER_POLLER);
      elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

      recording.stop();
      recording.dump(jfr);
      pinEvents = reportPinEvents(jfr);
    } finally {
      Files.deleteIfExists(jfr);
      // The seeded tasks stay claimable; clear them so they cannot pollute another test's claim
      // page in the shared context.
      taskRunRepository.deleteByWorkflowRunRef(run.getId());
    }

    LOGGER.info(
        "Poller virtual-thread measurement: {} pollers x {} cycles = {} findClaimable calls in "
            + "{}ms, {} pinning events",
        POLLERS,
        CYCLES_PER_POLLER,
        claims,
        elapsedMs,
        pinEvents);

    assertTrue(claims > 0, "the claim query path actually executed");
    // The claim path must not pin PER OPERATION - that is what would starve carriers under load.
    // JEP 491 (Java 24+) stops synchronized from pinning and driver 5.x checks connections out
    // under a ReentrantLock, so any residual pins are one-time (class init / pool warmup) and must
    // stay a tiny fraction of the poll cycles rather than scaling with them.
    long cycles = (long) POLLERS * CYCLES_PER_POLLER;
    assertTrue(
        pinEvents * 100L < cycles,
        "pinning must stay under 1% of poll cycles (was " + pinEvents + " over " + cycles + ")");
  }

  /**
   * Run {@code pollers} concurrent virtual-thread pollers, each performing {@code cycles} poll
   * cycles of the two real blocking points (claim query, then the idle wait). Return the total
   * number of TaskRuns the claim queries returned.
   */
  private long runPollCycles(int pollers, int cycles) throws InterruptedException {
    AtomicLong claimed = new AtomicLong();
    try (ExecutorService vthreads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int p = 0; p < pollers; p++) {
        vthreads.submit(
            () -> {
              for (int c = 0; c < cycles; c++) {
                claimed.addAndGet(
                    taskRunService.findClaimable(List.of(TaskType.template), 20).size());
                Thread.sleep(20);
              }
              return null;
            });
      }
      vthreads.shutdown();
      assertTrue(
          vthreads.awaitTermination(60, TimeUnit.SECONDS), "all virtual-thread pollers finished");
    }
    return claimed.get();
  }

  /**
   * Count {@code jdk.VirtualThreadPinned} events and log each one's duration and top stack frames,
   * so a non-zero result can be judged as one-time (class init, connection-pool warmup) rather
   * than a per-operation pin.
   */
  private static long reportPinEvents(Path recording) throws Exception {
    try (RecordingFile file = new RecordingFile(recording)) {
      long count = 0;
      while (file.hasMoreEvents()) {
        RecordedEvent event = file.readEvent();
        if (!"jdk.VirtualThreadPinned".equals(event.getEventType().getName())) {
          continue;
        }
        count++;
        StringBuilder frames = new StringBuilder();
        if (event.getStackTrace() != null) {
          event.getStackTrace().getFrames().stream()
              .limit(8)
              .forEach(
                  frame ->
                      frames
                          .append("\n    ")
                          .append(frame.getMethod().getType().getName())
                          .append('.')
                          .append(frame.getMethod().getName()));
        }
        LOGGER.info(
            "Pinning event #{} duration={}ms stack:{}",
            count,
            event.getDuration().toMillis(),
            frames);
      }
      return count;
    }
  }
}

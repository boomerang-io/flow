package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.DatabaseCommands;
import io.boomerang.engine.DatabaseCommands.Recorded;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The held poll's two cadences. The re-check interval is {@code flow.dispatcher.poll.recheck-ms}:
 * at its floor, a task that becomes claimable while a poll is held is claimed within that interval,
 * not within the one-second default. The termination pages run on their own interval, {@code
 * flow.dispatcher.poll.residue-ms} (1 s here, ten re-checks): not on every re-check, and residue
 * that no wake announced still reaches the holder within that interval.
 */
@TestPropertySource(
    properties = {
      "flow.dispatcher.poll.recheck-ms=100",
      "flow.dispatcher.poll.residue-ms=1000"
    })
class DispatcherPollRecheckTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private MongoTemplate mongoTemplate;

  // A task run an earlier test left claimable or held would answer an unfiltered poll, or its
  // termination pages, and the timings would measure that leftover; they end cancelled with no
  // claim, which neither the claim page nor the termination pages pick up.
  @BeforeEach
  void noLeftoverWorkOrResidue() {
    mongoTemplate.updateMulti(
        Query.query(
            new Criteria()
                .orOperator(
                    Criteria.where("status").is(RunStatus.ready).and("phase").is(RunPhase.pending),
                    Criteria.where("claim.by").exists(true))),
        new Update()
            .set("status", RunStatus.cancelled)
            .set("phase", RunPhase.completed)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt"),
        TaskRunEntity.class);
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  private String registerDispatcher(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
  }

  @Test
  void aTaskAdmittedMidHoldIsClaimedWithinTheRecheckInterval() throws Exception {
    String dispatcher = registerDispatcher("recheck-floor");

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

  /**
   * A full dispatcher's {@code limit=0} poll runs no claim page, so every query it makes is a
   * termination page: held 1.5 s - about fifteen re-checks - it runs the two pages on entry and
   * once more when the 1 s residue interval comes round, four finds, where a turn on every re-check
   * would make about thirty.
   */
  @Test
  void theTerminationPagesRunOncePerResidueIntervalNotOnEveryRecheck() throws Exception {
    String dispatcher = registerDispatcher("residue-cadence");

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Recorded<ResponseEntity<List<TaskRun>>>> poll =
          executor.submit(
              () ->
                  DatabaseCommands.record(
                      () -> dispatcherService.getTaskQueue(dispatcher, 0, null, null, null)));
      Thread.sleep(1500);
      executor.shutdownNow();
      Recorded<ResponseEntity<List<TaskRun>>> recorded = poll.get(2, TimeUnit.SECONDS);

      List<String> names =
          recorded.names().stream().map(name -> name.replace(" flowtest_", " ")).toList();
      System.out.printf("DB COMMANDS | limit=0 poll held 1.5 s | %s%n", names);
      assertEquals(HttpStatus.NO_CONTENT, recorded.result().getStatusCode());
      assertEquals(
          4,
          Collections.frequency(names, "find task_runs"),
          "two termination pages on entry and two at the 1 s residue turn: " + names);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * A task the dispatcher holds that ends cancelled without a wake on this instance - the end
   * written by another engine replica - is handed out as a termination order on the next residue
   * turn: not on the next 100 ms re-check, and within the 1 s residue interval of the last turn,
   * which the poll that claimed the task took.
   */
  @Test
  void residueNoWakeAnnouncedIsHandedOutWithinTheResidueInterval() throws Exception {
    String dispatcher = registerDispatcher("residue-unannounced");
    String taskId =
        savedTaskRun(
                "residue-unannounced-task",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                "residue-unannounced-wf",
                "residue-unannounced-wfrun")
            .getId();
    assertTrue(
        containsId(dispatcherService.getTaskQueue(dispatcher, 1, null, null, null), taskId),
        "the dispatcher claims the task");
    long turnTakenAt = System.nanoTime();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(dispatcher, 0, null, null, null));
      Thread.sleep(200);
      // Written straight to the database, as another replica's end would land: no wake here.
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(taskId)),
          new Update().set("status", RunStatus.cancelled).set("phase", RunPhase.completed),
          TaskRunEntity.class);
      long writtenAt = System.nanoTime();

      ResponseEntity<List<TaskRun>> response = poll.get(5, TimeUnit.SECONDS);
      long now = System.nanoTime();
      long sinceWriteMs = Duration.ofNanos(now - writtenAt).toMillis();
      long sinceTurnMs = Duration.ofNanos(now - turnTakenAt).toMillis();

      assertTrue(containsId(response, taskId), "the held poll carries the termination order");
      assertTrue(
          sinceWriteMs >= 300,
          "handed out " + sinceWriteMs + " ms after the write: a re-check took the residue turn");
      assertTrue(
          sinceTurnMs < 1500,
          "handed out " + sinceTurnMs + " ms after the last turn, expected within the 1 s interval");
    } finally {
      executor.shutdownNow();
    }
  }
}

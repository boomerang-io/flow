package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AgentRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * Agent task-queue claim semantics. RED-LINE: flips in E4 (B2/B16 per-document findAndModify
 * claim with ownership fields, Q-129). These tests pass today by demonstrating the current
 * defects (idempotency-audit.md #28/#29): getTaskQueue is find-then-bulk-update, so racing agents
 * can both receive the same ready TaskRun and terminal runs are redelivered on every poll. When
 * E4 lands, invert: exactly one agent receives a ready TaskRun, terminal runs are never redelivered.
 */
class AgentQueueClaimTest extends AbstractEngineIntegrationTest {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int MAX_RACE_ATTEMPTS = 8;

  @Autowired private AgentService agentService;

  private String registerAgent(String name) {
    return agentService.register(
        new AgentRegistrationRequest(name, name + ".local", List.of("template")));
  }

  private TaskRunEntity savedTerminalTaskRun() {
    return savedTaskRun(
        "terminal-" + UUID.randomUUID(),
        TaskType.template,
        RunStatus.cancelled,
        RunPhase.completed,
        "claim-race-wf",
        "claim-race-wfrun");
  }

  private TaskRunEntity savedReadyTaskRun() {
    return savedTaskRun(
        "race-" + UUID.randomUUID(),
        TaskType.template,
        RunStatus.ready,
        RunPhase.pending,
        "claim-race-wf",
        "claim-race-wfrun");
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  // RED-LINE: flips in E4 (B2). Terminal runs (completed/cancelled) match getTaskQueue's find
  // criteria with no delivery bookkeeping, so every agent gets them on every poll (audit #29).
  @Test
  void terminalTaskRunIsRedeliveredToEveryAgentOnEveryPoll() {
    String agentA = registerAgent("terminal-agent-a");
    String agentB = registerAgent("terminal-agent-b");
    String terminalId = savedTerminalTaskRun().getId();

    assertTrue(
        containsId(agentService.getTaskQueue(agentA), terminalId),
        "DEFECT (audit #29): terminal TaskRun expected to be dispatched to agent A");
    assertTrue(
        containsId(agentService.getTaskQueue(agentA), terminalId),
        "DEFECT (audit #29): terminal TaskRun expected to be re-dispatched to agent A");
    assertTrue(
        containsId(agentService.getTaskQueue(agentB), terminalId),
        "DEFECT (audit #29): terminal TaskRun expected to be dispatched to agent B as well");
  }

  // RED-LINE: flips in E4 (B2/B16). getTaskQueue finds first, bulk-updates second, and returns
  // the find result, so the claim loser still dispatches (audit #28/#29). Timing-dependent: a
  // terminal "beacon" run keeps every poll returning immediately (no 30 s long-poll), and we
  // retry synchronized starts up to MAX_RACE_ATTEMPTS.
  @Test
  void twoAgentsCanBothReceiveTheSameReadyTaskRun() throws Exception {
    String agentA = registerAgent("race-agent-a");
    String agentB = registerAgent("race-agent-b");
    savedTerminalTaskRun(); // beacon

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      boolean duplicateDispatchObserved = false;
      for (int attempt = 1; attempt <= MAX_RACE_ATTEMPTS && !duplicateDispatchObserved; attempt++) {
        String raceId = savedReadyTaskRun().getId();
        assertNotNull(raceId);

        CountDownLatch startGun = new CountDownLatch(1);
        Future<ResponseEntity<List<TaskRun>>> resultA =
            pool.submit(
                () -> {
                  startGun.await();
                  return agentService.getTaskQueue(agentA);
                });
        Future<ResponseEntity<List<TaskRun>>> resultB =
            pool.submit(
                () -> {
                  startGun.await();
                  return agentService.getTaskQueue(agentB);
                });
        startGun.countDown();

        boolean aGotIt = containsId(resultA.get(45, TimeUnit.SECONDS), raceId);
        boolean bGotIt = containsId(resultB.get(45, TimeUnit.SECONDS), raceId);
        LOGGER.info(
            "Claim race attempt {}: agentA={}, agentB={} for TaskRun {}",
            attempt,
            aGotIt,
            bGotIt,
            raceId);
        duplicateDispatchObserved = aGotIt && bGotIt;
      }

      assertTrue(
          duplicateDispatchObserved,
          "DEFECT (audit #28/#29): expected BOTH agents to receive the same ready TaskRun via the"
              + " find-then-update race. If E4's claim rework has landed, flip this to assert"
              + " exactly one winner; if it has not, the race window has narrowed - widen the"
              + " attempts rather than deleting this red-line.");
    } finally {
      pool.shutdownNow();
    }
  }
}

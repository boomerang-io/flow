package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Agent task-queue claim semantics: each ready TaskRun is claimed via a per-document
 * Compare-And-Set, so exactly one agent receives it and the claim records ownership (claim block,
 * incremented claim seq, agentRef alias). Terminal runs are not eligible and are never
 * redelivered.
 */
class AgentQueueClaimTest extends AbstractEngineIntegrationTest {

  private static final Logger LOGGER = LogManager.getLogger();

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

  // Terminal runs (completed/cancelled) are not claimable, so a poll never redelivers them. Each
  // poll gets a fresh ready beacon so it returns immediately instead of holding the long poll.
  @Test
  void terminalTaskRunIsNeverDelivered() {
    String agentA = registerAgent("terminal-agent-a");
    String agentB = registerAgent("terminal-agent-b");
    String terminalId = savedTerminalTaskRun().getId();

    for (String agent : List.of(agentA, agentA, agentB)) {
      String beaconId = savedReadyTaskRun().getId();
      ResponseEntity<List<TaskRun>> response = agentService.getTaskQueue(agent);
      assertTrue(containsId(response, beaconId), "poll should have claimed its ready beacon");
      assertFalse(
          containsId(response, terminalId),
          "a terminal TaskRun must never be delivered to any agent");
    }

    TaskRunEntity terminalAfter = taskRunRepository.findById(terminalId).orElseThrow();
    assertEquals(RunStatus.cancelled, terminalAfter.getStatus());
    assertEquals(RunPhase.completed, terminalAfter.getPhase());
  }

  // Two agents polling for the same ready TaskRun: the per-document Compare-And-Set claim admits
  // exactly one winner, which owns the claim block and the incremented claim seq.
  @Test
  void exactlyOneAgentReceivesAReadyTaskRun() throws Exception {
    String agentA = registerAgent("race-agent-a");
    String agentB = registerAgent("race-agent-b");
    String raceId = savedReadyTaskRun().getId();
    assertNotNull(raceId);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
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
      LOGGER.info("Claim race: agentA={}, agentB={} for TaskRun {}", aGotIt, bGotIt, raceId);
      assertTrue(aGotIt ^ bGotIt, "exactly one agent must receive the ready TaskRun");

      TaskRunEntity claimed = taskRunRepository.findById(raceId).orElseThrow();
      assertEquals(RunPhase.queued, claimed.getPhase());
      assertNotNull(claimed.getClaim(), "the winner's claim block must be recorded");
      assertEquals((aGotIt ? agentA : agentB), claimed.getClaim().getBy());
      assertEquals((aGotIt ? agentA : agentB), claimed.getAgentRef());
      assertEquals(1L, claimed.getClaim().getSeq());
    } finally {
      pool.shutdownNow();
    }
  }
}

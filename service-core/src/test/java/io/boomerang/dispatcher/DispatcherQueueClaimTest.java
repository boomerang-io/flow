package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.Date;
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
 * incremented claim seq). Terminal runs are not eligible and are never
 * redelivered.
 */
class DispatcherQueueClaimTest extends AbstractEngineIntegrationTest {

  private static final Logger LOGGER = LogManager.getLogger();

  @Autowired private DispatcherService dispatcherService;

  private String registerAgent(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
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

  // A terminal TaskRun a dispatcher still owns: the claim block survives the completion, which is
  // what marks the executor-side work (a Tekton TaskRun and its pod) as still needing termination.
  private TaskRunEntity savedOwnedTerminalTaskRun(
      TaskType type, RunStatus status, String owner) {
    TaskRunEntity run =
        savedTaskRun(
            "owned-terminal-" + UUID.randomUUID(),
            type,
            status,
            RunPhase.completed,
            "claim-race-wf",
            "claim-race-wfrun");
    RunClaim claim = new RunClaim();
    claim.setBy(owner);
    claim.setAt(new Date());
    claim.setSeq(1L);
    run.setClaim(claim);
    return taskRunRepository.save(run);
  }

  private static TaskRun delivered(ResponseEntity<List<TaskRun>> response, String id) {
    if (response == null || response.getBody() == null) {
      return null;
    }
    return response.getBody().stream().filter(t -> id.equals(t.getId())).findFirst().orElse(null);
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

  // A terminal TaskRun that was never claimed provisioned nothing, so no agent is ever told to
  // terminate it and no poll redelivers it. (A terminal run a dispatcher DOES still own is a
  // different case - see terminationIsDeliveredForCancelledAndTimedOutTaskRuns.) Each poll gets a
  // fresh ready beacon so it returns immediately instead of holding the long poll.
  @Test
  void unclaimedTerminalTaskRunIsNeverDelivered() {
    String agentA = registerAgent("terminal-agent-a");
    String agentB = registerAgent("terminal-agent-b");
    String terminalId = savedTerminalTaskRun().getId();

    for (String agent : List.of(agentA, agentA, agentB)) {
      String beaconId = savedReadyTaskRun().getId();
      ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(agent);
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
                return dispatcherService.getTaskQueue(agentA);
              });
      Future<ResponseEntity<List<TaskRun>>> resultB =
          pool.submit(
              () -> {
                startGun.await();
                return dispatcherService.getTaskQueue(agentB);
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
      assertEquals(1L, claimed.getClaim().getSeq());
    } finally {
      pool.shutdownNow();
    }
  }

  // Both terminal statuses the agent's terminate handler acts on (cancelled, timedout) reach a
  // polling agent of the right type, carrying the completed/cancelled|timedout shape that handler
  // keys off. Without this the agent is never told to kill the pod and it runs on.
  @Test
  void terminationIsDeliveredForCancelledAndTimedOutTaskRuns() {
    String owner = registerAgent("termination-owner");
    String cancelledId =
        savedOwnedTerminalTaskRun(TaskType.template, RunStatus.cancelled, owner).getId();
    String timedOutId =
        savedOwnedTerminalTaskRun(TaskType.template, RunStatus.timedout, owner).getId();

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(owner);

    TaskRun cancelled = delivered(response, cancelledId);
    assertNotNull(cancelled, "a cancelled TaskRun the dispatcher owns must be delivered");
    assertEquals(RunPhase.completed, cancelled.getPhase());
    assertEquals(RunStatus.cancelled, cancelled.getStatus());

    TaskRun timedOut = delivered(response, timedOutId);
    assertNotNull(timedOut, "a timed-out TaskRun the dispatcher owns must be delivered");
    assertEquals(RunPhase.completed, timedOut.getPhase());
    assertEquals(RunStatus.timedout, timedOut.getStatus());

    // The termination claim releases ownership (seq survives) and leaves the terminal record
    // otherwise untouched - a terminal status is never rewritten.
    TaskRunEntity after = taskRunRepository.findById(cancelledId).orElseThrow();
    assertEquals(RunStatus.cancelled, after.getStatus());
    assertEquals(RunPhase.completed, after.getPhase());
    assertNull(after.getClaim().getBy(), "the termination claim must release ownership");
    assertEquals(1L, after.getClaim().getSeq(), "claim seq is never cleared");
  }

  // The termination signal is one agent's job: the released claim means a second poll - by the
  // same agent or another - never sees the run again. This is the v4 defect (terminal runs
  // redelivered to every agent on every poll) that the Compare-And-Set exists to prevent.
  @Test
  void terminationIsDeliveredOnceToOneAgentOnly() {
    String agentA = registerAgent("termination-agent-a");
    String agentB = registerAgent("termination-agent-b");
    String terminalId =
        savedOwnedTerminalTaskRun(TaskType.template, RunStatus.cancelled, agentA).getId();

    ResponseEntity<List<TaskRun>> first = dispatcherService.getTaskQueue(agentA);
    assertNotNull(delivered(first, terminalId), "the first poll must receive the termination");

    for (String agent : List.of(agentA, agentB)) {
      String beaconId = savedReadyTaskRun().getId();
      ResponseEntity<List<TaskRun>> repoll = dispatcherService.getTaskQueue(agent);
      assertTrue(containsId(repoll, beaconId), "poll should have claimed its ready beacon");
      assertFalse(
          containsId(repoll, terminalId), "a claimed termination must never be redelivered");
    }
  }

  // Terminations are routed by the agent's registered task types exactly as executions are: an
  // agent registered for template alone is never handed a custom TaskRun to terminate.
  @Test
  void terminationRespectsTheAgentsRegisteredTaskTypes() {
    String agent = registerAgent("termination-type-agent"); // registers "template" only
    String customId =
        savedOwnedTerminalTaskRun(TaskType.custom, RunStatus.cancelled, agent).getId();
    String beaconId = savedReadyTaskRun().getId();

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(agent);

    assertTrue(containsId(response, beaconId), "poll should have claimed its ready beacon");
    assertFalse(
        containsId(response, customId),
        "a TaskRun of an unregistered type must not be delivered for termination");
    assertNotNull(
        taskRunRepository.findById(customId).orElseThrow().getClaim().getBy(),
        "an unrouted termination keeps its claim for an agent of that type");
  }

  // The parked-mid-retry shape (waiting/pending, still owned) is delivered for termination on the
  // same terms as a terminal run: once, to one agent, and never again - the release re-arms the
  // node for its next attempt, so a redelivery would also be a spurious second kill order.
  @Test
  void aParkedRetryIsDeliveredForTerminationOnceOnly() {
    String agentA = registerAgent("parked-agent-a");
    String agentB = registerAgent("parked-agent-b");
    TaskRunEntity parked =
        savedOwnedTerminalTaskRun(TaskType.template, RunStatus.cancelled, agentA);
    // The shape tryRequeue leaves behind: non-terminal, backed off, claim retained.
    parked.setStatus(RunStatus.waiting);
    parked.setPhase(RunPhase.pending);
    RunRetry retry = new RunRetry();
    retry.setCount(1);
    retry.setAfter(new Date(System.currentTimeMillis() + 600000));
    parked.setRetry(retry);
    taskRunRepository.save(parked);

    ResponseEntity<List<TaskRun>> first = dispatcherService.getTaskQueue(agentA);
    TaskRun dispatched = delivered(first, parked.getId());
    assertNotNull(dispatched, "a parked retry must be dispatched so the old pod is terminated");
    assertEquals(RunPhase.completed, dispatched.getPhase());
    assertEquals(RunStatus.timedout, dispatched.getStatus());

    for (String agent : List.of(agentA, agentB)) {
      String beaconId = savedReadyTaskRun().getId();
      ResponseEntity<List<TaskRun>> repoll = dispatcherService.getTaskQueue(agent);
      assertTrue(containsId(repoll, beaconId), "poll should have claimed its ready beacon");
      assertFalse(
          containsId(repoll, parked.getId()), "a claimed termination must never be redelivered");
    }
  }

  @Test
  void claimResponseCarriesPostClaimPhaseAndOwner() {
    String agent = registerAgent("payload-agent");
    String taskId = savedReadyTaskRun().getId();

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(agent);
    TaskRun claimed =
        response.getBody().stream()
            .filter(t -> taskId.equals(t.getId()))
            .findFirst()
            .orElseThrow();

    // The wire payload the agent receives must reflect the post-claim transition, not the stale
    // pre-claim pending phase the findAndModify pre-image originally held. (The claim block is an
    // internal field and is intentionally not exposed on the public TaskRun model.)
    assertEquals(RunPhase.queued, claimed.getPhase());
  }
}

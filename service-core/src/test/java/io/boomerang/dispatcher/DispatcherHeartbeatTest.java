package io.boomerang.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunClaim;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The heartbeat endpoint (DispatcherControllerV1 PUT /{id}/heartbeat) is what CREATES a TaskRun's
 * lease - {@link io.boomerang.engine.TaskRunService#tryClaim} never sets {@code
 * claim.leaseExpiresAt}, so a dispatcher that never heartbeats opts itself out of
 * WorkflowWatcher.reapExpiredLeases entirely (opt-in by behaviour).
 */
class DispatcherHeartbeatTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private DispatcherRepository dispatcherRepository;

  private String registerAgent(String name) {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(name, name + ".local", List.of("template")));
  }

  private TaskRunEntity claimedTaskRun(String name, String claimedBy) {
    WorkflowRunEntity wfRun = savedWorkflowRun(name + "-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity taskRun =
        savedTaskRun(
            name, TaskType.template, RunStatus.running, RunPhase.running, wfRun.getWorkflowRef(), wfRun.getId());
    RunClaim claim = new RunClaim();
    claim.setBy(claimedBy);
    claim.setAt(new Date());
    claim.setSeq(1L);
    taskRun.setClaim(claim);
    return taskRunRepository.save(taskRun);
  }

  @Test
  void unknownAgentIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> dispatcherService.heartbeat("no-such-agent", List.of("some-id")));
  }

  @Test
  void heartbeatRefreshesTheAgentsLastConnectedDate() {
    String agent = registerAgent("heartbeat-liveness");
    Date before = dispatcherRepository.findById(agent).orElseThrow().getLastConnectedDate();

    // Force a detectable gap: lastConnectedDate is set at registration too.
    dispatcherRepository.save(
        withLastConnected(dispatcherRepository.findById(agent).orElseThrow(), new Date(0)));

    dispatcherService.heartbeat(agent, List.of());

    Date after = dispatcherRepository.findById(agent).orElseThrow().getLastConnectedDate();
    assertNotNull(after);
    assertTrue(after.getTime() > 0, "heartbeat must refresh lastConnectedDate");
  }

  private DispatcherEntity withLastConnected(DispatcherEntity entity, Date date) {
    entity.setLastConnectedDate(date);
    return entity;
  }

  @Test
  void heartbeatRenewsOnlyLeasesTheAgentOwns() {
    String owner = registerAgent("heartbeat-owner");
    String other = registerAgent("heartbeat-other");
    TaskRunEntity owned = claimedTaskRun("owned-by-heartbeat-agent", owner);
    TaskRunEntity notOwned = claimedTaskRun("owned-by-other-agent", other);

    long renewed = dispatcherService.heartbeat(owner, List.of(owned.getId(), notOwned.getId()));

    assertEquals(1, renewed, "only the caller's own claim is renewed");
    TaskRunEntity ownedAfter = taskRunRepository.findById(owned.getId()).orElseThrow();
    assertNotNull(ownedAfter.getClaim().getLeaseExpiresAt(), "the first heartbeat creates the lease");
    TaskRunEntity notOwnedAfter = taskRunRepository.findById(notOwned.getId()).orElseThrow();
    assertNull(
        notOwnedAfter.getClaim().getLeaseExpiresAt(),
        "a heartbeat must never renew a lease it does not own");
  }

  @Test
  void heartbeatWithNoIdsStillRefreshesLivenessAndRenewsNothing() {
    String agent = registerAgent("heartbeat-empty");

    long renewed = dispatcherService.heartbeat(agent, List.of());

    assertEquals(0, renewed);
  }

  // A run that finished between the executor's last poll and its next heartbeat is no longer
  // queued/running - renewLeases must not resurrect a stale claim.leaseExpiresAt on a terminal
  // record.
  @Test
  void renewLeasesSkipsATerminalTaskRun() {
    String owner = registerAgent("heartbeat-terminal-owner");
    TaskRunEntity terminal = claimedTaskRun("terminal-by-the-time-of-heartbeat", owner);
    taskRunRepository.save(
        withPhaseAndStatus(terminal, RunPhase.completed, RunStatus.succeeded));

    long renewed =
        taskRunService.renewLeases(List.of(terminal.getId()), owner, new Date());

    assertEquals(0, renewed, "a terminal TaskRun must never be renewed");
    TaskRunEntity after = taskRunRepository.findById(terminal.getId()).orElseThrow();
    assertNull(after.getClaim().getLeaseExpiresAt());
  }

  private TaskRunEntity withPhaseAndStatus(TaskRunEntity entity, RunPhase phase, RunStatus status) {
    entity.setPhase(phase);
    entity.setStatus(status);
    return entity;
  }
}

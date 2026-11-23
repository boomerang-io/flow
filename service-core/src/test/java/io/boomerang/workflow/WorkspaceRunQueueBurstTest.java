package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A burst of simultaneous submits against a workspace's concurrent-run quota. Nothing is refused
 * for the concurrent limit and nothing is lost: every run is in flight, parked or ended, and the
 * queue drains to the last run. The queue's own cap holds under the burst: the runs left waiting
 * never outnumber it, and the rest are refused.
 */
class WorkspaceRunQueueBurstTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";
  private static final String TASK_SLUG = "run-queue-burst-test-task";
  private static final int BURST = 50;

  private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.ready, RunStatus.running);

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, true);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void aBurstOverTheConcurrentQuotaQueuesWithNothingRefusedOrLostAndDrainsToTheLastRun()
      throws Exception {
    String workspace = workspace("run-queue-burst-open", 4, 0);
    String workflow = "run-queue-burst-open-workflow";
    workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
    String workflowRef = submit(workspace, workflow).getWorkflowRef();

    int refused = submitBurst(workspace, workflow);

    assertEquals(0, refused, "an unlimited queue refuses nothing");
    List<WorkflowRunEntity> runs = runsOf(workflowRef);
    assertEquals(BURST + 1, runs.size(), "every submit left its run");
    long inFlight = runs.stream().filter(r -> IN_FLIGHT.contains(r.getStatus())).count();
    long parked = runs.stream().filter(r -> RunStatus.waiting.equals(r.getStatus())).count();
    assertEquals(BURST + 1, inFlight + parked, "every run is in flight or parked");
    // Racers admitted together may each recount before the others are in flight, so more than the
    // limit can hold a slot; the bound is the burst.
    assertTrue(inFlight >= 4, "at least the limit is in flight, was " + inFlight);

    // Ending the runs in flight, over and over, drains the queue: no run is left waiting.
    awaitEngine("the queue to drain to the last run")
        .untilAsserted(
            () -> {
              List<WorkflowRunEntity> now = runsOf(workflowRef);
              now.stream()
                  .filter(r -> IN_FLIGHT.contains(r.getStatus()))
                  .forEach(r -> workflowRunService.cancel(r.getId()));
              assertEquals(
                  BURST + 1,
                  now.stream().filter(r -> RunPhase.completed.equals(r.getPhase())).count());
            });
  }

  @Test
  void aBurstAgainstAFullWorkspaceLeavesNoMoreWaitingThanTheQueueHolds() throws Exception {
    String workspace = workspace("run-queue-burst-capped", 1, 5);
    String workflow = "run-queue-burst-capped-workflow";
    workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
    // The one slot is taken before the burst, so every racer is bound for the queue.
    WorkflowRun holder = submit(workspace, workflow);
    String workflowRef = holder.getWorkflowRef();

    int refused = submitBurst(workspace, workflow);

    List<WorkflowRunEntity> runs = runsOf(workflowRef);
    long parked = runs.stream().filter(r -> RunStatus.waiting.equals(r.getStatus())).count();
    assertTrue(parked <= 5, "a queue of 5 holds " + parked);
    assertEquals(BURST - parked, refused, "every submit the queue had no place for was refused");
    assertEquals(1 + parked, runs.size(), "a refused submit left no run behind");

    // Racers may all withdraw, each having counted the others; the places they left are free.
    for (long waiting = parked; waiting < 5; waiting++) {
      submit(workspace, workflow);
    }
    BoomerangException full =
        assertThrows(BoomerangException.class, () -> submit(workspace, workflow));
    assertEquals("QUOTA_EXCEEDED", full.getReason());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.getStatus());

    runsOf(workflowRef).stream()
        .filter(r -> RunStatus.waiting.equals(r.getStatus()))
        .forEach(r -> workflowRunService.cancel(r.getId()));
    workflowRunService.cancel(holder.getId());
  }

  /** Fires {@code BURST} submits released together and returns how many the queue refused. */
  private int submitBurst(String workspace, String workflow) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(BURST);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger refused = new AtomicInteger();
    try {
      List<Future<?>> submits =
          IntStream.range(0, BURST)
              .<Future<?>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            establishThreadIdentity();
                            release.await();
                            try {
                              submit(workspace, workflow);
                            } catch (BoomerangException e) {
                              assertEquals("QUOTA_EXCEEDED", e.getReason());
                              assertEquals("Queued runs", e.getArgs()[0]);
                              refused.incrementAndGet();
                            } finally {
                              SecurityContextHolder.clearContext();
                            }
                            return null;
                          }))
              .toList();
      release.countDown();
      for (Future<?> submit : submits) {
        submit.get(120, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    return refused.get();
  }

  private WorkflowRun submit(String workspace, String workflow) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return workflowService.submit(workspace, workflow, request, true);
  }

  private List<WorkflowRunEntity> runsOf(String workflowRef) {
    return workflowRunRepository.findByWorkflowRefAndPhaseIn(
        workflowRef, List.of(RunPhase.values()));
  }

  private String workspace(String name, int concurrent, int queued) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(concurrent);
    quotas.setMaxQueuedRuns(queued);
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }

  /** SecurityContextHolder is thread-local, so each burst thread carries its own identity. */
  private static void establishThreadIdentity() {
    Token principal = new Token(AuthScope.global);
    principal.setPrincipal("run-queue-burst-principal");
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

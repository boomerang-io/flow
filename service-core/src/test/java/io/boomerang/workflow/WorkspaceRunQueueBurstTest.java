package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.LogCapture;
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
import io.boomerang.engine.WorkflowExecutionService;
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
import org.apache.logging.log4j.Level;
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
  private static final int LIMIT = 4;

  private static final Set<RunStatus> IN_FLIGHT = Set.of(RunStatus.ready, RunStatus.running);

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowExecutionService workflowExecutionService;

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

  // Racers admitted together each pass the first count; the recount after admission steps the
  // younger ones back, so a burst ends with the limit in flight. A racer that recounts before an
  // older one is admitted keeps its slot, so one burst may end a little over: of three bursts at
  // least one ends exactly at the limit, which none does once the recount is gone.
  @Test
  void aBurstOverTheConcurrentQuotaQueuesWithNothingRefusedOrLostAndDrainsToTheLastRun()
      throws Exception {
    long fewestInFlight = Long.MAX_VALUE;
    for (int round = 0; round < 3; round++) {
      String workspace = workspace("run-queue-burst-open-" + round, LIMIT, 0);
      String workflow = "run-queue-burst-open-workflow-" + round;
      workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
      String workflowRef = submit(workspace, workflow).getWorkflowRef();

      int refused = submitBurst(workspace, workflow);

      assertEquals(0, refused, "an unlimited queue refuses nothing");
      List<WorkflowRunEntity> runs = runsOf(workflowRef);
      assertEquals(BURST + 1, runs.size(), "every submit left its run");
      long inFlight = inFlight(workflowRef);
      assertEquals(BURST + 1, inFlight + parked(workflowRef), "every run is in flight or parked");
      assertTrue(inFlight >= LIMIT, "at least the limit is in flight, was " + inFlight);
      assertTrue(inFlight <= BURST + 1, "at most the limit and its racers, was " + inFlight);
      fewestInFlight = Math.min(fewestInFlight, inFlight);

      // Settled: each run that ends lets exactly one in, and the limit holds from then on.
      if (inFlight == LIMIT) {
        for (int ended = 1; ended <= 3; ended++) {
          WorkflowRunEntity one =
              runsOf(workflowRef).stream()
                  .filter(r -> IN_FLIGHT.contains(r.getStatus()))
                  .findFirst()
                  .orElseThrow();
          workflowRunService.cancel(one.getId());
          long expectedParked = BURST + 1 - LIMIT - ended;
          awaitEngine("one queued run to take the freed slot")
              .untilAsserted(
                  () -> {
                    assertEquals(LIMIT, inFlight(workflowRef), "exactly the limit in flight");
                    assertEquals(expectedParked, parked(workflowRef));
                  });
        }
      }

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
    assertEquals(LIMIT, fewestInFlight, "a burst ends with the limit in flight");
  }

  private long inFlight(String workflowRef) {
    return runsOf(workflowRef).stream().filter(r -> IN_FLIGHT.contains(r.getStatus())).count();
  }

  private long parked(String workflowRef) {
    return runsOf(workflowRef).stream()
        .filter(r -> RunStatus.waiting.equals(r.getStatus()))
        .count();
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

  // A completion on every instance, and the sweep, may all drain one queue for one free slot. One
  // run takes it; the callers that lose the race are not failures and log none.
  @Test
  void drainsRacingForOneFreeSlotAdmitOneRunAndLogNoFailure() throws Exception {
    String workspace = workspace("run-queue-burst-drains", 1, 0);
    String workflow = "run-queue-burst-drains-workflow";
    workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
    WorkflowRun holder = submit(workspace, workflow);
    String workflowRef = holder.getWorkflowRef();
    for (int i = 0; i < 6; i++) {
      assertEquals(RunStatus.waiting, submit(workspace, workflow).getStatus());
    }
    // The holder ends with no transition published, so only the racing drains below admit.
    WorkflowRunEntity ended = workflowRunRepository.findById(holder.getId()).orElseThrow();
    ended.setStatus(RunStatus.cancelled);
    ended.setPhase(RunPhase.completed);
    workflowRunRepository.save(ended);

    int drains = 8;
    ExecutorService pool = Executors.newFixedThreadPool(drains);
    CountDownLatch release = new CountDownLatch(1);
    try (LogCapture logs = LogCapture.start(Level.INFO, "io.boomerang.engine.WorkflowExecutionService")) {
      List<Future<?>> racing =
          IntStream.range(0, drains)
              .<Future<?>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            release.await();
                            workflowExecutionService.drain(workflowRef);
                            return null;
                          }))
              .toList();
      release.countDown();
      for (Future<?> drain : racing) {
        drain.get(60, TimeUnit.SECONDS);
      }
      assertTrue(
          logs.lines().stream().noneMatch(line -> line.contains("Unable to re-admit")),
          "a lost race is not a failed re-admission");
    } finally {
      pool.shutdownNow();
    }

    List<WorkflowRunEntity> runs = runsOf(workflowRef);
    assertEquals(1, runs.stream().filter(r -> IN_FLIGHT.contains(r.getStatus())).count());
    assertEquals(5, runs.stream().filter(r -> RunStatus.waiting.equals(r.getStatus())).count());
    runs.stream()
        .filter(r -> RunStatus.waiting.equals(r.getStatus()))
        .forEach(r -> workflowRunService.cancel(r.getId()));
    runsOf(workflowRef).stream()
        .filter(r -> IN_FLIGHT.contains(r.getStatus()))
        .forEach(r -> workflowRunService.cancel(r.getId()));
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

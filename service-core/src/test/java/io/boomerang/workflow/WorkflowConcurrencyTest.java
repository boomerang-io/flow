package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ConcurrencyOverflow;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowConcurrency;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.WorkflowWatcher;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A workflow's concurrency setting bounds its runs in flight at admission: a run over the limit is
 * parked for a slot (overflow {@code queue}) and re-admitted when a run of the workflow ends, or
 * ends at once as cancelled with the typed reason (overflow {@code skip}). Simultaneous submits
 * cannot each pass the count: the post-admission recount steps the younger ones back. A workflow
 * without the setting runs as before.
 */
class WorkflowConcurrencyTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "concurrency-test-task";

  private static final int BURST = 6;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkflowRunStateHelper workflowRunStateHelper;
  @Autowired private WorkflowWatcher workflowWatcher;

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    // The workflow's own limit is under test; the workspace quota would refuse the burst first.
    setFeatureSetting("workspaceQuotas", false);
  }

  private String workspace(String name) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    return workspaceService.create(request).getName();
  }

  private void createWorkflow(
      String workspace, String name, Integer limit, ConcurrencyOverflow overflow) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    if (limit != null) {
      WorkflowConcurrency concurrency = new WorkflowConcurrency();
      concurrency.setLimit(limit);
      concurrency.setOverflow(overflow);
      workflow.setConcurrency(concurrency);
    }
    workflowService.create(workspace, workflow);
  }

  private WorkflowRun submit(String workspace, String workflow, boolean start) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return workflowService.submit(workspace, workflow, request, start);
  }

  private WorkflowRunEntity run(String id) {
    return workflowRunRepository.findById(id).orElseThrow();
  }

  @Test
  void aRunOverTheLimitIsParkedAndReadmittedWhenARunOfTheWorkflowEnds() {
    String workspace = workspace("concurrency-queue");
    String workflow = "concurrency-queue-workflow";
    createWorkflow(workspace, workflow, 1, ConcurrencyOverflow.queue);

    WorkflowRun first = submit(workspace, workflow, true);
    WorkflowRun second = submit(workspace, workflow, true);

    assertEquals(RunStatus.running, run(first.getId()).getStatus());
    WorkflowRunEntity parked = run(second.getId());
    assertEquals(RunStatus.waiting, parked.getStatus(), "parked for a slot");
    assertEquals(RunPhase.pending, parked.getPhase());
    assertTrue(parked.getStatusMessage().startsWith("Waiting for a slot"));
    assertEquals(RunStatus.waiting, second.getStatus(), "the submit response says so");
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowRunService.start(second.getId(), Optional.empty()));
    assertEquals("WORKFLOWRUN_INVALID_PHASE", refused.getReason());

    workflowRunService.cancel(first.getId());

    awaitEngine("the parked run to be re-admitted and started")
        .untilAsserted(
            () -> {
              WorkflowRunEntity readmitted = run(second.getId());
              assertEquals(RunStatus.running, readmitted.getStatus());
              assertEquals(RunPhase.running, readmitted.getPhase());
            });
  }

  @Test
  void aRunOverTheLimitIsSkippedAsCancelledWithTheTypedReason() {
    String workspace = workspace("concurrency-skip");
    String workflow = "concurrency-skip-workflow";
    createWorkflow(workspace, workflow, 1, ConcurrencyOverflow.skip);

    WorkflowRun first = submit(workspace, workflow, true);
    WorkflowRun second = submit(workspace, workflow, true);

    assertEquals(RunStatus.running, run(first.getId()).getStatus());
    WorkflowRunEntity skipped = run(second.getId());
    assertEquals(RunStatus.cancelled, skipped.getStatus());
    assertEquals(RunPhase.completed, skipped.getPhase());
    assertEquals(StatusReason.CONCURRENCY_LIMIT, skipped.getStatusReason());
    assertEquals(StatusReason.CONCURRENCY_LIMIT, second.getStatusReason(), "on the public model");
  }

  @Test
  void aBurstOfSimultaneousSubmitsLeavesOnlyTheLimitInFlight() throws Exception {
    String workspace = workspace("concurrency-burst");
    String workflow = "concurrency-burst-workflow";
    createWorkflow(workspace, workflow, 1, ConcurrencyOverflow.queue);

    ExecutorService pool = Executors.newFixedThreadPool(BURST);
    CountDownLatch release = new CountDownLatch(1);
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
                              submit(workspace, workflow, false);
                            } finally {
                              SecurityContextHolder.clearContext();
                            }
                            return null;
                          }))
              .toList();
      release.countDown();
      for (Future<?> submit : submits) {
        submit.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    List<WorkflowRunEntity> runs =
        workflowRunRepository.findByWorkflowRefAndPhaseIn(
            run(submit(workspace, workflow, false).getId()).getWorkflowRef(),
            List.of(RunPhase.values()));
    long inFlight = runs.stream().filter(r -> RunStatus.ready.equals(r.getStatus())).count();
    long parked = runs.stream().filter(r -> RunStatus.waiting.equals(r.getStatus())).count();
    assertEquals(BURST + 1, runs.size());
    assertEquals(1, inFlight, "exactly the limit holds a slot");
    assertEquals(BURST, parked, "the rest are parked");
  }

  @Test
  void theSweepReadmitsAParkedRunWhoseReadmissionWasLost() {
    String workspace = workspace("concurrency-sweep");
    String workflow = "concurrency-sweep-workflow";
    createWorkflow(workspace, workflow, null, null);
    WorkflowRun submitted = submit(workspace, workflow, false);
    assertNotNull(workflowRunStateHelper.tryPark(submitted.getId(), "Waiting for a slot: test"));
    assertEquals(RunStatus.waiting, run(submitted.getId()).getStatus());

    workflowWatcher.readmitParkedRuns();

    awaitEngine("the sweep to re-admit and start the parked run")
        .untilAsserted(() -> assertEquals(RunStatus.running, run(submitted.getId()).getStatus()));
  }

  @Test
  void aWorkflowWithoutTheSettingRunsEverySubmit() {
    String workspace = workspace("concurrency-none");
    String workflow = "concurrency-none-workflow";
    createWorkflow(workspace, workflow, null, null);

    WorkflowRun first = submit(workspace, workflow, true);
    WorkflowRun second = submit(workspace, workflow, true);

    assertEquals(RunStatus.running, run(first.getId()).getStatus());
    assertEquals(RunStatus.running, run(second.getId()).getStatus());
  }

  /** SecurityContextHolder is thread-local, so each burst thread carries its own identity. */
  private static void establishThreadIdentity() {
    Token principal = new Token(AuthScope.global);
    principal.setPrincipal("concurrency-burst-principal");
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

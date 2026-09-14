package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.boomerang.core.model.Token;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A burst of simultaneous submits must not admit more WorkflowRuns than the workspace quota
 * allows (boomerang-io/flow#347): the quota check is a read-then-insert, so every racer can read
 * a count below the limit and all of them pass. Each test fires one burst against a quota of one
 * and asserts on the resulting WorkflowRun documents - the ground truth a racy check would
 * over-populate.
 */
class StandaloneQuotaConcurrentSubmitTest extends AbstractEngineIntegrationTest {

  private static final String QUOTA_FEATURE = "workspaceQuotas";

  private static final String TASK_SLUG = "quota-burst-test-task";

  private static final int BURST = 8;

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkspaceService workspaceService;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @AfterEach
  void resetQuotaFeature() {
    // Shared Testcontainers Mongo: leave the feature off, the state the other test classes seed.
    setFeatureSetting(QUOTA_FEATURE, false);
  }

  @Test
  void aSubmitBurstCannotExceedTheMonthlyRunQuota() throws Exception {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunMonthly(1);
    String workspace = createWorkspace("quota-burst-monthly", quotas);
    String workflow = "quota-burst-monthly-workflow";
    workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
    setFeatureSetting(QUOTA_FEATURE, true);

    int admitted = submitBurst(workspace, workflow);

    assertTrue(
        admitted <= 1, "monthly quota of 1 admitted " + admitted + " of " + BURST + " submits");
    // Simultaneous racers may all withdraw (each counted the others), leaving the slot free -
    // the guarantee is that the limit is never over-filled. A follow-up submit fills exactly
    // the remaining slot, and one more is refused.
    if (admitted == 0) {
      workflowService.submit(workspace, workflow, manualRequest(), false);
    }
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, workflow, manualRequest(), false));
    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals(
        1, persistedRuns(workspace, workflow), "WorkflowRun documents exceed the monthly quota");
  }

  @Test
  void aSubmitBurstCannotExceedTheConcurrentRunQuota() throws Exception {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(1);
    String workspace = createWorkspace("quota-burst-concurrent", quotas);
    String workflow = "quota-burst-concurrent-workflow";
    workflowService.create(workspace, runnableWorkflow(workflow, TASK_SLUG));
    setFeatureSetting(QUOTA_FEATURE, true);

    int admitted = submitBurst(workspace, workflow);

    // Nothing here starts or finishes a run, so every admitted run is still in flight: the
    // number admitted is the concurrency the quota allowed.
    assertTrue(
        admitted <= 1, "concurrent quota of 1 admitted " + admitted + " of " + BURST + " submits");
    if (admitted == 0) {
      workflowService.submit(workspace, workflow, manualRequest(), false);
    }
    BoomerangException refused =
        assertThrows(
            BoomerangException.class,
            () -> workflowService.submit(workspace, workflow, manualRequest(), false));
    assertEquals("QUOTA_EXCEEDED", refused.getReason());
    assertEquals(
        1,
        persistedRuns(workspace, workflow),
        "WorkflowRun documents exceed the concurrent quota");
  }

  /**
   * Fires {@code BURST} submits released together and returns how many were admitted. A rejection
   * counts only when it is the quota refusing (QUOTA_EXCEEDED); any other failure propagates.
   */
  private int submitBurst(String workspace, String workflow) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(BURST);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger admitted = new AtomicInteger();
    try {
      List<Future<?>> submits =
          java.util.stream.IntStream.range(0, BURST)
              .<Future<?>>mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            establishThreadIdentity();
                            release.await();
                            try {
                              workflowService.submit(workspace, workflow, manualRequest(), false);
                              admitted.incrementAndGet();
                            } catch (BoomerangException e) {
                              assertEquals("QUOTA_EXCEEDED", e.getReason());
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
    return admitted.get();
  }

  private static WorkflowSubmitRequest manualRequest() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  /** SecurityContextHolder is thread-local, so each burst thread carries its own identity. */
  private static void establishThreadIdentity() {
    Token principal = new Token(AuthScope.global);
    principal.setPrincipal("quota-burst-principal");
    principal.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "**", List.of("**/**"))));
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal.getPrincipal(), null);
    authentication.setDetails(principal);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private long persistedRuns(String workspace, String workflow) {
    List<String> refs =
        relationshipService.filter(
            io.boomerang.core.enums.RelationshipType.WORKFLOW,
            java.util.Optional.of(List.of(workflow)),
            java.util.Optional.of(io.boomerang.core.enums.RelationshipType.WORKSPACE),
            java.util.Optional.of(List.of(workspace)),
            false);
    assertEquals(1, refs.size(), "expected exactly one workflow ref for " + workflow);
    List<WorkflowRunEntity> runs =
        workflowRunRepository.findByWorkflowRefAndPhaseIn(
            refs.get(0), List.of(RunPhase.values()));
    return runs.size();
  }

  private String createWorkspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name);
    request.setDisplayName(name);
    request.setQuotas(quotas);
    return workspaceService.create(request).getName();
  }
}

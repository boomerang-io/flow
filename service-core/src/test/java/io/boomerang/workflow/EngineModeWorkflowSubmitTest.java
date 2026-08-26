package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.schedule.ScheduleService;
import io.boomerang.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * POST /api/v2/workspace/{workspace}/workflow/{name}/submit is the only HTTP route that starts a
 * WorkflowRun, so until this was fixed a run could only ever be created in engine mode by an
 * already-running run - nothing could start the first one. WorkflowService.internalSubmit
 * called WorkspaceService.getWorkflowMaxDurationForTeam unconditionally, and WorkspaceService is
 * {@code @ConditionalOnFlowMode(STANDALONE)}: in engine mode the injected proxy threw
 * NoSuchBeanDefinitionException at request time. Boot still succeeded, which is exactly why no
 * mode-gating boot test caught it.
 *
 * <p>Engine mode serves the single {@code system} workspace (AM-10, EngineWorkspaceInterceptor),
 * so this drives the real service against it.
 */
@TestPropertySource(properties = "flow.mode=engine")
class EngineModeWorkflowSubmitTest extends AbstractEngineIntegrationTest {

  private static final String SYSTEM_WORKSPACE = "system";

  private static final String TASK_SLUG = "engine-submit-test-task";

  // The platform default in the seeded "workspaces" settings document - what the run-duration
  // ceiling must fall back to with no workspace quota record to read.
  private static final long PLATFORM_DEFAULT_DURATION = 30L;

  @Autowired private WorkflowService workflowService;
  @Autowired private ApplicationContext context;

  @BeforeEach
  void seedFixtures() {
    seedRelationshipRoot();
    seedTeamQuotaSettings();
    seedTaskSettings();
    // ParamLayerService reads both parameter feature flags on every submit.
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    seedSystemWorkspaceNode();
  }

  @Test
  void theQuotaAndScheduleBeansAreAbsentInEngineMode() {
    // Guards the premise: if either bean were present the submit assertions below would pass for
    // the wrong reason.
    assertTrue(context.getBeansOfType(WorkspaceService.class).isEmpty());
    assertTrue(context.getBeansOfType(ScheduleService.class).isEmpty());
  }

  @Test
  void submittingAWorkflowRunSucceedsInEngineMode() {
    createWorkflow("engine-submit-succeeds");

    WorkflowRun run = submit("engine-submit-succeeds");

    assertNotNull(run);
    assertNotNull(run.getId());
    // Admitted (queued but not started) - not `invalid`, which is what a rejected DAG leaves.
    assertEquals(RunStatus.ready, run.getStatus());
  }

  @Test
  void theRunDurationCeilingFallsBackToThePlatformDefaultInEngineMode() {
    createWorkflow("engine-submit-timeout-default");

    WorkflowRun run = submit("engine-submit-timeout-default");

    assertEquals(PLATFORM_DEFAULT_DURATION, run.getTimeout());
  }

  @Test
  void aRequestedTimeoutBelowTheCeilingIsStillHonouredInEngineMode() {
    createWorkflow("engine-submit-timeout-request");

    WorkflowSubmitRequest request = newSubmitRequest();
    request.setTimeout(5L);
    WorkflowRun run =
        workflowService.submit(
            SYSTEM_WORKSPACE, "engine-submit-timeout-request", request, false);

    assertEquals(5L, run.getTimeout());
  }

  @Test
  void deletingAWorkflowSucceedsInEngineModeDespiteNoScheduleService() {
    createWorkflow("engine-delete-no-schedules");

    workflowService.delete(SYSTEM_WORKSPACE, "engine-delete-no-schedules");

    assertTrue(
        workflowService
            .query(
                SYSTEM_WORKSPACE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(List.of("engine-delete-no-schedules")))
            .getContent()
            .isEmpty());
  }

  private void createWorkflow(String name) {
    workflowService.create(SYSTEM_WORKSPACE, runnableWorkflow(name, TASK_SLUG));
  }

  private WorkflowRun submit(String name) {
    return workflowService.submit(SYSTEM_WORKSPACE, name, newSubmitRequest(), false);
  }

  private static WorkflowSubmitRequest newSubmitRequest() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  // Engine mode has no workspace CRUD, so the `system` workspace's relationship node is seeded by
  // changeunit _0003__SeedSystemWorkspace rather than by any service - the loader does not run
  // against this Testcontainers Mongo, so stand up the same shape directly: a workspace node plus
  // the root:root --contains--> edge every anchored walk starts from.
  private void seedSystemWorkspaceNode() {
    if (relationshipService.doesSlugOrRefExistForType(
        RelationshipType.WORKSPACE, SYSTEM_WORKSPACE)) {
      return;
    }
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.CONTAINS,
        RelationshipType.WORKSPACE,
        SYSTEM_WORKSPACE,
        SYSTEM_WORKSPACE,
        Optional.empty(),
        Optional.empty());
  }

}

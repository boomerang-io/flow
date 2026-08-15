package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.core.EngineRunScopeResolver;
import io.boomerang.core.RunScopeResolver;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.api.IntegrationControllerV2;
import io.boomerang.api.ProfileControllerV2;
import io.boomerang.api.WorkspaceActionControllerV2;
import io.boomerang.api.WorkspaceControllerV2;
import io.boomerang.api.WorkspaceInsightsControllerV2;
import io.boomerang.api.WorkspaceScheduleControllerV2;
import io.boomerang.api.WorkspaceTaskControllerV2;
import io.boomerang.api.WorkspaceWorkflowControllerV2;
import io.boomerang.api.WorkspaceWorkflowRunControllerV2;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.WorkflowRunService;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Companion to {@link FlowModeGatingTest}: same boot test with {@code flow.mode=engine}. See that
 * class for the overall rationale.
 *
 * <p>E8: extends the H6 coverage to the two-mode matrix (re-ruled 2026-08-15) - {@code workspace}
 * (DD-01/J1), {@code schedule} (ruling I2) and their dependent api controllers are absent in
 * engine mode, while {@code workflow}/{@code engine}/{@code dispatcher} stay present per the mode
 * matrix (consolidation-proposal §4).
 *
 * <p>E10-prep (J1/H7): the workspace-scoped RUN/WORKFLOW surface
 * (Workflow/WorkflowRun/Task/Action ControllerV2) now serves in engine mode too, via {@link
 * RunScopeResolver} - see {@link #workspaceScopedRunSurfaceIsPresentInEngineMode()}. Workspace
 * MANAGEMENT (WorkspaceControllerV2 itself, insights, profile, schedules) stays absent - that
 * surface is genuinely workspace-domain (member CRUD, quotas, insights), not run/request scoping.
 */
@TestPropertySource(properties = "flow.mode=engine")
class FlowModeGatingEngineModeTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationContext context;

  @Autowired private RunScopeResolver runScopeResolver;

  @Test
  void integrationsBeanIsAbsentInEngineMode() {
    assertTrue(context.getBeansOfType(IntegrationControllerV2.class).isEmpty());
  }

  @Test
  void coreEngineBeansAreStillPresentInEngineMode() {
    assertFalse(context.getBeansOfType(WorkflowRunService.class).isEmpty());
    assertFalse(context.getBeansOfType(DispatcherService.class).isEmpty());
  }

  @Test
  void workflowBeanIsStillPresentInEngineMode() {
    assertFalse(context.getBeansOfType(WorkflowService.class).isEmpty());
  }

  @Test
  void workspaceBeansAreAbsentInEngineMode() {
    assertTrue(context.getBeansOfType(WorkspaceService.class).isEmpty());
  }

  @Test
  void scheduleBeansAreAbsentInEngineMode() {
    assertTrue(context.getBeansOfType(ScheduleWatcher.class).isEmpty());
  }

  @Test
  void workspaceManagementApiControllersAreAbsentInEngineMode() {
    assertTrue(context.getBeansOfType(WorkspaceControllerV2.class).isEmpty());
    assertTrue(context.getBeansOfType(WorkspaceScheduleControllerV2.class).isEmpty());
    assertTrue(context.getBeansOfType(WorkspaceInsightsControllerV2.class).isEmpty());
    assertTrue(context.getBeansOfType(ProfileControllerV2.class).isEmpty());
  }

  @Test
  void workspaceScopedRunSurfaceIsPresentInEngineMode() {
    assertFalse(context.getBeansOfType(WorkspaceWorkflowControllerV2.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkspaceWorkflowRunControllerV2.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkspaceTaskControllerV2.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkspaceActionControllerV2.class).isEmpty());
  }

  @Test
  void runScopeResolverIsTheEngineSingleAnchorImplementation() {
    assertFalse(context.getBeansOfType(EngineRunScopeResolver.class).isEmpty());
    // Any {team}/{workspace} path value resolves to the single implicit "default" anchor (J1).
    assertEquals("default", runScopeResolver.resolve("some-arbitrary-team"));
    assertEquals("default", runScopeResolver.resolve("another-team"));
    // Authorization-shaped membership checks always pass - no identity/membership graph exists.
    assertTrue(runScopeResolver.checkMembership("some-arbitrary-team"));
    // A ref never linked into the default anchor is correctly absent (not a blanket true).
    assertFalse(
        runScopeResolver.checkInScope(
            RelationshipType.WORKFLOW, "never-linked-ref", "some-arbitrary-team"));
    // filterInScope stays real: an unlinked ref is filtered out regardless of the team value.
    assertTrue(
        runScopeResolver
            .filterInScope(
                RelationshipType.WORKFLOW,
                Optional.of(List.of("never-linked-ref")),
                "some-arbitrary-team",
                false)
            .isEmpty());
  }
}

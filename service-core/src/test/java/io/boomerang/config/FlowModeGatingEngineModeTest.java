package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.api.IntegrationControllerV2;
import io.boomerang.api.WorkspaceControllerV2;
import io.boomerang.api.WorkspaceScheduleControllerV2;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.WorkflowRunService;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Companion to {@link FlowModeGatingTest}: same boot test with {@code flow.mode=engine}. See that
 * class for the overall rationale.
 *
 * <p>E8: extends the H6 coverage to the full mode matrix - {@code workspace} (DD-01/J1),
 * {@code schedule} (ruling I2) and their dependent api controllers are absent in engine mode,
 * while {@code workflow}/{@code engine}/{@code dispatcher} stay present per the mode matrix
 * (consolidation-proposal §4).
 */
@TestPropertySource(properties = "flow.mode=engine")
class FlowModeGatingEngineModeTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationContext context;

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
  void workspaceDependentApiControllersAreAbsentInEngineMode() {
    assertTrue(context.getBeansOfType(WorkspaceControllerV2.class).isEmpty());
    assertTrue(context.getBeansOfType(WorkspaceScheduleControllerV2.class).isEmpty());
  }
}

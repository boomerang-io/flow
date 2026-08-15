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
 * Third leg of the H6/E8 mode-gating boot-test trio: {@code flow.mode=standalone}. Per the mode
 * matrix (consolidation-proposal §4), standalone keeps {@code schedule} (unlike engine mode -
 * ruling I2) but drops {@code workspace} (DD-01/J1) and {@code integrations} same as engine mode.
 * Own cached context, same pattern as {@link FlowModeGatingEngineModeTest} / {@code
 * DispatcherAuthTest}.
 */
@TestPropertySource(properties = "flow.mode=standalone")
class FlowModeGatingStandaloneModeTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void integrationsBeanIsAbsentInStandaloneMode() {
    assertTrue(context.getBeansOfType(IntegrationControllerV2.class).isEmpty());
  }

  @Test
  void workspaceBeansAreAbsentInStandaloneMode() {
    assertTrue(context.getBeansOfType(WorkspaceService.class).isEmpty());
  }

  @Test
  void workspaceDependentApiControllerIsAbsentInStandaloneMode() {
    assertTrue(context.getBeansOfType(WorkspaceControllerV2.class).isEmpty());
  }

  @Test
  void scheduleBeansArePresentInStandaloneMode() {
    assertFalse(context.getBeansOfType(ScheduleWatcher.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkspaceScheduleControllerV2.class).isEmpty());
  }

  @Test
  void coreEngineBeansAreStillPresentInStandaloneMode() {
    assertFalse(context.getBeansOfType(WorkflowRunService.class).isEmpty());
    assertFalse(context.getBeansOfType(DispatcherService.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkflowService.class).isEmpty());
  }
}

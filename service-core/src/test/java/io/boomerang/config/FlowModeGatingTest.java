package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.api.IntegrationControllerV2;
import io.boomerang.api.WorkspaceControllerV2;
import io.boomerang.api.WorkspaceScheduleControllerV2;
import io.boomerang.core.security.EngineWorkspaceInterceptorConfiguration;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.engine.WorkflowRunService;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * H6/E8 boot test proving the {@code flow.mode} gating mechanism actually gates a bean, for the
 * default mode - {@code flow.mode} unset/blank resolves to {@link FlowMode#STANDALONE} (re-ruled
 * 2026-08-15: the old three-mode list collapsed to two, FULL merged into STANDALONE). Standalone
 * is the complete self-contained product, so every module root - {@code integrations}, {@code
 * workspace}, {@code schedule} and their dependent api controllers - is present, alongside the
 * unconditional engine/dispatcher/workflow beans.
 *
 * <p>Companion: {@link FlowModeGatingEngineModeTest} (flow.mode=engine, its own cached context)
 * asserts the opposite - those module roots absent, only the engine/dispatcher/workflow surface
 * present. Two cached contexts total, same pattern as {@code DispatcherAuthTest}. (There used to
 * be a third leg for the old laptop-mode "standalone" - that mode no longer exists; running the
 * product with security off is just standalone configured that way, not a separate mode, so that
 * test folded into this one.)
 */
class FlowModeGatingTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void integrationsBeanIsPresentByDefault() {
    assertFalse(context.getBeansOfType(IntegrationControllerV2.class).isEmpty());
  }

  @Test
  void workspaceAndScheduleBeansArePresentByDefault() {
    assertFalse(context.getBeansOfType(WorkspaceService.class).isEmpty());
    assertFalse(context.getBeansOfType(ScheduleWatcher.class).isEmpty());
  }

  @Test
  void workspaceDependentApiControllersArePresentByDefault() {
    assertFalse(context.getBeansOfType(WorkspaceControllerV2.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkspaceScheduleControllerV2.class).isEmpty());
  }

  @Test
  void coreEngineBeansArePresentByDefault() {
    assertFalse(context.getBeansOfType(WorkflowRunService.class).isEmpty());
    assertFalse(context.getBeansOfType(DispatcherService.class).isEmpty());
    assertFalse(context.getBeansOfType(WorkflowService.class).isEmpty());
  }

  @Test
  void engineWorkspaceInterceptorIsAbsentInStandaloneMode() {
    assertTrue(
        context.getBeansOfType(EngineWorkspaceInterceptorConfiguration.class).isEmpty());
  }
}

package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.api.IntegrationControllerV2;
import io.boomerang.schedule.ScheduleWatcher;
import io.boomerang.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * H6 boot test proving the {@code flow.mode} gating mechanism actually gates a bean.
 * {@code io.boomerang.integrations} was the first mode-gated module root (full-mode-only per the
 * mode matrix; E8 added {@code workspace} and {@code schedule} - see {@link
 * FlowModeGatingEngineModeTest} and {@link FlowModeGatingStandaloneModeTest}). This asserts it's
 * present by default (flow.mode unset -> {@link FlowMode#FULL}) and absent when {@code
 * flow.mode=engine}, while unconditional core engine beans stay present in both.
 *
 * <p>Three context configurations: this class (default/full, sharing the suite's cached default
 * context), {@link FlowModeGatingEngineModeTest} (flow.mode=engine, its own cached context) and
 * {@link FlowModeGatingStandaloneModeTest} (flow.mode=standalone, its own cached context) - same
 * pattern as {@code DispatcherAuthTest}.
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
}

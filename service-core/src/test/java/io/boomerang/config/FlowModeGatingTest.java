package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.IntegrationControllerV2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * H6 boot test proving the {@code flow.mode} gating mechanism actually gates a bean.
 * {@code io.boomerang.integrations} is the only mode-gated module root today (full-mode-only per
 * the mode matrix); this asserts it's present by default (flow.mode unset -> {@link
 * FlowMode#FULL}) and absent when {@code flow.mode=engine}, while unconditional core engine beans
 * stay present in both.
 *
 * <p>Two context configurations only: this class (default/full, sharing the suite's cached
 * default context) and its companion {@link FlowModeGatingEngineModeTest} (flow.mode=engine, its
 * own cached context - same pattern as {@code DispatcherAuthTest}).
 */
class FlowModeGatingTest extends AbstractEngineIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  void integrationsBeanIsPresentByDefault() {
    assertFalse(context.getBeansOfType(IntegrationControllerV2.class).isEmpty());
  }
}

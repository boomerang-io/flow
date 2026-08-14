package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.dispatcher.DispatcherService;
import io.boomerang.engine.WorkflowRunService;
import io.boomerang.api.IntegrationControllerV2;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Companion to {@link FlowModeGatingTest}: same boot test with {@code flow.mode=engine}. See that
 * class for the overall rationale.
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
}

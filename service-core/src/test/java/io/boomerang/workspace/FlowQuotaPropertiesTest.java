package io.boomerang.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The quota subsystem's mode-derived default, mirroring {@code FlowSecurityProperties}: quotas live
 * on the standalone-only WorkspaceService, so engine mode must resolve to off by default.
 */
class FlowQuotaPropertiesTest {

  @Test
  void quotasDefaultOnWhenFlowModeIsUnset() {
    assertTrue(FlowQuotaProperties.isQuotasEnabled(new MockEnvironment()));
  }

  @Test
  void quotasDefaultOnInStandaloneMode() {
    assertTrue(
        FlowQuotaProperties.isQuotasEnabled(
            new MockEnvironment().withProperty("flow.mode", "standalone")));
  }

  @Test
  void quotasDefaultOffInEngineMode() {
    assertFalse(
        FlowQuotaProperties.isQuotasEnabled(
            new MockEnvironment().withProperty("flow.mode", "engine")));
  }

  @Test
  void anExplicitPropertyOverridesTheModeDerivedDefault() {
    assertFalse(
        FlowQuotaProperties.isQuotasEnabled(
            new MockEnvironment()
                .withProperty("flow.mode", "standalone")
                .withProperty("flow.quotas.enabled", "false")));
  }
}

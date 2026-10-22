package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@code flow.security.enabled} defaults to on; only an explicit false turns security off. */
class FlowSecurityPropertiesTest {

  @Test
  void securityIsOnWhenUnset() {
    assertTrue(FlowSecurityProperties.isSecurityEnabled(new MockEnvironment()));
  }

  @Test
  void securityIsOffOnlyWhenSetFalse() {
    assertFalse(
        FlowSecurityProperties.isSecurityEnabled(
            new MockEnvironment().withProperty("flow.security.enabled", "false")));
    assertTrue(
        FlowSecurityProperties.isSecurityEnabled(
            new MockEnvironment().withProperty("flow.security.enabled", "true")));
  }
}

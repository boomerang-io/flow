package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@code flow.workspace.single} is off unless set to true. */
class FlowWorkspacePropertiesTest {

  @Test
  void singleIsOffWhenUnset() {
    assertFalse(FlowWorkspaceProperties.isSingle(new MockEnvironment()));
  }

  @Test
  void singleIsOnWhenSetTrue() {
    assertTrue(
        FlowWorkspaceProperties.isSingle(
            new MockEnvironment().withProperty("flow.workspace.single", "true")));
  }

  @Test
  void singleIsOffWhenSetFalse() {
    assertFalse(
        FlowWorkspaceProperties.isSingle(
            new MockEnvironment().withProperty("flow.workspace.single", "false")));
  }
}

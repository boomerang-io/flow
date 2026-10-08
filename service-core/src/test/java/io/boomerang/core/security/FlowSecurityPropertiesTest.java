package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Only a secured engine accepts nothing but Flow-minted tokens. */
class FlowSecurityPropertiesTest {

  @Test
  void aSecuredEngineIsTokenOnly() {
    assertThat(
            FlowSecurityProperties.isTokenOnly(
                new MockEnvironment()
                    .withProperty("flow.mode", "engine")
                    .withProperty("flow.security.enabled", "true")))
        .isTrue();
  }

  @Test
  void anEngineWithSecurityOffIsNotTokenOnly() {
    assertThat(
            FlowSecurityProperties.isTokenOnly(
                new MockEnvironment().withProperty("flow.mode", "engine")))
        .isFalse();
  }

  @Test
  void securedStandaloneKeepsEverySignInPath() {
    assertThat(
            FlowSecurityProperties.isTokenOnly(
                new MockEnvironment()
                    .withProperty("flow.mode", "standalone")
                    .withProperty("flow.security.enabled", "true")))
        .isFalse();
  }
}

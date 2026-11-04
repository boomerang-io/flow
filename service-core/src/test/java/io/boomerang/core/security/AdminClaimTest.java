package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminClaimTest {

  private static JWTClaimsSet claims(String name, Object value) {
    return new JWTClaimsSet.Builder().claim(name, value).build();
  }

  @Test
  void anUnconfiguredClaimGrantsNothingAndSaysNothing() {
    AdminClaim claim = new AdminClaim("", List.of(), true);

    assertThat(claim.configured()).isFalse();
    assertThat(claim.evaluateVerified(claims("groups", List.of("flow-admins")))).isNull();
    assertThat(claim.evaluateForwarded(claims("groups", List.of("flow-admins")))).isNull();
  }

  @Test
  void aNameWithoutValuesIsNotConfigured() {
    assertThat(new AdminClaim("groups", List.of(" "), false).configured()).isFalse();
  }

  @Test
  void anArrayClaimHoldingAnyConfiguredValueGrantsAdmin() {
    AdminClaim claim = new AdminClaim("groups", List.of("flow-admins", "ops"), false);

    assertThat(claim.evaluateVerified(claims("groups", List.of("staff", "ops")))).isTrue();
    assertThat(claim.evaluateVerified(claims("groups", List.of("staff")))).isFalse();
  }

  @Test
  void aStringClaimEqualToAConfiguredValueGrantsAdmin() {
    AdminClaim claim = new AdminClaim("role", List.of("admin"), false);

    assertThat(claim.evaluateVerified(claims("role", "admin"))).isTrue();
    assertThat(claim.evaluateVerified(claims("role", "Admin"))).isFalse();
    assertThat(claim.evaluateVerified(claims("role", "administrator"))).isFalse();
  }

  @Test
  void aTokenWithoutTheClaimIsNotAdmin() {
    AdminClaim claim = new AdminClaim("groups", List.of("flow-admins"), false);

    assertThat(claim.evaluateVerified(new JWTClaimsSet.Builder().subject("x").build())).isFalse();
  }

  @Test
  void aForwardedTokenIsReadOnlyWhenTheProxyIsTrusted() {
    JWTClaimsSet token = claims("groups", List.of("flow-admins"));

    assertThat(new AdminClaim("groups", List.of("flow-admins"), false).evaluateForwarded(token)).isNull();
    assertThat(new AdminClaim("groups", List.of("flow-admins"), true).evaluateForwarded(token)).isTrue();
  }
}

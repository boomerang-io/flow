package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link IdentityService} is now read under the invariant that an identity is ALWAYS established
 * for a served request - by {@code AuthenticationFilter} when {@code flow.security.enabled=true},
 * and by {@code UnauthenticatedGlobalAuthenticationFilter} when it is {@code false} (see {@link
 * UnauthenticatedGlobalAuthenticationFilterTest}, which proves the security-off half).
 *
 * <p>These tests therefore cover the two things this class itself is responsible for: reporting a
 * resolved identity faithfully, and leaving {@link IdentityService#getCurrentIdentity()} as the
 * honest raw accessor that still answers {@code null} for a context no filter ever touched (the
 * {@code shouldNotFilter} / {@code permitAll} routes under security-enabled, which {@code
 * SecurityInterceptor} relies on to answer its AuthN/AuthZ-mismatch 401).
 */
class IdentityServiceTest {

  private final IdentityService identityService = new IdentityService();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName(
      "getCurrentIdentity() stays the honest raw accessor - null for a context no filter touched")
  void noTokenOnSecurityContextReturnsNullIdentity() {
    SecurityContextHolder.clearContext();

    assertNull(identityService.getCurrentIdentity());
  }

  @Test
  @DisplayName("An Authentication whose details is not a Token is treated as no identity")
  void nonTokenDetailsIsTreatedAsNoIdentity() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("someone", null);
    authentication.setDetails("not-a-token");
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertNull(identityService.getCurrentIdentity());
  }

  @Test
  @DisplayName("A resolved token is reported as-is, principal and scope included")
  void resolvedTokenIsReturnedAsIs() {
    Token token = new Token(AuthScope.user);
    token.setPrincipal("user-1");

    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("user-1", null);
    authentication.setDetails(token);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertEquals(token, identityService.getCurrentIdentity());
    assertEquals("user-1", identityService.getCurrentPrincipal());
    assertEquals(AuthScope.user, identityService.getCurrentScope());
  }

  @Test
  @DisplayName(
      "The synthetic security-off identity reports principal 'system' and global scope, so no"
          + " caller has to invent a meaning for 'nobody is here'")
  void unauthenticatedGlobalTokenReportsSystemPrincipalAndGlobalScope() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(UnauthenticatedGlobalToken.PRINCIPAL, null);
    authentication.setDetails(new UnauthenticatedGlobalToken());
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertNotNull(identityService.getCurrentIdentity());
    assertEquals("system", identityService.getCurrentPrincipal());
    assertEquals(AuthScope.global, identityService.getCurrentScope());
  }
}

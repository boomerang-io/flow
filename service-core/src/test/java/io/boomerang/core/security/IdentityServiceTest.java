package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Covers the {@code flow.security.enabled=false} case directly: with no {@code
 * AuthenticationFilter} ever running, nothing ever attaches a {@link Token} to the
 * SecurityContext, so {@link IdentityService#getCurrentIdentity()} returns {@code null}. This
 * verifies {@link IdentityService#getCurrentPrincipal()} and {@link
 * IdentityService#getCurrentScope()} report that honestly (null) instead of NPE-ing, and that the
 * resolved-identity path is unaffected.
 */
class IdentityServiceTest {

  private final IdentityService identityService = new IdentityService();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noTokenOnSecurityContextReturnsNullIdentity() {
    SecurityContextHolder.clearContext();

    assertNull(identityService.getCurrentIdentity());
  }

  @Test
  void noTokenOnSecurityContextReturnsNullPrincipalNotNpe() {
    SecurityContextHolder.clearContext();

    assertNull(identityService.getCurrentPrincipal());
  }

  @Test
  void noTokenOnSecurityContextReturnsNullScopeNotNpe() {
    SecurityContextHolder.clearContext();

    assertNull(identityService.getCurrentScope());
  }

  @Test
  void noAuthenticationAtAllReturnsNullPrincipalAndScope() {
    // SecurityContextHolder.getContext() is never null (an empty context is created lazily), but
    // there is no Authentication at all - a step further than the details-not-a-Token case above.
    SecurityContextHolder.clearContext();
    SecurityContextHolder.getContext().setAuthentication(null);

    assertNull(identityService.getCurrentPrincipal());
    assertNull(identityService.getCurrentScope());
  }

  @Test
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
}

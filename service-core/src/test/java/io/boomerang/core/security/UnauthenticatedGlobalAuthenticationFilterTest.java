package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.core.audit.AuditActor;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.enums.TokenActorKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The security-off half of the "an identity is ALWAYS established" invariant.
 *
 * <p>Before this filter existed, {@code flow.security.enabled=false} left the SecurityContext empty
 * and every consumer invented its own meaning for "nobody is here" - {@code
 * RelationshipService.check()} allowed unscoped, {@code filter()} anchored at {@code ROOT}, {@code
 * ActionService} bypassed approver-group membership, and {@code AuditInterceptor} threw
 * inside {@code new AuditActor(token)} so <b>no audit record was written at all</b>. Those
 * no-principal branches have been deleted; this test pins the invariant that makes their deletion
 * safe, rather than re-testing each removed branch.
 */
class UnauthenticatedGlobalAuthenticationFilterTest {

  private final UnauthenticatedGlobalAuthenticationFilter filter =
      new UnauthenticatedGlobalAuthenticationFilter();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private Token runFilter() throws Exception {
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v2/profile"),
        new MockHttpServletResponse(),
        new MockFilterChain());
    return new IdentityService().getCurrentIdentity();
  }

  @Test
  @DisplayName(
      "THE INVARIANT: after the filter runs, getCurrentIdentity() is never null - so the deleted"
          + " no-principal branches in check()/filter()/UserService/IdentityService are unreachable")
  void identityIsAlwaysEstablished() throws Exception {
    SecurityContextHolder.clearContext();

    Token identity = runFilter();

    assertNotNull(identity, "security-off must still establish an identity");
    assertNotNull(identity.getPrincipal());
    assertNotNull(identity.getType());
  }

  @Test
  @DisplayName("The established identity is the clearly-identifiable 'system' global actor")
  void identityIsTheSystemGlobalActor() throws Exception {
    Token identity = runFilter();

    assertEquals(UnauthenticatedGlobalToken.class, identity.getClass());
    assertEquals("system", identity.getPrincipal());
    assertEquals(AuthScope.global, identity.getType());
    assertEquals(TokenActorKind.SERVICE, identity.getActorKind());
    assertTrue(identity.isValid());
  }

  @Test
  @DisplayName(
      "It is never persisted and carries no token id, so audit records cannot cite a token that"
          + " does not exist and logout() cannot try to revoke it")
  void syntheticTokenHasNoPersistedIdentifier() throws Exception {
    assertEquals(null, runFilter().getId());
  }

  @Test
  @DisplayName(
      "Its grant reproduces the ceiling the deleted TokenService.resolveGrantCeiling() null branch"
          + " handed out (**/**), so security-off keeps behaving as unrestricted")
  void grantIsGlobalUnrestricted() throws Exception {
    Token identity = runFilter();

    assertEquals(1, identity.getPermissions().size());
    assertEquals(PermissionScope.global, identity.getPermissions().get(0).getScope());
    assertEquals("**", identity.getPermissions().get(0).getPrincipal());
    assertEquals("**/**", identity.getPermissions().get(0).getActions().get(0));
  }

  @Test
  @DisplayName(
      "An identity established earlier in the chain wins - the /api/v1/** dispatcher chain runs"
          + " first and its real token must not be overwritten by the synthetic one")
  void existingIdentityIsNotOverwritten() throws Exception {
    Token real = new Token(AuthScope.key);
    real.setPrincipal("dispatcher-1");
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("dispatcher-1", null);
    authentication.setDetails(real);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertSame(real, runFilter());
  }

  /**
   * The headline defect this change fixed: before this filter, no identity meant no audit trail at
   * all on a security-off instance. The synthetic token resolves to the clearly-badged {@code
   * system} audit actor.
   */
  @Test
  @DisplayName("Audit events on a security-off instance record 'system' as the actor")
  void auditActorResolvesToSystem() throws Exception {
    AuditActor actor = AuditActor.from(runFilter());

    assertNotNull(actor);
    assertEquals("system", actor.id());
    assertEquals("system", actor.type());
  }

  @Test
  @DisplayName(
      "Engine mode is the machine-caller case this token represents: security defaults off, so"
          + " this filter is the one that establishes its identity")
  void engineModeDefaultsToSecurityDisabled() {
    assertFalse(
        FlowSecurityProperties.isSecurityEnabled(
            new MockEnvironment().withProperty("flow.mode", "engine")));
    assertTrue(
        FlowSecurityProperties.isSecurityEnabled(
            new MockEnvironment().withProperty("flow.mode", "standalone")));
    // An explicit setting still wins over the flow.mode-derived default.
    assertFalse(
        FlowSecurityProperties.isSecurityEnabled(
            new MockEnvironment()
                .withProperty("flow.mode", "standalone")
                .withProperty("flow.security.enabled", "false")));
  }
}

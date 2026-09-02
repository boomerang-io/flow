package io.boomerang.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
import io.boomerang.core.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;

/**
 * Same enforcement {@code SecurityInterceptor} used to provide, now expressed as an {@code
 * AuthorizationManager}: a permission mismatch denies (403-worthy - a returned {@link
 * AuthorizationDecision}, counted), a scope mismatch or a missing identity denies by throwing an
 * {@code AuthenticationException} (401-worthy, matching {@code AuthenticationFilter}'s shared
 * entry point) - and, the actual bugfix, a class-level {@code @AuthCriteria} is now honoured where
 * the retired interceptor silently ignored it.
 */
@ExtendWith(MockitoExtension.class)
class AuthCriteriaAuthorizationManagerTest {

  @Mock private IdentityService identityService;
  @Mock private MethodInvocation invocation;

  private SimpleMeterRegistry meterRegistry;
  private AuthCriteriaAuthorizationManager manager;
  private final Supplier<Authentication> noAuthentication = () -> null;

  @AuthCriteria(
      assignableScopes = {AuthScope.global},
      resource = PermissionResource.WORKFLOW,
      action = PermissionAction.READ)
  static class ClassLevelProtectedHandler {
    public void unannotatedEndpoint() {}
  }

  static class MethodLevelProtectedHandler {
    @AuthCriteria(
        assignableScopes = {AuthScope.global},
        resource = PermissionResource.WORKFLOW,
        action = PermissionAction.READ)
    public void protectedEndpoint() {}
  }

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    manager = new AuthCriteriaAuthorizationManager(identityService, meterRegistry);
  }

  @Test
  void permissionMismatchDeniesAndCounts() throws Exception {
    when(invocation.getMethod())
        .thenReturn(MethodLevelProtectedHandler.class.getMethod("protectedEndpoint"));
    Token token = new Token(AuthScope.global);
    token.setPrincipal("test-user");
    token.setPermissions(
        List.of(
            new ResolvedPermissions(PermissionScope.global, "test-user", List.of("workflow/write"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);

    AuthorizationDecision decision = manager.authorize(noAuthentication, invocation);

    assertFalse(decision.isGranted());
    assertEquals(
        1,
        meterRegistry
            .counter(
                "flow.security.denied", "resource", "workflow", "action", "read", "type", "global")
            .count());
  }

  @Test
  void scopeMismatchThrowsAnAuthenticationExceptionAndCounts() throws Exception {
    when(invocation.getMethod())
        .thenReturn(MethodLevelProtectedHandler.class.getMethod("protectedEndpoint"));
    when(identityService.getCurrentIdentity()).thenReturn(new Token(AuthScope.user));

    assertThrows(
        AuthenticationCredentialsNotFoundException.class,
        () -> manager.authorize(noAuthentication, invocation));
    assertEquals(
        1,
        meterRegistry
            .counter("flow.security.denied", "resource", "workflow", "action", "read", "type", "user")
            .count());
  }

  @Test
  void noIdentityThrowsAnAuthenticationExceptionWithoutCounting() throws Exception {
    when(invocation.getMethod())
        .thenReturn(MethodLevelProtectedHandler.class.getMethod("protectedEndpoint"));
    when(identityService.getCurrentIdentity()).thenReturn(null);

    assertThrows(
        AuthenticationCredentialsNotFoundException.class,
        () -> manager.authorize(noAuthentication, invocation));
    assertTrue(
        meterRegistry.getMeters().isEmpty(),
        "a missing identity is a route misconfiguration, not a denial - never counted");
  }

  @Test
  void aGrantedPermissionIsAuthorized() throws Exception {
    when(invocation.getMethod())
        .thenReturn(MethodLevelProtectedHandler.class.getMethod("protectedEndpoint"));
    Token token = new Token(AuthScope.global);
    token.setPermissions(
        List.of(new ResolvedPermissions(PermissionScope.global, "admin", List.of("**/**"))));
    when(identityService.getCurrentIdentity()).thenReturn(token);

    assertTrue(manager.authorize(noAuthentication, invocation).isGranted());
  }

  @Test
  void aClassLevelAuthCriteriaIsHonoured() throws Exception {
    // THE BUGFIX: the retired SecurityInterceptor only ever read
    // handlerMethod.getMethod().getAnnotation(), so a class-level @AuthCriteria (always a valid
    // target - see AuthCriteria's own @Target) was silently ignored and the route ran
    // unprotected.
    when(invocation.getMethod())
        .thenReturn(ClassLevelProtectedHandler.class.getMethod("unannotatedEndpoint"));
    when(identityService.getCurrentIdentity()).thenReturn(new Token(AuthScope.user));

    assertThrows(
        AuthenticationCredentialsNotFoundException.class,
        () -> manager.authorize(noAuthentication, invocation),
        "a class-level @AuthCriteria must still enforce assignableScopes on an unannotated method");
  }
}

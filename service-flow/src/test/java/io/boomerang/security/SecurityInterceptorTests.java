package io.boomerang.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.core.model.Token;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.boomerang.security.model.ResolvedPermissions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

/*
 * Verifies shadow enforcement: a permission mismatch is counted but never blocks the
 * request, while a scope mismatch still denies with a 401.
 */
@ExtendWith(MockitoExtension.class)
class SecurityInterceptorTests {

  @Mock private IdentityService identityService;
  @Mock private HttpServletRequest request;
  @Mock private HttpServletResponse response;

  private SimpleMeterRegistry meterRegistry;
  private SecurityInterceptor interceptor;
  private HandlerMethod handlerMethod;

  static class ProtectedHandler {
    @AuthCriteria(
        assignableScopes = {AuthScope.global},
        resource = PermissionResource.WORKFLOW,
        action = PermissionAction.READ)
    public void protectedEndpoint() {}
  }

  @BeforeEach
  void setUp() throws NoSuchMethodException {
    meterRegistry = new SimpleMeterRegistry();
    interceptor = new SecurityInterceptor(identityService, meterRegistry);
    handlerMethod =
        new HandlerMethod(
            new ProtectedHandler(), ProtectedHandler.class.getMethod("protectedEndpoint"));
  }

  @Test
  void testPermissionMismatchAllowsAndCountsWouldDeny() throws Exception {
    Token token = new Token(AuthScope.global);
    token.setPrincipal("test-user");
    token.setPermissions(
        List.of(new ResolvedPermissions(AuthScope.global, "test-user", List.of("workflow/write"))));
    when(identityService.getCurrentScope()).thenReturn(AuthScope.global);
    when(identityService.getCurrentIdentity()).thenReturn(token);

    assertTrue(interceptor.preHandle(request, response, handlerMethod));
    assertEquals(
        1,
        meterRegistry
            .counter(
                "flow.security.would.deny",
                "resource",
                "workflow",
                "action",
                "read",
                "type",
                "global")
            .count());
  }

  @Test
  void testScopeMismatchDeniesAndCountsDenied() throws Exception {
    when(identityService.getCurrentScope()).thenReturn(AuthScope.user);
    when(identityService.getCurrentIdentity()).thenReturn(new Token(AuthScope.user));
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    assertFalse(interceptor.preHandle(request, response, handlerMethod));
    verify(response).setStatus(401);
    assertEquals(
        1,
        meterRegistry
            .counter(
                "flow.security.denied", "resource", "workflow", "action", "read", "type", "user")
            .count());
  }
}

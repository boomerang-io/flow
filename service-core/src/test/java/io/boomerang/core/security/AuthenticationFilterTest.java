package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The session cookie as an identity source, and the structured-401 fix (specifications/authentication.md
 * §1/§5): AuthenticationFilter must not disturb its existing header-based branches, must accept
 * SessionCookie.NAME as an additional source, and must route an unresolved identity through the
 * delegated entry point EXCEPT on the exchange endpoint's own path, which it must let through
 * unauthenticated (the OIDC login body verifies itself).
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

  @Mock private TokenService tokenService;
  @Mock private SettingsService settingsService;
  @Mock private AuthenticationEntryPoint authEntryPoint;
  @Mock private FilterChain filterChain;

  private AuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new AuthenticationFilter(tokenService, settingsService, "basic-pass", authEntryPoint);
    SecurityContextHolder.clearContext();
  }

  @Test
  void aValidSessionCookieAuthenticatesTheRequest() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("user-1");
    when(tokenService.validate("bfs_raw-value")).thenReturn(true);
    when(tokenService.get("bfs_raw-value")).thenReturn(sessionToken);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.setCookies(new Cookie(SessionCookie.NAME, "bfs_raw-value"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verify(authEntryPoint, never()).commence(any(), any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
        .isEqualTo(sessionToken);
  }

  @Test
  void anInvalidSessionCookieIsRejectedWithAStructuredEntryPointResponse() throws Exception {
    when(tokenService.validate("bfs_bad-value")).thenReturn(false);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.setCookies(new Cookie(SessionCookie.NAME, "bfs_bad-value"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(any(), any());
    ArgumentCaptor<AuthenticationException> captor =
        ArgumentCaptor.forClass(AuthenticationException.class);
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), captor.capture());
    assertThat(captor.getValue()).isInstanceOf(FlowAuthenticationException.class);
  }

  @Test
  void theExchangeEndpointIsLetThroughUnauthenticated() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", AuthenticationFilter.PATH_AUTH_EXCHANGE);
    request.setServletPath(AuthenticationFilter.PATH_AUTH_EXCHANGE);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verify(authEntryPoint, never()).commence(any(), any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}

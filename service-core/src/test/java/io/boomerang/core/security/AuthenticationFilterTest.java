package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * The session cookie as an identity source, and the structured 401: AuthenticationFilter must
 * not disturb its existing header-based branches, must accept
 * SessionCookie.NAME as an additional source, and must route an unresolved identity through the
 * delegated entry point EXCEPT on the exchange endpoint's own path, which it must let through
 * unauthenticated (the OIDC login body verifies itself). A proxy-forwarded bearer JWT (a
 * non-Flow-shaped {@code Authorization: Bearer <jwt>}) is read for its email as the proxy sent
 * it: this filter checks no signature, so the route rests on the proxy in front of it.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

  @Mock private TokenService tokenService;
  @Mock private SettingsService settingsService;
  @Mock private UserService userService;
  @Mock private AuthenticationEntryPoint authEntryPoint;
  @Mock private FilterChain filterChain;

  private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

  private AuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new AuthenticationFilter(
            tokenService,
            settingsService,
            "basic-pass",
            authEntryPoint,
            passwordEncoder,
            new AdminClaim("", List.of(), false),
            userService);
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
  void theAuthConfigEndpointIsNeverFiltered() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", SecurityConfiguration.AUTH_CONFIG);
    request.setServletPath(SecurityConfiguration.AUTH_CONFIG);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verify(authEntryPoint, never()).commence(any(), any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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

  @Test
  void theCorrectBasicPasswordAuthenticates() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("person@example.test");
    when(tokenService.createSessionToken("person@example.test", null, null, false, false))
        .thenReturn(sessionToken);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader(
        "Authorization",
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString("person@example.test:basic-pass".getBytes()));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verify(authEntryPoint, never()).commence(any(), any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
        .isEqualTo(sessionToken);
  }

  @Test
  void theWrongBasicPasswordIsRejected() throws Exception {
    // The comparison is now via PasswordEncoder.matches (constant-time), never a raw
    // String.equals against the configured password - this pins the observable behaviour, not
    // the timing property itself.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader(
        "Authorization",
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString("person@example.test:wrong-pass".getBytes()));
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(any(), any());
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"person@example.test:", "person@example.test"})
  void aBlankConfiguredBasicPasswordAuthenticatesNobody(String credentials) throws Exception {
    // flow.authorization.basic.password is unset by default, and a header with no password
    // supplies an empty one: the two must not match, or Basic would admit any email.
    AuthenticationFilter unconfigured =
        new AuthenticationFilter(
            tokenService,
                settingsService,
                "",
                authEntryPoint,
                passwordEncoder,
                new AdminClaim("", List.of(), false),
                userService);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader(
        "Authorization",
        "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes()));
    MockHttpServletResponse response = new MockHttpServletResponse();

    unconfigured.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(any(), any());
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), any());
    verifyNoInteractions(tokenService);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void aProxyForwardedBearerJwtSignsItsEmailIn() throws Exception {
    // The route a v3 install signs in by: its proxy forwards the provider's id_token, and the
    // email claim is taken as the proxy sent it.
    String jwt =
        new PlainJWT(new JWTClaimsSet.Builder().claim("email", "person@example.test").build())
            .serialize();
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("person@example.test");
    when(tokenService.createSessionToken("person@example.test", null, null, false, false, null))
        .thenReturn(sessionToken);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader("Authorization", "Bearer " + jwt);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verify(authEntryPoint, never()).commence(any(), any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
        .isEqualTo(sessionToken);
  }

  private AuthenticationFilter filterTrusting(boolean trustForwardedToken) {
    return new AuthenticationFilter(
        tokenService,
        settingsService,
        "basic-pass",
        authEntryPoint,
        passwordEncoder,
        new AdminClaim("groups", List.of("flow-admins"), trustForwardedToken),
        userService);
  }

  private MockHttpServletRequest forwardedJwtWithGroups(String... groups) {
    String jwt =
        new PlainJWT(
                new JWTClaimsSet.Builder()
                    .claim("email", "person@example.test")
                    .claim("groups", List.of(groups))
                    .build())
            .serialize();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/profile");
    request.setServletPath("/api/v2/profile");
    request.addHeader("Authorization", "Bearer " + jwt);
    return request;
  }

  // The forwarded JWT is unverified, so its admin claim is ignored unless the operator trusts the proxy.
  @Test
  void aForwardedJwtsAdminClaimIsIgnoredUnlessTheProxyIsTrusted() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("person@example.test");
    when(tokenService.createSessionToken("person@example.test", null, null, false, true, null))
        .thenReturn(sessionToken);

    filterTrusting(false)
        .doFilter(forwardedJwtWithGroups("flow-admins"), new MockHttpServletResponse(), filterChain);

    verify(tokenService).createSessionToken("person@example.test", null, null, false, true, null);
  }

  @Test
  void aTrustedForwardedJwtCarriesTheClaimVerdictToTheSignIn() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("person@example.test");
    when(tokenService.createSessionToken(
            eq("person@example.test"), any(), any(), eq(false), eq(true), any(Boolean.class)))
        .thenReturn(sessionToken);

    filterTrusting(true)
        .doFilter(forwardedJwtWithGroups("flow-admins"), new MockHttpServletResponse(), filterChain);
    filterTrusting(true)
        .doFilter(forwardedJwtWithGroups("staff"), new MockHttpServletResponse(), filterChain);

    verify(tokenService)
        .createSessionToken("person@example.test", null, null, false, true, Boolean.TRUE);
    verify(tokenService)
        .createSessionToken("person@example.test", null, null, false, true, Boolean.FALSE);
  }

  // Headers and Basic carry no claim, so they never grant or remove admin.
  @Test
  void aForwardedEmailHeaderCarriesNoClaimVerdict() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("user-1");
    when(tokenService.createSessionToken("person@example.test", null, null, false, true))
        .thenReturn(sessionToken);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/profile");
    request.setServletPath("/api/v2/profile");
    request.addHeader("x-forwarded-email", "person@example.test");

    filterTrusting(true).doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(tokenService).createSessionToken("person@example.test", null, null, false, true);
    verify(tokenService, never())
        .createSessionToken(any(), any(), any(), anyBoolean(), anyBoolean(), any());
  }

  // --- the one-time code and the first admin -------------------------------------------------

  private MockHttpServletRequest activateRequest(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v2/activate");
    request.setServletPath("/api/v2/activate");
    request.addHeader("x-forwarded-email", "person@example.test");
    request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return request;
  }

  @Test
  void aWrongCodeOnAnEmptyInstallCreatesNobodyAndIsAnswered400() throws Exception {
    when(userService.isActivated()).thenReturn(false);
    when(userService.activationCodeValid("wrong")).thenReturn(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(activateRequest("{\"otc\":\"wrong\"}"), response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    verifyNoInteractions(tokenService);
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void aMissingBodyOnAnEmptyInstallCreatesNobody() throws Exception {
    when(userService.isActivated()).thenReturn(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(activateRequest(""), response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    verifyNoInteractions(tokenService);
  }

  @Test
  void theRightCodeOnAnEmptyInstallLetsTheFirstAdminBeCreatedAndReplaysTheBody() throws Exception {
    when(userService.isActivated()).thenReturn(false);
    when(userService.activationCodeValid("right")).thenReturn(true);
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("user-1");
    when(tokenService.createSessionToken("person@example.test", null, null, true, true))
        .thenReturn(sessionToken);
    String[] replayed = new String[1];
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var forwarded = (jakarta.servlet.http.HttpServletRequest) invocation.getArgument(0);
              replayed[0] = new String(forwarded.getInputStream().readAllBytes());
              return null;
            })
        .when(filterChain)
        .doFilter(any(), any());

    filter.doFilter(activateRequest("{\"otc\":\"right\"}"), new MockHttpServletResponse(), filterChain);

    verify(tokenService).createSessionToken("person@example.test", null, null, true, true);
    assertThat(replayed[0]).isEqualTo("{\"otc\":\"right\"}");
  }

  @Test
  void onAnActivatedInstallTheActivationRouteSignsInLikeAnyOther() throws Exception {
    when(userService.isActivated()).thenReturn(true);
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("user-1");
    when(tokenService.createSessionToken("person@example.test", null, null, false, false))
        .thenReturn(sessionToken);

    filter.doFilter(activateRequest("{\"otc\":\"wrong\"}"), new MockHttpServletResponse(), filterChain);

    verify(tokenService).createSessionToken("person@example.test", null, null, false, false);
    verify(userService, never()).activationCodeValid(any());
  }

  @Test
  void withAnAdminClaimTheCodeIsNotUsedToActivate() throws Exception {
    when(userService.isActivated()).thenReturn(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filterTrusting(true).doFilter(activateRequest("{\"otc\":\"right\"}"), response, filterChain);

    assertThat(response.getStatus()).isEqualTo(400);
    verifyNoInteractions(tokenService);
  }

  @Test
  void theExchangeRouteNeverActivatesThroughTheFilter() throws Exception {
    Token sessionToken = new Token(AuthScope.session);
    sessionToken.setPrincipal("user-1");
    when(tokenService.createSessionToken("person@example.test", null, null, false, true))
        .thenReturn(sessionToken);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", AuthenticationFilter.PATH_AUTH_EXCHANGE);
    request.setServletPath(AuthenticationFilter.PATH_AUTH_EXCHANGE);
    request.addHeader("x-forwarded-email", "person@example.test");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(tokenService).createSessionToken("person@example.test", null, null, false, true);
  }

  @Test
  void aBearerThatIsNotAJwtIsRejected() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader("Authorization", "Bearer not-a-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verifyNoInteractions(tokenService);
    verify(filterChain, never()).doFilter(any(), any());
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"bft_retired-workspace-token", "bfw_retired-workflow-token"})
  void aRetiredPrefixBearerNeverReachesTheTokenLookup(String retired) throws Exception {
    // The shape gate (TokenTypePrefix.isFlowToken) admits only bfg_/bfk_/bfu_/bfs_; anything
    // else on the Authorization header is treated as a non-Flow bearer, and a value that is
    // not a JWT yields no identity - TokenService is never consulted.
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader("Authorization", "Bearer " + retired);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verifyNoInteractions(tokenService);
    verify(filterChain, never()).doFilter(any(), any());
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"bft_retired-workspace-token", "bfw_retired-workflow-token"})
  void aRetiredPrefixOnTheFallbackSourcesNeverReachesTheTokenLookup(String retired)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader("x-access-token", retired);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verifyNoInteractions(tokenService);
    verify(filterChain, never()).doFilter(any(), any());
    verify(authEntryPoint, times(1)).commence(eq(request), eq(response), any());
  }

  @Test
  void aLivePrefixBearerReachesTheTokenLookup() throws Exception {
    Token globalToken = new Token(AuthScope.global);
    globalToken.setPrincipal("admin");
    when(tokenService.validate("bfg_live-token")).thenReturn(true);
    when(tokenService.get("bfg_live-token")).thenReturn(globalToken);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/workflow");
    request.setServletPath("/api/v2/workflow");
    request.addHeader("Authorization", "Bearer bfg_live-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(tokenService, times(1)).validate("bfg_live-token");
    verify(filterChain, times(1)).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
        .isEqualTo(globalToken);
  }
}

package io.boomerang.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.JWTClaimsSet;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.TokenService;
import io.boomerang.core.TokenService.SessionToken;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.AuthConfig;
import io.boomerang.core.model.AuthConfig.AuthMode;
import io.boomerang.core.model.AuthExchangeRequest;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.enums.AuthScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * The unified token exchange's two identity sources at the service layer - both converge on
 * {@code TokenService}'s session-minting, and logout must revoke.
 */
@ExtendWith(MockitoExtension.class)
class AuthExchangeServiceTest {

  @Mock private IdentityService identityService;
  @Mock private UserService userService;
  @Mock private TokenService tokenService;
  @Mock private OidcTokenVerifier oidcTokenVerifier;

  private final MockEnvironment environment = new MockEnvironment();

  private AuthExchangeService authExchangeService;

  @BeforeEach
  void setUp() {
    authExchangeService =
        new AuthExchangeService(
            identityService, userService, tokenService, oidcTokenVerifier, environment);
  }

  @Test
  void emptyBodyMintsASessionFromTheAlreadyResolvedProxyIdentity() {
    Token identity = new Token(AuthScope.session);
    identity.setPrincipal("user-1");
    when(identityService.getCurrentIdentity()).thenReturn(identity);

    UserEntity user = new UserEntity();
    user.setId("user-1");
    when(userService.getCurrentUser()).thenReturn(user);

    SessionToken minted = new SessionToken(new Token(AuthScope.session), "bfs_raw-value");
    when(tokenService.createSessionTokenForUser(user)).thenReturn(minted);

    SessionToken result = authExchangeService.exchange(null);

    assertThat(result).isSameAs(minted);
    verify(oidcTokenVerifier, never()).verify(any(), any());
  }

  @Test
  void emptyBodyWithNoResolvedIdentityIsRejected() {
    when(identityService.getCurrentIdentity()).thenReturn(null);

    assertThatThrownBy(() -> authExchangeService.exchange(null))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getReason())
        .isEqualTo(BoomerangError.AUTH_REQUIRED.getReason());

    verify(userService, never()).getCurrentUser();
  }

  @Test
  void idTokenBodyVerifiesThenMintsFromTheClaimedEmail() throws Exception {
    AuthExchangeRequest request = new AuthExchangeRequest();
    request.setIdToken("a.b.c");
    request.setNonce("nonce-1");

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("idp-user-1")
            .claim("email", "person@example.test")
            .claim("given_name", "Person")
            .claim("family_name", "Example")
            .build();
    when(oidcTokenVerifier.verify("a.b.c", "nonce-1")).thenReturn(claims);

    SessionToken minted = new SessionToken(new Token(AuthScope.session), "bfs_raw-value");
    when(tokenService.createSessionTokenWithRaw(
            "person@example.test", "Person", "Example", true, true))
        .thenReturn(minted);

    SessionToken result = authExchangeService.exchange(request);

    assertThat(result).isSameAs(minted);
    verify(identityService, never()).getCurrentIdentity();
  }

  /*
   * Azure-compatibility claim fallbacks (maintainer-approved 2026-08-31, following ARCHIE's
   * proven chain): email -> emailAddress -> preferred_username, the last only when email-shaped,
   * because Flow keys users and IDP activation on email. Names prefer the composite `name` claim
   * (split on the first space), falling back to given_name/family_name and the legacy aliases.
   */
  @Test
  void idTokenWithOnlyAnEmailShapedPreferredUsernameMintsFromIt() {
    AuthExchangeRequest request = new AuthExchangeRequest();
    request.setIdToken("a.b.c");
    request.setNonce("nonce-1");

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("idp-user-1")
            .claim("preferred_username", "person@example.test")
            .build();
    when(oidcTokenVerifier.verify("a.b.c", "nonce-1")).thenReturn(claims);

    SessionToken minted = new SessionToken(new Token(AuthScope.session), "bfs_raw-value");
    when(tokenService.createSessionTokenWithRaw("person@example.test", null, null, true, true))
        .thenReturn(minted);

    assertThat(authExchangeService.exchange(request)).isSameAs(minted);
  }

  @Test
  void idTokenWithANonEmailPreferredUsernameAndNoEmailClaimIsRejected() {
    AuthExchangeRequest request = new AuthExchangeRequest();
    request.setIdToken("a.b.c");
    request.setNonce("nonce-1");

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("idp-user-1")
            .claim("preferred_username", "grace.hopper")
            .build();
    when(oidcTokenVerifier.verify("a.b.c", "nonce-1")).thenReturn(claims);

    assertThatThrownBy(() -> authExchangeService.exchange(request))
        .isInstanceOf(BoomerangException.class)
        .extracting(ex -> ((BoomerangException) ex).getReason())
        .isEqualTo(BoomerangError.AUTH_TOKEN_INVALID.getReason());

    verify(tokenService, never()).createSessionTokenWithRaw(any(), any(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void compositeNameClaimSplitsOnTheFirstSpaceIntoFirstAndLastName() {
    AuthExchangeRequest request = new AuthExchangeRequest();
    request.setIdToken("a.b.c");
    request.setNonce("nonce-1");

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("idp-user-1")
            .claim("email", "person@example.test")
            .claim("name", "Grace Brewster Hopper")
            .build();
    when(oidcTokenVerifier.verify("a.b.c", "nonce-1")).thenReturn(claims);

    SessionToken minted = new SessionToken(new Token(AuthScope.session), "bfs_raw-value");
    when(tokenService.createSessionTokenWithRaw(
            "person@example.test", "Grace", "Brewster Hopper", true, true))
        .thenReturn(minted);

    assertThat(authExchangeService.exchange(request)).isSameAs(minted);
  }

  @Test
  void logoutRevokesTheCurrentSessionToken() {
    Token identity = new Token(AuthScope.session);
    identity.setId("token-id-1");
    when(identityService.getCurrentIdentity()).thenReturn(identity);

    authExchangeService.logout();

    verify(tokenService, times(1)).delete("token-id-1");
  }

  @Test
  void logoutWithNoCurrentSessionIsANoOp() {
    when(identityService.getCurrentIdentity()).thenReturn(null);

    authExchangeService.logout();

    verify(tokenService, never()).delete(any());
  }

  /*
   * GET /api/v2/auth/config mode derivation: "none" when security is disabled, "oidc" when a
   * trusted issuer AND clientId are configured, "proxy" otherwise - and issuer/clientId are only
   * ever present for oidc.
   */
  @Test
  void configWithSecurityDisabledIsModeNoneAndExposesNothing() {
    environment.setProperty("flow.security.enabled", "false");

    AuthConfig config = authExchangeService.config();

    assertThat(config.getMode()).isEqualTo(AuthMode.none);
    assertThat(config.getIssuer()).isNull();
    assertThat(config.getClientId()).isNull();
    verify(oidcTokenVerifier, never()).configuredIssuer();
  }

  @Test
  void configWithATrustedIssuerAndClientIdIsModeOidc() {
    // No flow.security.enabled / flow.mode set: standalone default = security enabled.
    when(oidcTokenVerifier.configuredIssuer()).thenReturn("https://idp.example.test");
    when(oidcTokenVerifier.configuredClientId()).thenReturn("flow-web");

    AuthConfig config = authExchangeService.config();

    assertThat(config.getMode()).isEqualTo(AuthMode.oidc);
    assertThat(config.getIssuer()).isEqualTo("https://idp.example.test");
    assertThat(config.getClientId()).isEqualTo("flow-web");
  }

  @Test
  void configWithoutOidcSettingsIsModeProxy() {
    when(oidcTokenVerifier.configuredIssuer()).thenReturn(null);

    AuthConfig config = authExchangeService.config();

    assertThat(config.getMode()).isEqualTo(AuthMode.proxy);
    assertThat(config.getIssuer()).isNull();
    assertThat(config.getClientId()).isNull();
  }

  @Test
  void configWithAnIssuerButNoClientIdIsModeProxy() {
    when(oidcTokenVerifier.configuredIssuer()).thenReturn("https://idp.example.test");
    when(oidcTokenVerifier.configuredClientId()).thenReturn(null);

    AuthConfig config = authExchangeService.config();

    assertThat(config.getMode()).isEqualTo(AuthMode.proxy);
    assertThat(config.getIssuer()).isNull();
  }
}

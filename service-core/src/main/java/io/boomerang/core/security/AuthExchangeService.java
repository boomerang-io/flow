package io.boomerang.core.security;

import com.nimbusds.jwt.JWTClaimsSet;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.TokenService;
import io.boomerang.core.TokenService.SessionToken;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.AuthConfig;
import io.boomerang.core.model.AuthExchangeRequest;
import io.boomerang.core.model.Token;
import java.text.ParseException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The two identity sources of {@code POST /api/v2/auth/exchange} converging on the same
 * session-minting path (specifications/authentication.md §1). Standalone-only: the exchange
 * endpoint has no meaning for an embedded engine.
 */
@Service
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class AuthExchangeService {

  private final IdentityService identityService;
  private final UserService userService;
  private final TokenService tokenService;
  private final OidcTokenVerifier oidcTokenVerifier;
  private final Environment environment;

  public AuthExchangeService(
      IdentityService identityService,
      UserService userService,
      TokenService tokenService,
      OidcTokenVerifier oidcTokenVerifier,
      Environment environment) {
    this.identityService = identityService;
    this.userService = userService;
    this.tokenService = tokenService;
    this.oidcTokenVerifier = oidcTokenVerifier;
    this.environment = environment;
  }

  /*
   * The pre-auth bootstrap contract of GET /api/v2/auth/config: "none" when security is disabled,
   * "oidc" when a trusted issuer AND clientId are configured, "proxy" otherwise. The sign-in mode
   * is derived from configuration that already exists (flow.security.enabled and the auth.oidc.*
   * settings) rather than a second flag that could fall out of sync. issuer/clientId are only
   * ever exposed for oidc; nothing else from the auth settings leaves the server through this
   * shape.
   */
  public AuthConfig config() {
    if (!FlowSecurityProperties.isSecurityEnabled(environment)) {
      return AuthConfig.none();
    }
    String issuer = oidcTokenVerifier.configuredIssuer();
    String clientId = oidcTokenVerifier.configuredClientId();
    return (issuer != null && clientId != null)
        ? AuthConfig.oidc(issuer, clientId)
        : AuthConfig.proxy();
  }

  public SessionToken exchange(AuthExchangeRequest request) {
    if (request != null && request.getIdToken() != null && !request.getIdToken().isBlank()) {
      return exchangeOidc(request);
    }
    return exchangeProxy();
  }

  /*
   * AuthenticationFilter has already resolved (and, for a first-time caller, registered) the
   * principal for this request from x-forwarded-email or a forwarded JWT - read it via
   * IdentityService rather than re-parsing headers here, which would duplicate the filter's own
   * logic. createSessionTokenForUser mints straight from that already-resolved user, calling
   * TokenService.resolvePermissionsForUser itself.
   */
  private SessionToken exchangeProxy() {
    // Still null-checked: this route is permitAll, so with security ENABLED AuthenticationFilter
    // may legitimately resolve no identity for it (see its PATH_AUTH_EXCHANGE branch).
    Token identity = identityService.getCurrentIdentity();
    if (identity == null || identity.getPrincipal() == null) {
      throw new BoomerangException(BoomerangError.AUTH_REQUIRED);
    }
    // A resolved identity is not necessarily a USER: with security disabled the established
    // identity is the machine UnauthenticatedGlobalToken, which maps to no user record. There is
    // no session to mint for it - fail the same way as above rather than handing null to
    // mintSessionToken(), which dereferences user.getId().
    UserEntity user = userService.getCurrentUser();
    if (user == null) {
      throw new BoomerangException(BoomerangError.AUTH_REQUIRED);
    }
    return tokenService.createSessionTokenForUser(user);
  }

  private SessionToken exchangeOidc(AuthExchangeRequest request) {
    JWTClaimsSet claims = oidcTokenVerifier.verify(request.getIdToken(), request.getNonce());

    String email = firstNonBlank(claimString(claims, "email"), claimString(claims, "emailAddress"));
    if (email == null || email.isBlank()) {
      throw new BoomerangException(BoomerangError.AUTH_TOKEN_INVALID);
    }
    String firstName =
        firstNonBlank(claimString(claims, "given_name"), claimString(claims, "firstName"));
    String lastName =
        firstNonBlank(claimString(claims, "family_name"), claimString(claims, "lastName"));

    // The exchange endpoint is a first-call bootstrap path exactly like /api/v2/profile and
    // /api/v2/activate: it may both activate the instance (first admin) and register a brand new
    // user.
    return tokenService.createSessionTokenWithRaw(email, firstName, lastName, true, true);
  }

  /** Revokes the current session token (identified by AuthenticationFilter's cookie branch). */
  public void logout() {
    Token identity = identityService.getCurrentIdentity();
    if (identity != null && identity.getId() != null) {
      tokenService.delete(identity.getId());
    }
  }

  private static String claimString(JWTClaimsSet claims, String name) {
    try {
      return claims.getStringClaim(name);
    } catch (ParseException ex) {
      return null;
    }
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }
}

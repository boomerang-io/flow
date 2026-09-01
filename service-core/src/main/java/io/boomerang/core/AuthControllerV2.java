package io.boomerang.core;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.TokenService.SessionToken;
import io.boomerang.core.model.AuthConfig;
import io.boomerang.core.model.AuthExchangeRequest;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.AuthExchangeService;
import io.boomerang.core.security.SessionCookie;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * The unified token exchange (specifications/authentication.md §1/§2/§5): a single endpoint, two
 * identity sources (an authenticating proxy's already-resolved principal, or a direct OIDC
 * id_token), converging on the same session-minting path. Delivered as an httpOnly, Secure,
 * SameSite=Lax cookie carrying only the opaque bfs_<uuid> value - never permissions, which stay
 * server-side on the persisted TokenEntity.
 */
@RestController
@RequestMapping("/api/v2/auth")
@Tag(
    name = "Authentication",
    description =
        "Unified token exchange - proxy-forwarded identity or a direct OIDC login both converge on"
            + " the same session cookie.")
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class AuthControllerV2 {

  @Autowired private AuthExchangeService authExchangeService;

  /*
   * The pre-auth bootstrap: the SPA must learn HOW to sign in before it holds any session, so this
   * endpoint is deliberately public - @AuthCriteria is absent on purpose (not an oversight), and
   * the path is exempted from the security layer (permitAll + shouldNotFilter) exactly like the
   * GitHub callback. It exposes only the sign-in mode and, for oidc, the issuer/clientId the
   * browser needs for its authorize request - never anything else from the auth settings.
   */
  @GetMapping("/config")
  @Operation(summary = "How to sign in - the pre-auth bootstrap contract for the webapp")
  public AuthConfig config() {
    return authExchangeService.config();
  }

  /*
   * Empty body: the caller is behind an authenticating proxy and AuthenticationFilter has already
   * resolved a principal for this request (x-forwarded-email or a forwarded JWT).
   * {idToken}: a direct OIDC login (local IDPZero) - verified via JWKS (issuer/audience/expiry/nonce)
   * before a session is minted.
   *
   * Reachable unauthenticated (permitAll in SecurityConfiguration): the proxy path still relies on
   * AuthenticationFilter having resolved an identity for THIS request; the OIDC path verifies the
   * id_token itself and needs no prior credential.
   */
  @PostMapping("/exchange")
  @Operation(
      summary = "Exchange a proxy-forwarded identity or a verified OIDC id_token for a session")
  public ResponseEntity<Void> exchange(
      @RequestBody(required = false) AuthExchangeRequest request) {
    SessionToken session = authExchangeService.exchange(request);
    Duration maxAge = maxAge(session);
    ResponseCookie cookie = SessionCookie.mint(session.rawToken(), maxAge);
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
  }

  /*
   * Clears the session cookie AND revokes the underlying token - the reference implementation
   * this design is modelled on only clears the cookie, leaving the token valid server-side until
   * its natural expiry.
   */
  @PostMapping("/logout")
  @AuthCriteria(
      assignableScopes = {AuthScope.session},
      resource = PermissionResource.TOKEN,
      action = PermissionAction.DELETE)
  @Operation(summary = "Revoke the current session and clear the session cookie")
  public ResponseEntity<Void> logout() {
    authExchangeService.logout();
    return ResponseEntity.status(HttpStatus.NO_CONTENT)
        .header(HttpHeaders.SET_COOKIE, SessionCookie.clear().toString())
        .build();
  }

  private static Duration maxAge(SessionToken session) {
    if (session.token().getExpirationDate() == null) {
      return Duration.ZERO;
    }
    Duration remaining =
        Duration.between(Instant.now(), session.token().getExpirationDate().toInstant());
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }
}

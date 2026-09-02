package io.boomerang.core.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.slack.api.app_backend.SlackSignature.Generator;
import com.slack.api.app_backend.SlackSignature.Verifier;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * The Filter ensures that the user is Authenticated prior to the Interceptor which validates
 * Authorization
 *
 * Note: This cannot be auto marked as a Service/Component that Spring Boot would auto inject as
 * then it will apply to all routes
 */
@ConditionalOnProperty(name = "flow.security.enabled", havingValue = "true")
public class AuthenticationFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String X_FORWARDED_USER = "x-forwarded-user";
  private static final String X_FORWARDED_EMAIL = "x-forwarded-email";
  // The URL param and the x-access-token header are KEPT deliberately (ruled 2026-09-01, after
  // briefly being removed): major webhook senders offer no way to set an Authorization header -
  // Docker Hub has no webhook auth/header configuration at all, and the industry-standard
  // fallback for such senders is a token in the URL (capability URL). Removing them would leave
  // header-less senders with no way to authenticate to /webhook, /event and /callback.
  // Mitigations: HTTPS-only deployments, and tokens are scoped + revocable.
  private static final String TOKEN_URL_PARAM_NAME = "access_token";
  private static final String X_ACCESS_TOKEN_HEADER = "x-access-token";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  //  private static final String X_SLACK_SIGNATURE = "X-Slack-Signature";
  //  private static final String X_SLACK_TIMESTAMP = "X-Slack-Request-Timestamp";
  private static final String PATH_ACTIVATE = "/api/v2/activate";
  private static final String PATH_PROFILE = "/api/v2/profile";
  // The unified token exchange (PATH_AUTH_EXCHANGE) is now, alongside
  // PATH_PROFILE/PATH_ACTIVATE, a valid first call for a brand-new proxy-identified caller - so it
  // gets the same allowActivation/allowUserCreation treatment below. It is ALSO the one path this
  // filter must not reject with a 401 when it resolves no identity at all: the direct OIDC login
  // body ({idToken}) carries no proxy headers, and the exchange controller verifies that token
  // itself rather than relying on this filter.
  static final String PATH_AUTH_EXCHANGE = "/api/v2/auth/exchange";
  private static final String TOKEN_PATTERN = "Bearer\\sbf._(.)+";

  private TokenService tokenService;
  private SettingsService settingsService;
  private PasswordEncoder passwordEncoder;
  // Encoded once at startup from flow.authorization.basic.password - never the raw configured
  // value, so the comparison below is always through PasswordEncoder.matches (constant-time),
  // never a raw String.equals.
  private String encodedBasicPassword;
  private AuthenticationEntryPoint authEntryPoint;
  private OidcTokenVerifier oidcTokenVerifier;

  public AuthenticationFilter(
      TokenService tokenService,
      SettingsService settingsService,
      String basicPassword,
      AuthenticationEntryPoint authEntryPoint,
      OidcTokenVerifier oidcTokenVerifier,
      PasswordEncoder passwordEncoder) {
    super();
    this.tokenService = tokenService;
    this.settingsService = settingsService;
    this.passwordEncoder = passwordEncoder;
    this.encodedBasicPassword = passwordEncoder.encode(basicPassword);
    this.authEntryPoint = authEntryPoint;
    this.oidcTokenVerifier = oidcTokenVerifier;
  }

  /*
   * Filter to ensure the user is authenticated
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    LOGGER.debug("In AuthFilter()");
    try {
      Authentication authentication = null;

      // Rely on Authorization header and Bearer tokens. Fall back on the x-access-token header
      // and then a token in the URL param - some webhook senders can only set a URL, not
      // headers (see the constant declarations above for why these fallbacks are kept).
      if (req.getHeader(AUTHORIZATION_HEADER) != null) {
        if (req.getHeader(AUTHORIZATION_HEADER).matches(TOKEN_PATTERN)) {
          authentication = getTokenAuthentication(req.getHeader(AUTHORIZATION_HEADER));
        } else {
          authentication = getUserSessionAuthentication(req);
        }
      } else if (req.getHeader(X_ACCESS_TOKEN_HEADER) != null) {
        authentication = getTokenAuthentication(req.getHeader(X_ACCESS_TOKEN_HEADER));
      } else if (req.getParameter(TOKEN_URL_PARAM_NAME) != null) {
        authentication = getTokenAuthentication(req.getParameter(TOKEN_URL_PARAM_NAME));
      } else if (req.getHeader(X_FORWARDED_EMAIL) != null) {
        authentication = getGithubUserAuthentication(req);
      } else if (getSessionCookieValue(req) != null) {
        authentication = getTokenAuthentication(getSessionCookieValue(req));
      }

      if (authentication != null) {
        LOGGER.debug("AuthFilter() - authorized.");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(req, res);
      } else if (req.getServletPath().startsWith(PATH_AUTH_EXCHANGE)) {
        // The exchange endpoint's direct OIDC login path verifies the id_token itself and has no
        // proxy-forwarded identity to resolve here - permitAll in SecurityConfiguration lets an
        // unauthenticated request continue past the authorization layer too.
        chain.doFilter(req, res);
      } else {
        LOGGER.error("AuthFilter() - not authorized.");
        authEntryPoint.commence(
            req, res, new FlowAuthenticationException(BoomerangError.AUTH_REQUIRED, null));
      }
    } catch (final HttpClientErrorException ex) {
      LOGGER.error(ex);
      res.sendError(ex.getStatusCode().value());
    } catch (AccessDeniedException ex) {
      // Thrown by AuthCriteriaAuthorizationManager (method security) for a permission mismatch -
      // a plain 403, matching the retired SecurityInterceptor's raw response for the same case
      // exactly (no structured body; the mismatch itself is already logged/counted there).
      LOGGER.error(ex);
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    } catch (AuthenticationException ex) {
      // Covers both this filter's own identity resolution failures and
      // AuthCriteriaAuthorizationManager throwing for "no identity"/"scope not assignable" - the
      // same structured 401 entry point either way.
      LOGGER.error(ex);
      authEntryPoint.commence(
          req, res, new FlowAuthenticationException(BoomerangError.AUTH_REQUIRED, ex.getMessage()));
    }
  }

  /*
   * Extracts the opaque bfs_ value from the session cookie (SessionCookie.NAME), if present - the
   * additional identity source minted by POST /api/v2/auth/exchange, added alongside the existing
   * branches above, never in place of them.
   */
  private String getSessionCookieValue(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (SessionCookie.NAME.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  /*
   * Authorization Header Bearer Token
   *
   * Populated by the app via OAuth2_Proxy
   * TODO: figure out a way to ensure it comes via the OAuth2_Proxy
   */
  private UsernamePasswordAuthenticationToken getUserSessionAuthentication(
      HttpServletRequest request) // NOSONAR
      {
    final String token = request.getHeader(AUTHORIZATION_HEADER);

    boolean allowActivation = false;
    if (request.getServletPath().startsWith(PATH_ACTIVATE)
        || request.getServletPath().startsWith(PATH_AUTH_EXCHANGE)) {
      allowActivation = true;
    }

    boolean allowUserCreation = false;
    if (request.getServletPath().startsWith(PATH_PROFILE)
        || request.getServletPath().startsWith(PATH_AUTH_EXCHANGE)) {
      allowUserCreation = true;
    }

    if (token.startsWith("Bearer ")) {
      LOGGER.debug("AuthFilter() - " + token);
      JWTClaimsSet claims;
      try {
        // Cryptographically verified against the configured OIDC issuer's published JWKS - an
        // unsigned (PlainJWT) or wrongly-signed token is refused here, never trusted on its claims
        // alone. See OidcTokenVerifier.verifyBearerToken.
        claims = oidcTokenVerifier.verifyBearerToken(token.replace("Bearer ", ""));
      } catch (BoomerangException e) {
        LOGGER.error("AuthFilter() - Error verifying Bearer token: " + e.getMessage());
        return null;
      }
      LOGGER.debug("AuthFilter() - claims: " + claims.toString());
      String email = null;
      if (claims.getClaim("emailAddress") != null) {
        email = (String) claims.getClaim("emailAddress");
      } else if (claims.getClaim("email") != null) {
        email = (String) claims.getClaim("email");
      }

      String firstName = null;
      if (claims.getClaim("firstName") != null) {
        firstName = (String) claims.getClaim("firstName");
      } else if (claims.getClaim("given_name") != null) {
        firstName = (String) claims.getClaim("given_name");
      }

      String lastName = null;
      if (claims.getClaim("lastName") != null) {
        lastName = (String) claims.getClaim("lastName");
      } else if (claims.getClaim("family_name") != null) {
        lastName = (String) claims.getClaim("family_name");
      }

      if (email != null && !email.isBlank()) {
        final Token sessionToken =
            tokenService.createSessionToken(
                email, firstName, lastName, allowActivation, allowUserCreation);
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(email, null, authoritiesFor(sessionToken));
        authToken.setDetails(sessionToken);
        return authToken;
      }
    } else if (token.startsWith("Basic ")) {
      String base64Credentials =
          request.getHeader(AUTHORIZATION_HEADER).substring("Basic".length()).trim();

      LOGGER.debug("AuthFilter() - Basic : " + base64Credentials);
      byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
      String credentials = new String(credDecoded, StandardCharsets.UTF_8);

      String password = "";
      final String[] values = credentials.split(":", 2);
      String email = values[0];

      if (values.length > 1) {
        password = values[1];
      }

      // Constant-time via PasswordEncoder.matches - was a raw String.equals against the
      // configured password, timeable by an attacker probing byte-by-byte.
      if (!passwordEncoder.matches(password, encodedBasicPassword)) {
        return null;
      }

      if (email != null && !email.isBlank()) {
        final Token sessionToken =
            tokenService.createSessionToken(email, null, null, allowActivation, allowUserCreation);
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(email, password, authoritiesFor(sessionToken));
        authToken.setDetails(sessionToken);
        return authToken;
      }
    }
    return null;
  }

  /*
   * Validate and hoist Token Based Auth
   *
   * Handles the token coming from AUTHORIZATION_HEADER, X_ACCESS_TOKEN_HEADER or
   * TOKEN_URL_PARAM_NAME in that order
   */
  private Authentication getTokenAuthentication(String accessToken) {
    if (accessToken.startsWith("Bearer ")) {
      accessToken = accessToken.replace("Bearer ", "");
    }
    if (tokenService.validate(accessToken)) {
      Token token = tokenService.get(accessToken);
      if (token != null) {
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(
                token.getPrincipal(), null, authoritiesFor(token));
        authToken.setDetails(token);
        return authToken;
      }
    }
    return null;
  }

  /*
   * Validate and Bump GitHub Protected Auth
   */
  private Authentication getGithubUserAuthentication(HttpServletRequest request) {
    boolean allowActivation = false;
    if (request.getServletPath().startsWith(PATH_ACTIVATE)
        || request.getServletPath().startsWith(PATH_AUTH_EXCHANGE)) {
      allowActivation = true;
    }

    boolean allowUserCreation = false;
    if (request.getServletPath().startsWith(PATH_PROFILE)
        || request.getServletPath().startsWith(PATH_AUTH_EXCHANGE)) {
      allowUserCreation = true;
    }
    String email = request.getHeader(X_FORWARDED_EMAIL);
    String userName = request.getHeader(X_FORWARDED_USER);
    final Token token =
        tokenService.createSessionToken(email, userName, null, allowActivation, allowUserCreation);
    if (email != null && !email.isBlank()) {
      final UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(
              token.getPrincipal(), null, authoritiesFor(token));
      authToken.setDetails(token);
      return authToken;
    }
    return null;
  }

  /*
   * Utlity method for verifying requests are signed by Slack
   *
   * <h4>Specifications</h4> <ul> <li><a
   * href="https://api.slack.com/authentication/verifying-requests-from-slack">Verifying Requests
   * from Slack</a></li> </ul>
   */
  private Boolean verifySignature(String signature, String timestamp, String body) {
    String key =
        this.settingsService.getSettingConfig("extensions", "slack.signingSecret").getValue();
    LOGGER.debug("Key: " + key);
    LOGGER.debug("Slack Timestamp: " + timestamp);
    LOGGER.debug("Slack Body: " + body);
    Generator generator = new Generator(key);
    Verifier verifier = new Verifier(generator);
    LOGGER.debug("Slack Signature: " + signature);
    LOGGER.debug("Computed Signature: " + generator.generate(timestamp, body));
    return verifier.isValid(timestamp, body, signature);
  }

  /*
   * Maps a resolved Token's permission actions (e.g. "workflow/write") straight onto
   * GrantedAuthority, so Spring Security's own authorization machinery can act on them natively
   * instead of every check having to re-derive them from Token.getPermissions() by hand. Token
   * itself remains the source of truth - setDetails(Token) below is unchanged, and callers that
   * still read scope/relationship semantics off the Token keep doing so.
   */
  private static List<GrantedAuthority> authoritiesFor(Token token) {
    if (token == null || token.getPermissions() == null) {
      return List.of();
    }
    return token.getPermissions().stream()
        .filter(permission -> permission.getActions() != null)
        .flatMap(permission -> permission.getActions().stream())
        .distinct()
        .map(SimpleGrantedAuthority::new)
        .map(GrantedAuthority.class::cast)
        .toList();
  }

  @Override
  // TODO figure out why these aren't being applied in the SecurityConfig
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return path.startsWith("/error")
        || path.startsWith("/health")
        || path.startsWith("/api/docs")
        || path.equals(SecurityConfiguration.GITHUB_CALLBACK)
        || path.equals(SecurityConfiguration.AUTH_CONFIG);
  }
}

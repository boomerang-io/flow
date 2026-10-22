package io.boomerang.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.slack.api.app_backend.SlackSignature.Generator;
import com.slack.api.app_backend.SlackSignature.Verifier;
import io.boomerang.core.SettingsService;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * The Filter ensures that the user is Authenticated prior to the Interceptor which validates
 * Authorization
 *
 * Note: This cannot be auto marked as a Service/Component that Spring Boot would auto inject as
 * then it will apply to all routes
 */
@ConditionalOnProperty(name = "flow.authorization.enabled", havingValue = "true")
public class AuthenticationFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String X_FORWARDED_USER = "x-forwarded-user";
  private static final String X_FORWARDED_EMAIL = "x-forwarded-email";
  private static final String TOKEN_URL_PARAM_NAME = "access_token";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  //  private static final String X_SLACK_SIGNATURE = "X-Slack-Signature";
  //  private static final String X_SLACK_TIMESTAMP = "X-Slack-Request-Timestamp";
  private static final String PATH_ACTIVATE = "/api/v2/activate";
  private static final String PATH_PROFILE = "/api/v2/profile";
  private static final String TOKEN_PATTERN = "Bearer\\sbf._(.)+";

  private TokenService tokenService;
  private SettingsService settingsService;
  private String basicPassword;

  public AuthenticationFilter(
      TokenService tokenService, SettingsService settingsService, String basicPassword) {
    super();
    this.tokenService = tokenService;
    this.settingsService = settingsService;
    this.basicPassword = basicPassword;
  }

  /*
   * Filter to ensure the user is authenticated
   *
   * //DEPRECATED: X_ACCESS_TOKEN in favor of AUTHORIZATION_HEADER
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    LOGGER.debug("In AuthFilter()");
    try {
      Authentication authentication = null;

      // Rely on Authorization header and Bearer tokens
      // Fall back on token in URL param (some integrations can only set a URL and not the
      // headers
      if (req.getHeader(AUTHORIZATION_HEADER) != null) {
        if (req.getHeader(AUTHORIZATION_HEADER).matches(TOKEN_PATTERN)) {
          authentication = getTokenAuthentication(req.getHeader(AUTHORIZATION_HEADER));
        } else {
          authentication = getUserSessionAuthentication(req);
        }
      } else if (req.getParameter(TOKEN_URL_PARAM_NAME) != null) {
        authentication = getTokenAuthentication(req.getParameter(TOKEN_URL_PARAM_NAME));
      } else if (req.getHeader(X_FORWARDED_EMAIL) != null) {
        authentication = getGithubUserAuthentication(req);
      }

      if (authentication != null) {
        LOGGER.debug("AuthFilter() - authorized.");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(req, res);
      } else {
        LOGGER.error("AuthFilter() - not authorized.");
        res.sendError(401);
      }
    } catch (final HttpClientErrorException ex) {
      LOGGER.error(ex);
      res.sendError(ex.getRawStatusCode());
    } catch (AccessDeniedException | AuthenticationException ex) {
      LOGGER.error(ex);
      res.sendError(401);
    }
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
    if (request.getServletPath().startsWith(PATH_ACTIVATE)) {
      allowActivation = true;
    }

    boolean allowUserCreation = false;
    if (request.getServletPath().startsWith(PATH_PROFILE)) {
      allowUserCreation = true;
    }

    if (token.startsWith("Bearer ")) {
      LOGGER.debug("AuthFilter() - " + token);
      JWTClaimsSet claims;
      try {
        Object jwt = JWTParser.parse(token.replace("Bearer ", ""));
        if (jwt instanceof PlainJWT) {
          claims = ((PlainJWT) jwt).getJWTClaimsSet();
        } else if (jwt instanceof SignedJWT) {
          claims = ((SignedJWT) jwt).getJWTClaimsSet();
        } else {
          throw new IllegalArgumentException("Unsupported JWT type");
        }
      } catch (Exception e) {
        LOGGER.error("AuthFilter() - Error parsing Bearer token: " + e.getMessage());
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
        final List<GrantedAuthority> authorities = new ArrayList<>();
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(email, null, authorities);
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

      if (!basicPassword.equals(password)) {
        return null;
      }

      if (email != null && !email.isBlank()) {
        final Token sessionToken =
            tokenService.createSessionToken(email, null, null, allowActivation, allowUserCreation);
        final List<GrantedAuthority> authorities = new ArrayList<>();
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(email, password, authorities);
        authToken.setDetails(sessionToken);
        return authToken;
      }
    }
    return null;
  }

  /*
   * Validate and hoist Token Based Auth
   *
   * Handles the token coming from AUTHORIZATION_HEADER or TOKEN_URL_PARAM_NAME in
   * that order
   */
  private Authentication getTokenAuthentication(String accessToken) {
    if (accessToken.startsWith("Bearer ")) {
      accessToken = accessToken.replace("Bearer ", "");
    }
    if (tokenService.validate(accessToken)) {
      Token token = tokenService.get(accessToken);
      if (token != null) {
        final List<GrantedAuthority> authorities = new ArrayList<>();
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(token.getPrincipal(), null, authorities);
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
    if (request.getServletPath().startsWith(PATH_ACTIVATE)) {
      allowActivation = true;
    }

    boolean allowUserCreation = false;
    if (request.getServletPath().startsWith(PATH_PROFILE)) {
      allowUserCreation = true;
    }
    String email = request.getHeader(X_FORWARDED_EMAIL);
    String userName = request.getHeader(X_FORWARDED_USER);
    final Token token =
        tokenService.createSessionToken(email, userName, null, allowActivation, allowUserCreation);
    if (email != null && !email.isBlank()) {
      final List<GrantedAuthority> authorities = new ArrayList<>();
      final UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(token.getPrincipal(), null, authorities);
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

  @Override
  // TODO figure out why these aren't being applied in the SecurityConfig
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return path.startsWith("/error")
        || path.startsWith("/health")
        || path.startsWith("/api/docs")
        || path.startsWith("/internal");
  }
}

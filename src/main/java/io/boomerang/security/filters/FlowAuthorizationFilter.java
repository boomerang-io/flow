package io.boomerang.security.filters;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.util.StreamUtils;
import com.slack.api.app_backend.SlackSignature.Generator;
import com.slack.api.app_backend.SlackSignature.Verifier;
import io.boomerang.mongo.entity.FlowUserEntity;
import io.boomerang.mongo.entity.TokenEntity;
import io.boomerang.mongo.model.TokenScope;
import io.boomerang.mongo.service.FlowSettingsService;
import io.boomerang.mongo.service.FlowTokenService;
import io.boomerang.mongo.service.FlowUserService;
import io.boomerang.security.AuthorizationException;
import io.boomerang.security.model.FlowAuthenticationToken;
import io.boomerang.security.model.GlobalToken;
import io.boomerang.security.model.TeamToken;
import io.boomerang.security.model.Token;
import io.boomerang.security.model.UserToken;
import io.boomerang.security.util.MultiReadHttpServletRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.impl.DefaultJwtParser;

public class FlowAuthorizationFilter extends BasicAuthenticationFilter {

  private static final String X_FORWARDED_USER = "x-forwarded-user";
  private static final String X_FORWARDED_EMAIL = "x-forwarded-email";
  private static final String X_ACCESS_TOKEN = "x-access-token";
  private static final String TOKEN_URL_PARAM_NAME = "access_token";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String X_SLACK_SIGNATURE = "X-Slack-Signature";
  private static final String X_SLACK_TIMESTAMP = "X-Slack-Request-Timestamp";
  private String basicPassword;

  private static final Logger LOGGER = LogManager.getLogger();
  private FlowTokenService tokenService;
  private FlowUserService flowUserService;
  private FlowSettingsService flowSettingsService;
  
  public FlowAuthorizationFilter(FlowTokenService tokenService, AuthenticationManager authManager,
      FlowUserService flowUserService, FlowSettingsService flowSettingsService, String basicPassword) {
    super(authManager);
    this.tokenService = tokenService;
    this.basicPassword = basicPassword;
    this.flowUserService = flowUserService;
    this.flowSettingsService = flowSettingsService; 
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
      FilterChain chain) throws IOException, ServletException {
    try {
      MultiReadHttpServletRequest multiReadRequest = new MultiReadHttpServletRequest(req);
      Authentication authentication = null;
      if (multiReadRequest.getHeader(AUTHORIZATION_HEADER) != null) {
      	LOGGER.info("Found AUTHORIZATION_HEADER");
        authentication = getUserAuthentication(req);
      } else if (multiReadRequest.getHeader(X_FORWARDED_EMAIL) != null) {
      	LOGGER.info("Found X_FORWARDED_EMAIL");
        authentication = getGithubUserAuthentication(req);
      }
      else if (multiReadRequest.getHeader(X_ACCESS_TOKEN) != null || req.getParameter(TOKEN_URL_PARAM_NAME) != null) {
      	LOGGER.info("Found X_ACCESS_TOKEN/TOKEN_URL_PARAM_NAME");
        authentication = getTokenBasedAuthentication(req);
      }

      if (multiReadRequest.getHeader(X_SLACK_SIGNATURE) != null) {
      	LOGGER.info("Found X_SLACK_SIGNATURE");
        InputStream inputStream = multiReadRequest.getInputStream();
        byte[] body = StreamUtils.copyToByteArray(inputStream);
        String signature = multiReadRequest.getHeader(X_SLACK_SIGNATURE);
        String timestamp = multiReadRequest.getHeader(X_SLACK_TIMESTAMP);
        
        if (!verifySignature(signature, timestamp, new String(body))) {
          LOGGER.error("Fail SlackSignatureVerificationFilter()");
          res.sendError(401);
          return;
        }
      }

      SecurityContextHolder.getContext().setAuthentication(authentication);
      chain.doFilter(multiReadRequest, res);
    } catch (final AuthorizationException e) {
      LOGGER.error(e);
    }
  }

  private Authentication getGithubUserAuthentication(HttpServletRequest req) {
    String email = req.getHeader(X_FORWARDED_EMAIL);
    String userName = req.getHeader(X_FORWARDED_USER);
    final UserToken userDetails = new UserToken(email, userName, "");
    if (email != null && !email.isBlank()) {
      final List<GrantedAuthority> authorities = new ArrayList<>();
      final UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(email, email, authorities);
      authToken.setDetails(userDetails);
      return authToken;
    }
    return null;
  }

  private Authentication getTokenBasedAuthentication(HttpServletRequest request) {
    String xAccessToken = request.getHeader(X_ACCESS_TOKEN) != null ? request.getHeader(X_ACCESS_TOKEN) : 
      request.getParameter(TOKEN_URL_PARAM_NAME);
    TokenEntity token = tokenService.getAccessToken(xAccessToken);
    if (token != null) {
      final List<GrantedAuthority> authorities = new ArrayList<>();
      if (token.getScope() == TokenScope.global) {
      	LOGGER.error("Token: TokenScope.global");
        Token t = new GlobalToken();
        final FlowAuthenticationToken authToken = new FlowAuthenticationToken(authorities);
        authToken.setDetails(t);
        return authToken;
      } else if (token.getScope() == TokenScope.team) {
      	LOGGER.error("Token: TokenScope.team");
        TeamToken t = new TeamToken();
        String teamId = token.getTeamId();
        t.setTeamId(teamId);
        
        final FlowAuthenticationToken authToken = new FlowAuthenticationToken(authorities);
        authToken.setDetails(t);
        return authToken;
      } else if (token.getScope() == TokenScope.user) {
      	LOGGER.error("Token: TokenScope.user");
        String userId = token.getUserId();
        Optional<FlowUserEntity> user = flowUserService.getUserById(userId);
        if (user.isPresent()) {
          FlowUserEntity flowUser = user.get();
          String name = flowUser.getName();
          String email = flowUser.getEmail();
          final UserToken userDetails = new UserToken(userId, name, "");
          if (userId != null) {
            final UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(email, null, authorities);
            authToken.setDetails(userDetails);
            return authToken;
          }
        }
      }
    }
    return null;
  }

  private UsernamePasswordAuthenticationToken getUserAuthentication(HttpServletRequest request) // NOSONAR
  {
    final String token = request.getHeader(AUTHORIZATION_HEADER);
    
    if (token.startsWith("Bearer ")) {
      final String jws = token.replace("Bearer ", "");
      Claims claims;
      String withoutSignature = jws.substring(0, jws.lastIndexOf('.') + 1);
      try {
        claims = (Claims) new DefaultJwtParser().parse(withoutSignature).getBody();
      } catch (ExpiredJwtException e) {
        claims = e.getClaims();

      }
      String userId = null;
      if (claims.get("emailAddress") != null) {
        userId = (String) claims.get("emailAddress");
      } else if (claims.get("email") != null) {
        userId = (String) claims.get("email");
      }

      String firstName = null;
      if (claims.get("firstName") != null) {
        firstName = (String) claims.get("firstName");
      } else if (claims.get("given_name") != null) {
        firstName = (String) claims.get("given_name");
      }

      String lastName = null;
      if (claims.get("lastName") != null) {
        lastName = (String) claims.get("lastName");
      } else if (claims.get("family_name") != null) {
        lastName = (String) claims.get("family_name");
      }

      firstName = santaize(firstName);
      lastName = santaize(lastName);

      final UserToken userDetails = new UserToken(userId, firstName, lastName);
      if (userId != null) {
        final List<GrantedAuthority> authorities = new ArrayList<>();
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(userId, null, authorities);
        authToken.setDetails(userDetails);

        return authToken;

      }
      return null;
    } else if (token.startsWith("Basic ")) {

      
      
      String base64Credentials =
          request.getHeader(AUTHORIZATION_HEADER).substring("Basic".length()).trim();
      
      byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
      String credentials = new String(credDecoded, StandardCharsets.UTF_8);

      String password = "";
      final String[] values = credentials.split(":", 2);
      String username = values[0];
   
      if (values.length > 1) {
        password = values[1];
      }
      
      if (!basicPassword.equals(password)) {
        return null;
      }

      final UserToken userDetails = new UserToken(username, username, "");

      if (username != null) {
        final List<GrantedAuthority> authorities = new ArrayList<>();
        final UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(username, password, authorities);
        authToken.setDetails(userDetails);
        return authToken;
      }
      return null;
    }
    return null;
  }

  private String santaize(String value) {
    if (StringUtils.isBlank(value)) {
      return value;
    }
    String cleanString = value;
    try {
      cleanString = java.net.URLDecoder.decode(value, "UTF-8");
    } catch (final UnsupportedEncodingException e) {
      return value;
    }
    cleanString = cleanString.toLowerCase();
    cleanString = WordUtils.capitalizeFully(cleanString);
    return cleanString;
  }
  
/*
* Utlity method for verifying requests are signed by Slack
* 
* <h4>Specifications</h4>
* <ul>
* <li><a href="https://api.slack.com/authentication/verifying-requests-from-slack">Verifying Requests from Slack</a></li>
* </ul>
*/
private Boolean verifySignature(String signature, String timestamp, String body) {
 String key = this.flowSettingsService.getConfiguration("extensions", "slack.signingSecret").getValue();
 LOGGER.debug("Key: " + key);
 LOGGER.debug("Slack Timestamp: " + timestamp);
 LOGGER.debug("Slack Body: " + body);
 Generator generator = new Generator(key);
 Verifier verifier = new Verifier(generator);
 LOGGER.debug("Slack Signature: " + signature);
 LOGGER.debug("Computed Signature: " + generator.generate(timestamp, body));
 return verifier.isValid(timestamp, body, signature);
}

}

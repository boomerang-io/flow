package io.boomerang.dispatcher;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.enums.TokenTypePrefix;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.UnauthenticatedGlobalToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the dispatcher wire ({@code /dispatcher/**}) with a real Flow token — a global-scope
 * token carrying a machine {@code actorKind} (see {@link
 * io.boomerang.core.security.enums.TokenActorKind}), minted through the existing token API. No
 * new {@code AuthScope} value or token prefix was added: the dispatcher token is deliberately an
 * ordinary {@code bfg_} global token, an orthogonal discriminator field is the only difference
 * from any other global token.
 *
 * <p>Every request its filter chain claims is checked. {@link DispatcherSecurityConfiguration}'s
 * matcher is the one statement of which paths those are, so this filter keeps no path list of its
 * own that could drift from it.
 *
 * <p>Validation order: a cheap prefix-regex shape gate ({@link
 * TokenTypePrefix#isFlowToken}) runs BEFORE any Mongo lookup, so a bearer that isn't even shaped
 * like a Flow token never hits the database. A shaped-but-unknown/expired/wrong-kind token is
 * looked up once ({@link TokenService#validateActorToken}) and rejected. On success, {@code
 * lastUsedAt} is stamped (throttled — see {@link TokenService#touchLastUsed}) and the token becomes
 * the request's identity - the one the same bearer carries on {@code /api/v2} - so code a
 * dispatcher route reaches reads its caller like any other route's code does. The chain's {@code
 * SecurityContextHolderFilter} clears it when the request ends.
 *
 * <p><b>Dev/test escape hatch:</b> {@code flow.dispatcher.auth.enabled} (default {@code true}) —
 * setting it {@code false} checks nothing, and the request acts as the synthetic {@link
 * UnauthenticatedGlobalToken} a security-off request has. This replaces the old {@code
 * flow.dispatcher.token} shared-secret
 * property, which no longer exists: {@code service-dispatcher}'s configured bearer value
 * ({@code flow.engine.dispatcher.token}) is unchanged code-wise, it just now needs to hold a real
 * minted token instead of an arbitrary shared string.
 */
public class DispatcherAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final TokenService tokenService;
  private final boolean authEnabled;

  public DispatcherAuthFilter(TokenService tokenService, boolean authEnabled) {
    this.tokenService = tokenService;
    this.authEnabled = authEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // Dev/test escape hatch: nothing identifies the caller, so it acts as the system.
    if (!authEnabled) {
      setIdentity(new UnauthenticatedGlobalToken());
      filterChain.doFilter(request, response);
      return;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    String presented = header.substring(BEARER_PREFIX.length());
    // Cheap pre-DB gate: a bearer that isn't Flow-token-shaped never hits Mongo.
    if (!TokenTypePrefix.isFlowToken(presented)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    Optional<TokenEntity> token = tokenService.validateActorToken(presented);
    if (token.isEmpty()) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    tokenService.touchLastUsed(token.get());
    Token identity = new Token(token.get());
    identity.setValid(true);
    setIdentity(identity);
    filterChain.doFilter(request, response);
  }

  private static void setIdentity(Token identity) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(identity.getPrincipal(), null, List.of());
    authentication.setDetails(identity);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}

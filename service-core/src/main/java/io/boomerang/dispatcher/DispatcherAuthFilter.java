package io.boomerang.dispatcher;

import io.boomerang.core.TokenService;
import io.boomerang.core.entity.TokenEntity;
import io.boomerang.core.enums.TokenTypePrefix;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the worker-facing dispatcher endpoints ({@code /api/v1/dispatcher/**}) with a real Flow
 * token (T6-1) — a global-scope token carrying a machine {@code actorKind} (see {@link
 * io.boomerang.core.security.enums.TokenActorKind}), minted through the existing token API. No
 * new {@code AuthScope} value or token prefix was added: the dispatcher token is deliberately an
 * ordinary {@code bfg_} global token, an orthogonal discriminator field is the only difference
 * from any other global token (ARCHIE pattern, least deviation from the proven model).
 *
 * <p>Validation order mirrors ARCHIE: a cheap prefix-regex shape gate ({@link
 * TokenTypePrefix#isFlowToken}) runs BEFORE any Mongo lookup, so a bearer that isn't even shaped
 * like a Flow token never hits the database. A shaped-but-unknown/expired/wrong-kind token is
 * looked up once ({@link TokenService#validateActorToken}) and rejected. On success, {@code
 * lastUsedAt} is stamped (throttled — see {@link TokenService#touchLastUsed}).
 *
 * <p><b>Dev/test escape hatch:</b> {@code flow.dispatcher.auth.enabled} (default {@code true}) —
 * setting it {@code false} makes the filter a complete no-op, exactly like the interim filter's
 * old blank-token permit path. This replaces the old {@code flow.dispatcher.token} shared-secret
 * property, which no longer exists: {@code service-agent}'s configured bearer value
 * ({@code flow.engine.dispatcher.token}) is unchanged code-wise, it just now needs to hold a real
 * minted token instead of an arbitrary shared string.
 *
 * <p>Every other path is untouched and stays {@code permitAll} exactly as before.
 */
public class DispatcherAuthFilter extends OncePerRequestFilter {

  private static final String DISPATCHER_PATH_PREFIX = "/api/v1/dispatcher/";
  private static final String BEARER_PREFIX = "Bearer ";

  private final TokenService tokenService;
  private final boolean authEnabled;

  public DispatcherAuthFilter(TokenService tokenService, boolean authEnabled) {
    this.tokenService = tokenService;
    this.authEnabled = authEnabled;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Only guard the dispatcher endpoints; everything else stays permitAll.
    return !request.getRequestURI().startsWith(DISPATCHER_PATH_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // Dev/test escape hatch: permit, do nothing.
    if (!authEnabled) {
      filterChain.doFilter(request, response);
      return;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    String presented = header.substring(BEARER_PREFIX.length());
    // Cheap pre-DB gate (ARCHIE pattern): a bearer that isn't Flow-token-shaped never hits Mongo.
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
    filterChain.doFilter(request, response);
  }
}

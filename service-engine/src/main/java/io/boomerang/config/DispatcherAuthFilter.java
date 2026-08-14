package io.boomerang.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the worker-facing dispatcher endpoints ({@code /api/v1/dispatcher/**}) with a configured
 * static shared-secret bearer token.
 *
 * <p>Enforce-when-configured, permit-when-blank: with no {@code flow.dispatcher.token} set (local
 * dev and the test suite) the filter is a no-op; once a token is configured (production) every
 * dispatcher request must carry a matching {@code Authorization: Bearer <token>} header or is
 * rejected with 401. We control both ends (engine + worker), so there is no shadow phase.
 *
 * <p>Every other path is untouched and stays {@code permitAll} exactly as before.
 */
public class DispatcherAuthFilter extends OncePerRequestFilter {

  private static final String DISPATCHER_PATH_PREFIX = "/api/v1/dispatcher/";
  private static final String BEARER_PREFIX = "Bearer ";

  private final String token;

  public DispatcherAuthFilter(String token) {
    this.token = token;
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
    // Blank token = dev/test default: permit, do nothing.
    if (!StringUtils.hasText(token)) {
      filterChain.doFilter(request, response);
      return;
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX) || !matches(header)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private boolean matches(String authorizationHeader) {
    String presented = authorizationHeader.substring(BEARER_PREFIX.length());
    // Constant-time comparison to avoid leaking the token via response timing.
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }
}

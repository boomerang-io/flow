package io.boomerang.dispatcher;

import io.boomerang.dispatcher.sdk.Dispatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The log route ({@code /api/v1/logs/**}) is read by the engine alone, with the bearer this
 * dispatcher registered ({@link Dispatcher#logsToken()}). Anything else is a bare 401: a pod log
 * is unscrubbed, and the engine is the only reader that scrubs it. Every other route of this
 * process is untouched.
 */
@Component
public class LogAuthFilter extends OncePerRequestFilter {

  static final String LOGS_PATH = "/api/v1/logs";
  private static final String BEARER_PREFIX = "Bearer ";

  private final ObjectProvider<Dispatcher> dispatcher;

  public LogAuthFilter(ObjectProvider<Dispatcher> dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path == null || !(path.equals(LOGS_PATH) || path.startsWith(LOGS_PATH + "/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (accepts(request.getHeader(HttpHeaders.AUTHORIZATION))) {
      filterChain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
  }

  boolean accepts(String authorization) {
    Dispatcher registered = dispatcher.getIfAvailable();
    String expected = (registered != null) ? registered.logsToken() : null;
    if (expected == null
        || expected.isBlank()
        || authorization == null
        || !authorization.startsWith(BEARER_PREFIX)) {
      return false;
    }
    return MessageDigest.isEqual(
        authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8));
  }
}

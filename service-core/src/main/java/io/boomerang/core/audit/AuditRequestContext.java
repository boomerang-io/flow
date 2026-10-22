package io.boomerang.core.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Snapshot of the current HTTP request for audit attribution. Must be captured on the request
 * thread ({@link RequestContextHolder} is a thread-local); all fields are null for non-HTTP call
 * sites (schedules, engine transitions).
 *
 * <p>Source IP takes the first {@code X-Forwarded-For} entry when present — trustworthy only
 * behind the gateway that rewrites the header (the documented deployment shape,
 * docker/gateway/nginx.conf) — else the socket address.
 */
record AuditRequestContext(String sourceIp, String userAgent, String method, String path) {

  private static final AuditRequestContext EMPTY = new AuditRequestContext(null, null, null, null);

  static AuditRequestContext capture() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return EMPTY;
    }
    HttpServletRequest request = attrs.getRequest();
    return new AuditRequestContext(
        sourceIp(request),
        request.getHeader("User-Agent"),
        request.getMethod(),
        request.getRequestURI());
  }

  private static String sourceIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }
}

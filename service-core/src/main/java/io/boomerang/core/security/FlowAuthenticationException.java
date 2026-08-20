package io.boomerang.core.security;

import io.boomerang.common.error.BoomerangError;
import org.springframework.security.core.AuthenticationException;

/**
 * Carries a {@link BoomerangError} through Spring Security's {@link AuthenticationException}
 * machinery so the delegated entry point (see {@link DelegatedAuthenticationEntryPoint}) can
 * render the platform's standard {@code RestErrorResponse} body - code, reason, message - instead
 * of the framework default, and so the response distinguishes WHY authentication failed (§5,
 * specifications/authentication.md) rather than a bare 401.
 */
public class FlowAuthenticationException extends AuthenticationException {

  private static final long serialVersionUID = 1L;

  private final BoomerangError error;

  public FlowAuthenticationException(BoomerangError error, String message) {
    super(message);
    this.error = error;
  }

  public BoomerangError getError() {
    return error;
  }
}

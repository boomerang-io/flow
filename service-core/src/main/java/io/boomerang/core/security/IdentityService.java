package io.boomerang.core.security;

import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.model.Token;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Helpers to retrieve the idenity of the current principal from the security context.
 *
 * <p><b>No Token on the SecurityContext (e.g. {@code flow.security.enabled=false} -
 * {@code AuthenticationFilter} is {@code @ConditionalOnProperty} on that flag and never loads, so
 * no request ever attaches a {@link Token}):</b> {@link #getCurrentIdentity()} already returned
 * {@code null} for this case; {@link #getCurrentPrincipal()} and {@link #getCurrentScope()} now
 * also return {@code null} instead of NPE-ing on it, rather than inventing a placeholder identity.
 * This mirrors how the rest of the codebase already treats "no principal": {@code
 * RelationshipService.check()}/{@code filter()} have an explicit no-principal branch that allows
 * unscoped access (there is nothing to narrow by), and {@code TokenService.resolveGrantCeiling()}
 * mirrors it for permission ceilings. Those are data-scoping decisions the CALLER is best placed
 * to make - IdentityService itself has no such context, so it just reports the honest answer (no
 * identity) and leaves "what does that mean for me" to the caller, same as {@code
 * getCurrentIdentity()} already does. {@code SecurityInterceptor.preHandle()} was already written
 * to treat a {@code null} scope as a real, meaningful signal (an AuthN/AuthZ mismatch) - it just
 * never actually received one because this class NPE'd first.
 */
@Service
public class IdentityService {

  private static final Logger LOGGER = LogManager.getLogger();

  public String getCurrentPrincipal() {
    Token token = this.getCurrentIdentity();
    return token == null ? null : token.getPrincipal();
  }

  public AuthScope getCurrentScope() {
    Token token = this.getCurrentIdentity();
    return token == null ? null : token.getType();
  }

  public Token getCurrentIdentity() {
    if (SecurityContextHolder.getContext() != null
        && SecurityContextHolder.getContext().getAuthentication() != null
        && SecurityContextHolder.getContext().getAuthentication().getDetails() != null
        && SecurityContextHolder.getContext().getAuthentication().getDetails() instanceof Token) {
      Object details = SecurityContextHolder.getContext().getAuthentication().getDetails();
      return (Token) details;
    } else {
      return null;
    }
  }
}

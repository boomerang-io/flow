package io.boomerang.core.security;

import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.model.Token;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Helpers to retrieve the identity of the current principal from the security context.
 *
 * <p><b>An identity is always established for a served request.</b> When {@code
 * flow.security.enabled=true}, {@code AuthenticationFilter} attaches a real {@link Token} or 401s.
 * When it is {@code false} (local-dev/E2E, and the default for {@code flow.mode=engine}), {@code
 * UnauthenticatedGlobalAuthenticationFilter} attaches an {@link
 * io.boomerang.core.security.UnauthenticatedGlobalToken} instead of leaving the context empty -
 * following Spring Security's own rationale for {@code AnonymousAuthenticationToken} ("classes can
 * be authored more robustly if they know the SecurityContextHolder always contains an
 * Authentication object, and never null"). Background work that runs off a request thread hoists
 * its own token the same way (see {@code ScheduleJob}).
 *
 * <p>Consequently {@link #getCurrentPrincipal()} and {@link #getCurrentScope()} dereference the
 * identity directly - the "no principal" branches they used to carry are gone, along with the
 * ones in {@code RelationshipService.check()}/{@code filter()} and {@code UserService} that each
 * invented a different meaning for "nobody is here".
 *
 * <p>{@link #getCurrentIdentity()} remains {@code null}-returning: it is the raw accessor, and
 * under {@code flow.security.enabled=true} there genuinely are routes that bypass {@code
 * AuthenticationFilter} entirely ({@code shouldNotFilter}: {@code /health}, {@code /api/docs}, the
 * GitHub callback; plus the {@code permitAll} auth-exchange path). None of those carry an {@code
 * @AuthCriteria}, which is why {@link AuthCriteriaAuthorizationManager} tests this accessor for
 * {@code null} to catch exactly that misconfiguration and answer a clean 401.
 */
@Service
public class IdentityService {

  private static final Logger LOGGER = LogManager.getLogger();

  public String getCurrentPrincipal() {
    return this.getCurrentIdentity().getPrincipal();
  }

  public AuthScope getCurrentScope() {
    return this.getCurrentIdentity().getType();
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

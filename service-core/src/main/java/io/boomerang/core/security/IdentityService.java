package io.boomerang.core.security;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.core.security.enums.PermissionScope;
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
 * When it is {@code false} (local-dev/E2E, and an embedded engine), {@code
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

  /**
   * Insist the identity holds a platform-wide grant for the resource and action: a permission of
   * {@code global} scope whose action matches, the resource or action wildcarded or not. A route
   * that reads across workspaces, such as the audit and the platform-wide Insights routes, calls
   * this after {@code @AuthCriteria} admitted the token class, since that check matches the
   * permission string without regard to its scope.
   */
  public void requireGlobalGrant(PermissionResource resource, PermissionAction action) {
    Token identity = getCurrentIdentity();
    String required =
        "(\\*{2}|" + resource.getLabel() + ")\\/(\\*{2}|" + action.getLabel() + ")";
    if (identity == null
        || identity.getPermissions().stream()
            .filter(permission -> PermissionScope.global.equals(permission.getScope()))
            .flatMap(permission -> permission.getActions().stream())
            .noneMatch(candidate -> candidate.matches(required))) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
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

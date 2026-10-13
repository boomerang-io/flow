package io.boomerang.core.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Installs the {@link UnauthenticatedGlobalToken} on the {@code SecurityContext} so that {@code
 * IdentityService.getCurrentIdentity()} is <b>never null</b>, even when {@code
 * flow.security.enabled=false}.
 *
 * <p>Registered only by {@link SecurityDisabledConfiguration} via {@code http.anonymous(...)} (the
 * {@code SecurityDisabledCondition} complement of {@code SecurityConfiguration}), so when security
 * is enabled this filter is not in the chain at all and {@code AuthenticationFilter}'s real-token
 * path is completely untouched.
 *
 * <p>This extends Spring Security's own {@link AnonymousAuthenticationFilter} rather than
 * reimplementing it: that class's {@code doFilter()} already installs its result only when the
 * {@code SecurityContext} carries no {@code Authentication} yet - exactly the "populate only if
 * empty" guard the previous hand-rolled version reimplemented by hand, needed because the {@code
 * /api/v1/**} dispatcher chain ({@code DispatcherSecurityConfiguration}, {@code @Order(1)}) runs
 * ahead of the {@code @Order(2)} chain this filter belongs to. The only customisation required is
 * {@link #createAuthentication(HttpServletRequest)} - WHAT gets installed, never WHEN.
 */
public class UnauthenticatedGlobalAuthenticationFilter extends AnonymousAuthenticationFilter {

  private static final String KEY = "flow-security-disabled";

  // AnonymousAuthenticationFilter's constructor requires a non-empty authorities list, but
  // createAuthentication() below is fully overridden and never reads this field - the
  // Authentication it actually installs carries an EMPTY authority list, byte-for-byte the same
  // as the previous hand-rolled filter.
  private static final List<GrantedAuthority> UNUSED_SUPERCLASS_PLACEHOLDER =
      List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"));

  public UnauthenticatedGlobalAuthenticationFilter() {
    super(KEY, UnauthenticatedGlobalToken.PRINCIPAL, UNUSED_SUPERCLASS_PLACEHOLDER);
  }

  @Override
  protected Authentication createAuthentication(HttpServletRequest request) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            UnauthenticatedGlobalToken.PRINCIPAL, null, List.of());
    authentication.setDetails(new UnauthenticatedGlobalToken());
    return authentication;
  }
}

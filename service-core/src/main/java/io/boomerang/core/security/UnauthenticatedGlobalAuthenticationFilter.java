package io.boomerang.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Installs the {@link UnauthenticatedGlobalToken} on the {@code SecurityContext} so that {@code
 * IdentityService.getCurrentIdentity()} is <b>never null</b>, even when {@code
 * flow.security.enabled=false}.
 *
 * <p>Registered only by {@link SecurityDisabledConfiguration} (the {@code
 * SecurityDisabledCondition} complement of {@code SecurityConfiguration}), so when security is
 * enabled this filter is not in the chain at all and {@code AuthenticationFilter}'s real-token
 * path is completely untouched.
 *
 * <p>It is written as "populate only if empty" rather than an unconditional overwrite: the {@code
 * /api/v1/**} dispatcher chain ({@code DispatcherSecurityConfiguration}, {@code @Order(1)}) runs
 * ahead of the {@code @Order(2)} chain this filter belongs to and may already have authenticated a
 * real dispatcher token via {@code DispatcherAuthFilter}. That identity must win - this filter only
 * fills the gap where nothing else established one.
 */
public class UnauthenticatedGlobalAuthenticationFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!hasIdentity()) {
      final List<GrantedAuthority> authorities = List.of();
      final UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(
              UnauthenticatedGlobalToken.PRINCIPAL, null, authorities);
      authentication.setDetails(new UnauthenticatedGlobalToken());
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    chain.doFilter(request, response);
  }

  /**
   * Mirrors {@code IdentityService.getCurrentIdentity()}'s own test: an {@code Authentication}
   * whose {@code details} is not a {@code Token} is, to every consumer in this codebase, the same
   * as no identity at all.
   */
  private boolean hasIdentity() {
    Authentication authentication =
        SecurityContextHolder.getContext() == null
            ? null
            : SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.getDetails() instanceof io.boomerang.core.model.Token;
  }
}

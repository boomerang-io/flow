package io.boomerang.dispatcher;

import io.boomerang.core.TokenService;
import io.boomerang.dispatcher.DispatcherAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Owns the dispatcher wire ({@code /dispatcher/**}) as a separate, higher-priority {@link
 * SecurityFilterChain} scoped via {@code securityMatcher}, evaluated before {@link
 * SecurityConfiguration} / {@link SecurityDisabledConfiguration} (see {@code @Order}). It applies
 * whatever {@code flow.security.enabled} says: every request the chain claims must carry a
 * dispatcher token, checked by {@link DispatcherAuthFilter} - see that class's javadoc for the
 * {@code flow.dispatcher.auth.enabled} dev/test escape hatch. The filter keeps no path list of its
 * own, so no route can sit inside the chain and outside the check.
 */
@Configuration
public class DispatcherSecurityConfiguration {

  private static final String DISPATCHER_PATTERN = "/dispatcher/**";

  private final TokenService tokenService;

  @Value("${flow.dispatcher.auth.enabled:true}")
  private boolean dispatcherAuthEnabled;

  public DispatcherSecurityConfiguration(TokenService tokenService) {
    this.tokenService = tokenService;
  }

  @Bean
  @Order(1)
  SecurityFilterChain dispatcherFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(DISPATCHER_PATTERN)
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .addFilterBefore(
            new DispatcherAuthFilter(tokenService, dispatcherAuthEnabled),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}

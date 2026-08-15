package io.boomerang.dispatcher;

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
 * Merged from service-engine's {@code config.SecurityConfig} (E8.2a - physically merging
 * service-engine into service-core, DD-05).
 *
 * <p>Owns the engine v1 / dispatcher surface ({@code /api/v1/**}) as a SEPARATE, higher-priority
 * {@link SecurityFilterChain} scoped via {@code securityMatcher}, evaluated BEFORE {@link
 * SecurityConfiguration} / {@link SecurityDisabledConfiguration} (see {@code @Order}). This
 * preserves service-engine's pre-merge security posture exactly and independently of flow's
 * {@code flow.security.enabled} setting: the v1 surface stays network-protected permitAll, with
 * the worker-facing dispatcher paths ({@code /api/v1/dispatcher/**}) additionally gated by
 * {@link DispatcherAuthFilter} once {@code flow.dispatcher.token} is configured.
 */
@Configuration
public class DispatcherSecurityConfiguration {

  private static final String V1_PATTERN = "/api/v1/**";

  @Value("${flow.dispatcher.token:}")
  private String dispatcherToken;

  @Bean
  @Order(1)
  SecurityFilterChain dispatcherFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(V1_PATTERN)
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .addFilterBefore(
            new DispatcherAuthFilter(dispatcherToken), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}

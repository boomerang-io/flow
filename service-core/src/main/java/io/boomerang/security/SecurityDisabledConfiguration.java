package io.boomerang.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(name = "flow.auth.enabled", havingValue = "false")
public class SecurityDisabledConfiguration {

  // @Order required now that DispatcherSecurityConfiguration's /api/v1/** chain (E8.2a merge)
  // also lives in this context: it must evaluate FIRST (lower value), this chain evaluates last.
  @Bean
  @Order(2)
  SecurityFilterChain unauthenticatedFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        .build();
  }
}

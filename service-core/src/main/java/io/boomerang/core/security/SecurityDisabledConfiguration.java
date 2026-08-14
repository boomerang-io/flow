package io.boomerang.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// A5/H6: complement of SecurityConfiguration's AuthEnabledCondition - see FlowSecurityProperties.
@Configuration
@Conditional(AuthDisabledCondition.class)
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

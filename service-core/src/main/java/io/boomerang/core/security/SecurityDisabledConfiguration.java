package io.boomerang.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

// A5/H6: complement of SecurityConfiguration's SecurityEnabledCondition - see
// FlowSecurityProperties.
@Configuration
@Conditional(SecurityDisabledCondition.class)
public class SecurityDisabledConfiguration {

  // @Order required now that DispatcherSecurityConfiguration's /api/v1/** chain (E8.2a merge)
  // also lives in this context: it must evaluate FIRST (lower value), this chain evaluates last.
  @Bean
  @Order(2)
  SecurityFilterChain unauthenticatedFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
        // Security is off, but an identity is still ESTABLISHED rather than left absent, so
        // IdentityService.getCurrentIdentity() is never null and downstream consumers (audit in
        // particular) have a real actor to record. See UnauthenticatedGlobalToken. Native
        // http.anonymous(), with a custom AnonymousAuthenticationFilter supplying WHAT gets
        // installed - Spring Security's own filter already owns WHEN (only if the SecurityContext
        // has no Authentication yet).
        .anonymous(
            anonymous ->
                anonymous.authenticationFilter(new UnauthenticatedGlobalAuthenticationFilter()))
        .build();
  }
}

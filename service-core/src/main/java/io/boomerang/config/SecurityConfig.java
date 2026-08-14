package io.boomerang.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfig {

  // Static shared-secret bearer token guarding /api/v1/dispatcher/**. Blank = permit (dev/test).
  @Value("${flow.dispatcher.token:}")
  private String dispatcherToken;

  @Bean
  public SecurityFilterChain configure(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests((authz) -> authz.anyRequest().permitAll())
        .addFilterBefore(
            new DispatcherAuthFilter(dispatcherToken), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}

package io.boomerang.security;

import io.boomerang.core.TokenService;
import io.boomerang.core.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@ConditionalOnProperty(name = "flow.auth.enabled", havingValue = "true")
public class SecurityConfiguration {

  private static final String INFO = "/info";

  private static final String API_DOCS = "/api/docs/**";

  private static final String HEALTH = "/health";

  private static final String INTERNAL = "/internal/**";

  private static final String WEBJARS = "/webjars/**";

  private static final String SLACK_INSTALL = "/api/v2/extensions/slack/install";

  @Autowired
  private TokenService tokenService;

  @Autowired
  private SettingsService settingsService;

  @Autowired
  @Qualifier("delegatedAuthenticationEntryPoint")
  AuthenticationEntryPoint authEntryPoint;

  @Value("${flow.authorization.basic.password:}")
  private String basicPassword;

  //TODO figure out why we also have to have the permitAll matches in the doNotFilter of AuthenticationFilter
    @Bean
    SecurityFilterChain authFilterChain(HttpSecurity http) throws Exception {
      final AuthenticationFilter authFilter =
          new AuthenticationFilter(tokenService, settingsService, basicPassword);
      http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(
              authorize ->
                  authorize
                      .requestMatchers(HEALTH, API_DOCS, INFO, INTERNAL, WEBJARS, SLACK_INSTALL)
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
          .sessionManagement(
              session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .exceptionHandling(
              exceptionHandling -> exceptionHandling.authenticationEntryPoint(authEntryPoint));
      return http.build();
    }
}

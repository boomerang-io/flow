package io.boomerang.core.security;

import io.boomerang.core.TokenService;
import io.boomerang.core.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// A5/H6: gated via the unified flow.security.enabled resolution - see FlowSecurityProperties.
@Configuration
@Conditional(SecurityEnabledCondition.class)
public class SecurityConfiguration {

  private static final String INFO = "/info";

  private static final String API_DOCS = "/api/docs/**";

  private static final String HEALTH = "/health";

  private static final String WEBJARS = "/webjars/**";

  private static final String SLACK_INSTALL = "/api/v2/extensions/slack/install";

  // Reached by a browser redirect from GitHub - cannot carry a bearer token. Also exempted from
  // AuthenticationFilter below, since that filter runs ahead of this authorization decision.
  static final String GITHUB_CALLBACK = "/api/v2/integration/github/callback";

  // The unified token exchange's direct OIDC login path (specifications/authentication.md §1) is
  // reached by the browser with no prior credential - AuthenticationFilter still RUNS for this
  // path (so proxy-forwarded identity is picked up when present), it just no longer 401s when it
  // resolves nothing; permitAll here is what lets that unauthenticated request continue to the
  // controller, which verifies the id_token itself.
  static final String AUTH_EXCHANGE = AuthenticationFilter.PATH_AUTH_EXCHANGE;

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
    // @Order required now that DispatcherSecurityConfiguration's /api/v1/** chain (E8.2a merge)
    // also lives in this context: it must evaluate FIRST (lower value), this chain evaluates last.
    @Bean
    @Order(2)
    SecurityFilterChain authFilterChain(HttpSecurity http) throws Exception {
      final AuthenticationFilter authFilter =
          new AuthenticationFilter(tokenService, settingsService, basicPassword, authEntryPoint);
      http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(
              authorize ->
                  authorize
                      .requestMatchers(
                          HEALTH, API_DOCS, INFO, WEBJARS, SLACK_INSTALL, GITHUB_CALLBACK, AUTH_EXCHANGE)
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

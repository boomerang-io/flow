package io.boomerang.security.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import io.boomerang.mongo.service.FlowSettingsService;
import io.boomerang.mongo.service.FlowTokenService;
import io.boomerang.mongo.service.FlowUserService;
import io.boomerang.security.filters.FlowAuthorizationFilter;
import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, proxyTargetClass = true)
public class WebSecurity {

  private static final String INFO = "/info";

  private static final String API_DOCS = "/apis/docs/**";

  private static final String HEALTH = "/health";

  private static final String INTERNAL = "/internal/**";

  private static final String WEBJARS = "/webjars/**";

  private static final String SLACK_INSTALL = "/apis/v1/extensions/slack/install";

  @Value("${jwt.secret:secret}")
  private String jwtSecret;

  @Autowired
  private FlowTokenService tokenService;

  @Autowired
  private FlowUserService flowUserService;

  @Autowired
  private FlowSettingsService flowSettingsService;

  @Value("${boomerang.authorization.enabled:false}")
  private boolean boomerangAuthorization;

  @Value("${boomerang.authorization.basic.password:}")
  private String basicPassword;

  @Bean
  public SecurityFilterChain configure(HttpSecurity http) throws Exception {

    if (boomerangAuthorization) {
      return setupJWT(http);
    } else {
      return setupNone(http);
    }
     

  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
    return authConfig.getAuthenticationManager();
  }

  // @Bean
  public SecurityFilterChain setupJWT(HttpSecurity http)
      throws Exception {
    final FlowAuthorizationFilter jwtFilter = new FlowAuthorizationFilter(tokenService,
        authenticationManager(http.getSharedObject(AuthenticationConfiguration.class)), flowUserService,
        flowSettingsService, basicPassword);

    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
        		authorize -> authorize
            .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
            .requestMatchers(HEALTH, API_DOCS, INFO, INTERNAL, WEBJARS, SLACK_INSTALL).permitAll())
        .authorizeHttpRequests(request -> {
          request.anyRequest().authenticated();
        })
        .addFilterBefore(jwtFilter, BasicAuthenticationFilter.class)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).build();

  }

  // @Bean
  public SecurityFilterChain setupNone(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .anonymous(a -> a.authorities(AuthorityUtils.createAuthorityList("ROLE_admin"))).build();
  }

}

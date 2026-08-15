package io.boomerang.core.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// A5/H6: gated via the unified flow.security.enabled resolution - see FlowSecurityProperties.
@Configuration
@Conditional(SecurityEnabledCondition.class)
public class SecurityInterceptorConfiguration implements WebMvcConfigurer {

  @Autowired
  private IdentityService identityService;

  @Autowired
  private MeterRegistry meterRegistry;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
     registry.addInterceptor(new SecurityInterceptor(identityService, meterRegistry));
  }

}

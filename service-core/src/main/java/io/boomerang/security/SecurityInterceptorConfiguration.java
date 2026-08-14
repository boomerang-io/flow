package io.boomerang.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// A5/H6: gated via the unified resolution (flow.authorization.enabled, if explicitly set, still
// wins - see FlowSecurityProperties) rather than a raw @ConditionalOnProperty with
// matchIfMissing=true on flow.authorization.enabled.
@Configuration
@Conditional(AuthorizationEnabledCondition.class)
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

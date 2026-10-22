package io.boomerang.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "flow.authorization.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityInterceptorConfiguration implements WebMvcConfigurer {

  @Autowired
  private IdentityService identityService;
  
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
     registry.addInterceptor(new SecurityInterceptor(identityService));
  }

}

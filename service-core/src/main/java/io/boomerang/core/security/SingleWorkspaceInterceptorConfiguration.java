package io.boomerang.core.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// With flow.workspace.single=true every workspace-scoped request must target the system
// workspace, the resource itself included: GET /api/v2/workspace/{workspace} is guarded the same
// way as /api/v2/workspace/{workspace}/**. /api/v2/workspace/query binds a handler with no
// {workspace} variable, so it passes through.
@Configuration
public class SingleWorkspaceInterceptorConfiguration implements WebMvcConfigurer {

  private final Environment environment;

  public SingleWorkspaceInterceptorConfiguration(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    if (FlowWorkspaceProperties.isSingle(environment)) {
      registry
          .addInterceptor(new SingleWorkspaceInterceptor())
          .addPathPatterns("/api/v2/workspace/{workspace}", "/api/v2/workspace/{workspace}/**");
    }
  }
}

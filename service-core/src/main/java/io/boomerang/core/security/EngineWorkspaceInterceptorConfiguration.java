package io.boomerang.core.security;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// AM-10: engine mode only - the system workspace IS the workspace in engine mode, so every
// workspace-scoped request must target it. Registered on both DD-01 aliases (/api/v2/team/{team}
// and /api/v2/workspace/{team}) that survive in engine mode (see the mode matrix -
// WorkspaceControllerV2 itself, and its bare /api/v2/team|workspace collection paths, is
// STANDALONE-only and never loads here).
@Configuration
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineWorkspaceInterceptorConfiguration implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new EngineWorkspaceInterceptor())
        .addPathPatterns("/api/v2/team/{team}/**", "/api/v2/workspace/{team}/**");
  }
}

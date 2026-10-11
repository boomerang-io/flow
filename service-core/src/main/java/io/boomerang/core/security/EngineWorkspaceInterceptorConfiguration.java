package io.boomerang.core.security;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Engine mode only - the system workspace IS the workspace in engine mode, so every
// workspace-scoped request must target it. Registered on the workspace-scoped v2 paths that
// survive in engine mode (see the mode matrix in specifications/architecture.md), including the
// resource itself: EngineWorkspaceControllerV2 serves the read of /api/v2/workspace/{workspace}
// while WorkspaceControllerV2 and its writes stay STANDALONE-only. /api/v2/workspace/query binds
// a handler with no {workspace} variable, so it passes through. The deprecated
// /api/v2/team/{team} alias is gone, so only /api/v2/workspace/{workspace} remains.
@Configuration
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineWorkspaceInterceptorConfiguration implements WebMvcConfigurer {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new EngineWorkspaceInterceptor())
        .addPathPatterns("/api/v2/workspace/{workspace}", "/api/v2/workspace/{workspace}/**");
  }
}

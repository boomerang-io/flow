package io.boomerang.core.security;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/*
 * Engine-mode workspace guard (AM-10, specifications/merge-execution-plan.md): in engine mode the
 * `system` workspace IS the workspace - "engine basically runs in what the admins use". It is
 * seeded by changeunit _0014__SeedSystemWorkspace (unlimited-quota, undeletable) and `system` is
 * in WorkspaceService.RESERVED_WORKSPACE_NAMES, so no user workspace can ever shadow it.
 *
 * Rejects any workspace-scoped request whose {team} path variable is not "system". Registered
 * only in engine mode, and only on the workspace-scoped v2 paths - see
 * EngineWorkspaceInterceptorConfiguration. Does not normalise/rewrite {team} to "system" - a
 * mismatched workspace is rejected, never silently remapped.
 */
public class EngineWorkspaceInterceptor implements HandlerInterceptor {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String SYSTEM_WORKSPACE = "system";

  @Override
  @SuppressWarnings("unchecked")
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object uriTemplateVariables =
        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (uriTemplateVariables instanceof Map) {
      Map<String, String> variables = (Map<String, String>) uriTemplateVariables;
      String team = variables.get("team");
      if (team != null && !SYSTEM_WORKSPACE.equals(team)) {
        LOGGER.warn(
            "EngineWorkspaceInterceptor - rejecting workspace-scoped request for '{}': engine "
                + "mode only serves the '{}' workspace.",
            team,
            SYSTEM_WORKSPACE);
        throw new BoomerangException(BoomerangError.TEAM_INVALID_REF, team);
      }
    }
    return true;
  }
}

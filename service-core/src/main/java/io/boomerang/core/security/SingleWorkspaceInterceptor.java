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
 * The single-workspace guard (flow.workspace.single=true): the `system` workspace IS the
 * workspace. It is seeded with unlimited quotas, `system` is in
 * WorkspaceService.RESERVED_WORKSPACE_NAMES so no user workspace can ever shadow it, and
 * WorkspaceService refuses to create or delete any workspace while the switch is on.
 *
 * Rejects any workspace-scoped request whose {workspace} path variable is not "system".
 * Registered only when the switch is on, and only on the workspace-scoped v2 paths - see
 * SingleWorkspaceInterceptorConfiguration. Does not normalise/rewrite {workspace} to "system" - a
 * mismatched workspace is rejected, never silently remapped.
 */
public class SingleWorkspaceInterceptor implements HandlerInterceptor {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String SYSTEM_WORKSPACE = "system";

  @Override
  @SuppressWarnings("unchecked")
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object uriTemplateVariables =
        request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (uriTemplateVariables instanceof Map) {
      Map<String, String> variables = (Map<String, String>) uriTemplateVariables;
      String workspace = variables.get("workspace");
      if (workspace != null && !SYSTEM_WORKSPACE.equals(workspace)) {
        LOGGER.warn(
            "SingleWorkspaceInterceptor - rejecting workspace-scoped request for '{}': this "
                + "instance only serves the '{}' workspace.",
            workspace,
            SYSTEM_WORKSPACE);
        throw new BoomerangException(BoomerangError.WORKSPACE_INVALID_REF, workspace);
      }
    }
    return true;
  }
}

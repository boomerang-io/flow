package io.boomerang.workspace;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.UserService;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.model.WorkspaceSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Serves a given user's Workspace membership rollup, the same composition ProfileControllerV2
 * performs for the current user.
 *
 * Lives in workspace, not core beside UserControllerV2, for the same reason as ProfileControllerV2:
 * the rollup spans core (User) and workspace (Workspace) data, and io.boomerang.core has zero
 * outbound feature-package imports.
 */
@RestController
@RequestMapping("/api/v2/user")
@Tag(name = "Users", description = "List, Create, update and delete Users.")
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class UserWorkspaceControllerV2 {

  @Autowired private UserService userService;

  @Autowired private WorkspaceService workspaceService;

  @GetMapping(value = "/{userId}/workspaces")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.key, AuthScope.global})
  @Operation(summary = "Get a Users Workspace memberships")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<WorkspaceSummary> getUserWorkspaces(@PathVariable String userId) {
    userService
        .getUserByID(userId)
        .orElseThrow(() -> new BoomerangException(BoomerangError.USER_NOT_FOUND));
    return workspaceService.getWorkspaceMembershipSummary(
        userService.getTeamRefsAndRolesForUser(userId));
  }
}

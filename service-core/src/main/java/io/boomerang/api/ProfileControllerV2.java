package io.boomerang.api;

import io.boomerang.api.model.UserProfile;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.model.UserRequest;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.WorkspaceSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Composes the User Profile response.
 *
 * The Workspace membership rollup (summaries + permissions) spans core (User) and workspace (Workspace)
 * data - core.UserService cannot depend on workspace, so this composition lives here in the api
 * layer, which may depend on everything.
 */
// E8: hard-depends on workspace.WorkspaceService, so full-mode-only.
@RestController
@RequestMapping("/api/v2/profile")
@Tag(name = "Profile", description = "Retrieve your profile and update your details.")
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class ProfileControllerV2 {

  @Autowired private UserService userService;

  @Autowired private WorkspaceService workspaceService;

  @Autowired private TokenService tokenService;

  /*
   * Returns the current users profile
   *
   * The authentication handler ensures they are already a registered user
   */
  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user})
  @Operation(summary = "Get your Profile")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "423", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Instance not activated. Profile locked.")
      })
  public UserProfile getProfile() {
    UserEntity baseEntity = userService.getCurrentProfileEntity();
    UserProfile profile = new UserProfile(baseEntity);
    Map<String, String> teamRefsAndRoles = userService.getTeamRefsAndRolesForUser(profile.getId());
    List<WorkspaceSummary> teams = workspaceService.getWorkspaceMembershipSummary(teamRefsAndRoles);
    profile.setTeams(teams);
    profile.setPermissions(tokenService.resolvePermissionsForUser(baseEntity));
    return profile;
  }

  @PatchMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user})
  @Operation(summary = "Patch your Profile")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "423", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Instance not activated. Profile locked.")
      })
  public void updateProfile(@RequestBody UserRequest request) {
    userService.updateCurrentProfile(request);
  }
}

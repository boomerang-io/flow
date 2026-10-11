package io.boomerang.workspace;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.TokenService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.model.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * The engine-mode read of the caller's profile, so the webapp can start against engine mode. The
 * workspace list is the system workspace alone; ProfileControllerV2 and the profile update stay
 * standalone-only because they compose WorkspaceService memberships.
 */
@RestController
@RequestMapping("/api/v2/profile")
@Tag(name = "Profile", description = "Retrieve your profile.")
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineProfileControllerV2 {

  private final UserService userService;
  private final TokenService tokenService;
  private final EngineWorkspaceService engineWorkspaceService;

  public EngineProfileControllerV2(
      UserService userService,
      TokenService tokenService,
      EngineWorkspaceService engineWorkspaceService) {
    this.userService = userService;
    this.tokenService = tokenService;
    this.engineWorkspaceService = engineWorkspaceService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user})
  @Operation(summary = "Get your Profile")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public UserProfile getProfile() {
    UserEntity baseEntity = userService.getCurrentProfileEntity();
    UserProfile profile = new UserProfile(baseEntity);
    profile.setTeams(engineWorkspaceService.summaries());
    profile.setPermissions(tokenService.resolvePermissionsForUser(baseEntity));
    return profile;
  }
}

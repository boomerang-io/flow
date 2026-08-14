package io.boomerang.core;

import io.boomerang.core.model.UserProfile;
import io.boomerang.core.model.UserRequest;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/profile")
@Tag(name = "Profile", description = "Retrieve your profile and update your details.")
public class ProfileControllerV2 {

  @Autowired private UserService userService;

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
    return userService.getCurrentProfile();
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

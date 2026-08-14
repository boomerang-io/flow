package io.boomerang.core;

import io.boomerang.core.model.Features;
import io.boomerang.core.model.HeaderNavigationResponse;
import io.boomerang.core.model.Navigation;
import io.boomerang.core.model.OneTimeCode;
import io.boomerang.core.model.Setting;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.boomerang.workflow.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@Tag(
    name = "System",
    description =
        "Register the instance, retrieve context and navigation, and manage global admin areas.")
public class SystemControllerV2 {

  @Autowired private SettingsService settingsService;

  @Autowired private UserService userService;

  @Autowired private ParameterService paramService;

  @Autowired private NavigationService navigationService;

  @Autowired private ContextService contextService;

  @Autowired private FeatureService featureService;

  @GetMapping(value = "/settings")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Retrieve Boomerang Flow Settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<Setting> getAppConfiguration() {
    return settingsService.getAllSettings();
  }

  @PutMapping(value = "/settings")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Update Boomerang Flow Settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<Setting> updateSettings(@RequestBody List<Setting> settings) {
    return settingsService.updateSettings(settings);
  }

  @PutMapping(value = "/activate")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user})
  @Operation(summary = "Register and activate an installation of Flow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Boolean> register(@RequestBody(required = false) OneTimeCode otc) {
    return userService.activateSetup(otc);
  }

  @GetMapping(value = "/context")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Retrieve this instances context, features, and navigation.")
  public HeaderNavigationResponse getHeaderNavigation() {
    return this.contextService.getHeaderNavigation(userService.isCurrentUserAdmin());
  }

  @GetMapping(value = "/features")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Retrieve feature flags.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Features> getFlowFeatures() {
    CacheControl cacheControl = CacheControl.maxAge(5, TimeUnit.MINUTES);
    return ResponseEntity.ok().cacheControl(cacheControl).body(featureService.get());
  }

  @GetMapping(value = "/navigation")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Retrieve navigation.")
  public ResponseEntity<List<Navigation>> getNavigation(
      @Parameter(
              name = "team",
              description = "Team as owner reference",
              example = "my-amazing-team",
              required = false)
          @RequestParam(required = false)
          Optional<String> team) {
    List<Navigation> response =
        navigationService.getNavigation(userService.isCurrentUserAdmin(), team);

    CacheControl cacheControl = CacheControl.maxAge(1, TimeUnit.HOURS);

    return ResponseEntity.ok().cacheControl(cacheControl).body(response);
  }
}

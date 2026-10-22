package io.boomerang.core;

import io.boomerang.core.model.User;
import io.boomerang.core.model.UserRequest;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/user")
@Tag(name = "Users", description = "List, Create, update and delete Users.")
public class UserControllerV2 {

  @Value("${flow.externalUrl.user}")
  private String flowExternalUrlUser;

  @Autowired private UserService userService;

  @GetMapping(value = "/{userId}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.team, AuthScope.global})
  @Operation(summary = "Get a Users details")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public ResponseEntity<User> getUserByID(@PathVariable String userId) {
    Optional<User> user = userService.getUserByID(userId);
    if (user.isPresent()) {
      return ResponseEntity.ok(user.get());
    } else {
      return ResponseEntity.notFound().build();
    }
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.team, AuthScope.global})
  @Operation(summary = "Search for Users")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<User> getUsers(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "status",
              description = "List of statuses to filter for. Defaults to all.",
              example = "active,inactive",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> statuses,
      @Parameter(name = "ids", description = "List of ids to filter for.", required = false)
          @RequestParam(required = false)
          Optional<List<String>> ids,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "order",
              description = "Ascending or Descending (default) order",
              example = "0",
              required = false)
          @RequestParam(defaultValue = "DESC")
          Optional<Direction> order,
      @Parameter(
              name = "sort",
              description = "The element to sort on",
              example = "0",
              required = false)
          @RequestParam(defaultValue = "name")
          Optional<String> sort) {
    return userService.query(page, limit, order, sort, labels, statuses, ids);
  }

  // TODO - figure out if this is valid or whether an 'invite' user should exist
  //  @PostMapping(value = "")
  //  @AuthScope(action = PermissionAction.WRITE, scope = PermissionScope.USER, types =
  // {AuthType.global})
  //  @Operation(summary = "Create a new Boomerang Flow user")
  //  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK"),
  //      @ApiResponse(responseCode = "400", description = "Bad Request")})
  //  public ResponseEntity<User> addUser(@RequestBody UserRequest request) {
  //    if (isUserManagementAvaliable()) {
  //      User flowUserEntity = userService.create(request);
  //      return ResponseEntity.ok(flowUserEntity);
  //    } else {
  //      return ResponseEntity.badRequest().build();
  //    }
  //  }

  @PatchMapping(value = "/{userId}")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.global})
  @Operation(summary = "Update a Boomerang Flow Users details")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Void> apply(@PathVariable String userId, @RequestBody UserRequest user) {
    user.setId(userId);
    if (isUserManagementAvaliable()) {
      userService.apply(user);
      return ResponseEntity.ok().build();
    } else {
      return ResponseEntity.badRequest().build();
    }
  }

  @DeleteMapping(value = "/{userId}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.USER,
      assignableScopes = {AuthScope.global})
  @Operation(summary = "Delete a Boomerang Flow user")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Void> deleteFlowUser(@PathVariable String userId) {
    if (isUserManagementAvaliable()) {
      userService.delete(userId);
      return ResponseEntity.ok().build();
    } else {
      return ResponseEntity.badRequest().build();
    }
  }

  private boolean isUserManagementAvaliable() {
    return flowExternalUrlUser.isBlank();
  }
}

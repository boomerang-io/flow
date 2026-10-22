package io.boomerang.workflow;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.core.audit.AuditAction;
import io.boomerang.core.audit.AuditLevel;
import io.boomerang.core.audit.Audited;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v2/parameters")
@Tag(
    name = "Parameters",
    description =
        "Create, Update, and Delete global parameters available to all Teams and Workflows.")
public class ParameterControllerV2 {

  /** The media type of a JSON Merge Patch (RFC 7396); plain JSON is accepted as the same thing. */
  private static final String MERGE_PATCH_JSON = "application/merge-patch+json";

  @Autowired private ParameterService paramService;

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Get all global Params")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<AbstractParam> getAll() {
    return paramService.getAll();
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Audited(action = AuditAction.CREATE, resourceType = "parameter", resourceId = "#request?.getName()")
  @Operation(summary = "Create a global Param")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public AbstractParam create(@RequestBody AbstractParam request) {
    return paramService.create(request);
  }

  @PatchMapping(
      value = "/{name}",
      consumes = {MERGE_PATCH_JSON, MediaType.APPLICATION_JSON_VALUE})
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Audited(action = AuditAction.UPDATE, resourceType = "parameter", resourceId = "#name")
  @Operation(
      summary = "Update a global Param",
      description =
          "A JSON Merge Patch (RFC 7396): an absent member is unchanged, null clears it, any other"
              + " value replaces it. A secured value or default sent as ***** keeps the stored one.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request"),
        @ApiResponse(responseCode = "404", description = "No global param of that name")
      })
  public AbstractParam patch(@PathVariable String name, @RequestBody JsonNode patch) {
    return paramService.patch(name, patch);
  }

  @DeleteMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.SYSTEM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Audited(
      action = AuditAction.DELETE,
      resourceType = "parameter",
      resourceId = "#name",
      level = AuditLevel.DESTRUCTIVE)
  @Operation(summary = "Delete a global Param")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void delete(@PathVariable String name) {
    paramService.delete(name);
  }
}

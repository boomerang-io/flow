package io.boomerang.workspace;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.model.Workspace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * The engine-mode read of the workspace resource. Every workspace-scoped route in engine mode
 * takes "system" as its {workspace}, so the resource itself must resolve; WorkspaceControllerV2
 * and the writes it carries stay standalone-only.
 */
@RestController
@RequestMapping("/api/v2/workspace")
@Tag(name = "Workspace Management", description = "Read the system Workspace.")
@ConditionalOnFlowMode(FlowMode.ENGINE)
public class EngineWorkspaceControllerV2 {

  private final EngineWorkspaceService engineWorkspaceService;

  public EngineWorkspaceControllerV2(EngineWorkspaceService engineWorkspaceService) {
    this.engineWorkspaceService = engineWorkspaceService;
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKSPACE,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.key, AuthScope.global})
  @Operation(summary = "Search for Workspaces")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK")})
  public Page<Workspace> getWorkspaces() {
    return engineWorkspaceService.query();
  }

  @GetMapping(value = "/{workspace}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKSPACE,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.key, AuthScope.global})
  @Operation(summary = "Get workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public Workspace getWorkspace(
      @Parameter(
              name = "workspace",
              description = "Workspace as owner reference.",
              example = "system",
              required = true)
          @PathVariable
          String workspace) {
    return engineWorkspaceService.get(workspace);
  }
}

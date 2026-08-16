package io.boomerang.api;

import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.model.Role;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.Workspace;
import io.boomerang.workspace.model.WorkspaceMember;
import io.boomerang.workspace.model.WorkspaceNameCheckRequest;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// E8: hard-depends on workspace.WorkspaceService, so standalone-only. J1's engine-mode
// default-workspace remapping is deferred (E10 territory).
@RestController
@RequestMapping("/api/v2/workspace")
@Tag(
    name = "Workspace Management",
    description = "Manage Workspaces, Workspace Members, Quotas, ApprovalGroups and Parameters.")
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class WorkspaceControllerV2 {

  private final WorkspaceService workspaceService;

  public WorkspaceControllerV2(WorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  @PostMapping(value = "/validate-name")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Validate workspace name and check uniqueness.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "422", description = "Name is already taken"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<?> validateWorkspaceName(@RequestBody WorkspaceNameCheckRequest request) {
    return workspaceService.validateName(request);
  }

  @GetMapping(value = "/{workspace}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Get workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workspace getWorkspace(
      @Parameter(
              name = "workspace",
              description = "Workspace as owner reference.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace) {
    return workspaceService.get(workspace);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Search for Workspaces")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<Workspace> getWorkspaces(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "statuses",
              description = "List of statuses to filter for. Defaults to all.",
              example = "active,inactive",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> statuses,
      @Parameter(
              name = "workspaces",
              description = "List of Workspace names to filter for.",
              example = "my-amazing-workspace,boomerangs-return",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> names,
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
    return workspaceService.query(page, limit, order, sort, labels, statuses, names);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.global})
  @Operation(summary = "Create new workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workspace createWorkspace(@RequestBody WorkspaceRequest request) {
    return workspaceService.create(request);
  }

  @PatchMapping(value = "/{workspace}")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Patch or update a workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workspace updateWorkspace(
      @Parameter(name = "workspace", description = "ID of Workspace", required = true) @PathVariable
          String workspace,
      @RequestBody WorkspaceRequest request) {
    return workspaceService.patch(workspace, request);
  }

  @DeleteMapping(value = "/{workspace}")
  @Operation(summary = "Delete Workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteWorkflow(
      @Parameter(name = "workspace", description = "ID of Workspace", required = true) @PathVariable
          String workspace) {
    workspaceService.delete(workspace);
  }

  @DeleteMapping(value = "/{workspace}/members")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Remove Workspace Members")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void removeMembers(
      @Parameter(
              name = "workspace",
              description = "Workspace as owner reference.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody List<WorkspaceMember> request) {
    workspaceService.removeMembers(workspace, request);
  }

  @DeleteMapping(value = "/{workspace}/leave")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.user, AuthScope.session})
  @Operation(summary = "Leave Workspace")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void leave(
      @Parameter(name = "workspace", description = "Workspace as owner reference.", required = true)
          @PathVariable
          String workspace) {
    workspaceService.leave(workspace);
  }

  @DeleteMapping(value = "/{workspace}/parameters/{name}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Delete Workspace Parameter")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteParameters(
      @Parameter(name = "workspace", description = "Workspace as owner reference.", required = true)
          @PathVariable
          String workspace,
      @PathVariable String name) {
    workspaceService.deleteParameter(workspace, name);
  }

  @DeleteMapping(value = "/{workspace}/approvers")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Delete Approver Groups")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteApproverGroup(
      @Parameter(name = "workspace", description = "Workspace as owner reference.", required = true)
          @PathVariable
          String workspace,
      @RequestBody List<String> names) {
    workspaceService.deleteApproverGroups(workspace, names);
  }

  @DeleteMapping(value = "/{workspace}/quotas")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Reset Workspace Quota")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void resetQuotas(
      @Parameter(name = "workspace", description = "Workspace as owner reference.", required = true)
          @PathVariable
          String workspace) {
    workspaceService.deleteCustomQuotas(workspace);
  }

  @GetMapping(value = "/quotas/default")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Retrieve Default Workspace Quota")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Quotas> getDefaultQuotas() {
    return workspaceService.getDefaultQuotas();
  }

  @GetMapping(value = "/roles")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TEAM,
      assignableScopes = {AuthScope.session, AuthScope.user, AuthScope.workspace, AuthScope.global})
  @Operation(summary = "Retrieve Workspace Roles")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<Role>> getRoles() {
    return workspaceService.getRoles();
  }
}

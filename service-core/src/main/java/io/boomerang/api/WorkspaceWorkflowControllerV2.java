package io.boomerang.api;

import io.boomerang.api.model.WorkflowResponsePage;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workflow.model.WorkflowCanvas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspace/{workspace}/workflow")
@Tag(name = "Workflows", description = "Create, list, and manage your Workflows.")
@SecurityRequirement(name = "BearerAuth")
@SecurityRequirement(name = "x-access-token")
public class WorkspaceWorkflowControllerV2 {

  private final WorkspaceWorkflowService workspaceWorkflowService;

  public WorkspaceWorkflowControllerV2(WorkspaceWorkflowService workspaceWorkflowService) {
    this.workspaceWorkflowService = workspaceWorkflowService;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Retrieve a Workflow",
      description =
          "Retrieve a version of the Workflow. Defaults to latest. Optionally without Tasks")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow getWorkflow(
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name,
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "version", description = "Workflow version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version,
      @Parameter(
              name = "withTasks",
              description = "Include Workflow tasks in response",
              required = false)
          @RequestParam(defaultValue = "true")
          boolean withTasks) {
    return workspaceWorkflowService.get(workspace, name, version, withTasks);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Search for Workflows", description = "")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowResponsePage queryWorkflows(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
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
              name = "workflows",
              description = "List of workflows to filter for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflows,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "sort",
              description = "Ascending (ASC) or Descending (DESC) sort on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> sort) {
    return workspaceWorkflowService.query(workspace, limit, page, sort, labels, statuses, workflows);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Create a new workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow createWorkflow(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody Workflow workflow) {
    return workspaceWorkflowService.create(workspace, workflow);
  }

  @PutMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Update, replace, or create new, Workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow applyWorkflow(
      @RequestBody Workflow workflow,
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workspaceWorkflowService.apply(workspace, workflow, replace);
  }

  @GetMapping(value = "/{name}/changelog")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Retrieve the changlog",
      description = "Retrieves each versions changelog and returns them all as a list.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<ChangeLogVersion>> getChangelog(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workspaceWorkflowService.changelog(workspace, name);
  }

  @DeleteMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Delete a workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteWorkflow(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    workspaceWorkflowService.delete(workspace, name);
  }

  @PostMapping(value = "/{name}/submit")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Submit a Workflow to be run. Will queue the WorkflowRun ready for execution.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun submitWorkflow(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name,
      @Parameter(
              name = "start",
              description = "Start the WorkflowRun immediately after submission",
              required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean start,
      @RequestBody WorkflowSubmitRequest request) {
    return workspaceWorkflowService.submit(workspace, name, request, start);
  }

  @GetMapping(value = "/{name}/export", produces = "application/json")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Export the Workflow as JSON.")
  public ResponseEntity<InputStreamResource> export(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workspaceWorkflowService.export(workspace, name);
  }

  @GetMapping(value = "/{name}/compose")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Convert workflow to compose model for UI Designer and detailed Activity screens.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowCanvas compose(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name,
      @Parameter(name = "version", description = "Workflow Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return workspaceWorkflowService.composeGet(workspace, name, version);
  }

  @PutMapping(value = "/{name}/compose")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Update, replace, or create new, Workflow for Canvas")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowCanvas applyCanvas(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody WorkflowCanvas canvas,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workspaceWorkflowService.composeApply(workspace, canvas, replace);
  }

  @PostMapping(value = "/{name}/duplicate")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Duplicates the workflow.")
  public Workflow duplicateWorkflow(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workspaceWorkflowService.duplicate(workspace, name);
  }

  @GetMapping(value = "/{name}/available-parameters")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Retrieve the parameters.")
  public List<String> getAvailableParameters(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workspaceWorkflowService.getAvailableParameters(workspace, name);
  }
}

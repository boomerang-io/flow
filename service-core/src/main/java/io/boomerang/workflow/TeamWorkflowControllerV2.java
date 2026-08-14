package io.boomerang.workflow;

import io.boomerang.client.WorkflowResponsePage;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
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
@RequestMapping("/api/v2/team/{team}/workflow")
@Tag(name = "Workflows", description = "Create, list, and manage your Workflows.")
@SecurityRequirement(name = "BearerAuth")
@SecurityRequirement(name = "x-access-token")
public class TeamWorkflowControllerV2 {

  private final WorkflowService workflowService;

  public TeamWorkflowControllerV2(WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
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
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(name = "version", description = "Workflow version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version,
      @Parameter(
              name = "withTasks",
              description = "Include Workflow tasks in response",
              required = false)
          @RequestParam(defaultValue = "true")
          boolean withTasks) {
    return workflowService.get(team, name, version, withTasks);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Search for Workflows", description = "")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowResponsePage queryWorkflows(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
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
    return workflowService.query(team, limit, page, sort, labels, statuses, workflows);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Create a new workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow createWorkflow(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @RequestBody Workflow workflow) {
    return workflowService.create(team, workflow);
  }

  @PutMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Update, replace, or create new, Workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow applyWorkflow(
      @RequestBody Workflow workflow,
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workflowService.apply(team, workflow, replace);
  }

  @GetMapping(value = "/{name}/changelog")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
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
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workflowService.changelog(team, name);
  }

  @DeleteMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Delete a workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteWorkflow(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    workflowService.delete(team, name);
  }

  @PostMapping(value = "/{name}/submit")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(
      summary = "Submit a Workflow to be run. Will queue the WorkflowRun ready for execution.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun submitWorkflow(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
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
    return workflowService.submit(team, name, request, start);
  }

  @GetMapping(value = "/{name}/export", produces = "application/json")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Export the Workflow as JSON.")
  public ResponseEntity<InputStreamResource> export(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workflowService.export(team, name);
  }

  @GetMapping(value = "/{name}/compose")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(
      summary = "Convert workflow to compose model for UI Designer and detailed Activity screens.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowCanvas compose(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
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
    return workflowService.composeGet(team, name, version);
  }

  @PutMapping(value = "/{name}/compose")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Update, replace, or create new, Workflow for Canvas")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowCanvas applyCanvas(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @RequestBody WorkflowCanvas canvas,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workflowService.composeApply(team, canvas, replace);
  }

  @PostMapping(value = "/{name}/duplicate")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Duplicates the workflow.")
  public Workflow duplicateWorkflow(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workflowService.duplicate(team, name);
  }

  @GetMapping(value = "/{name}/available-parameters")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.session,
        AuthScope.workflow
      })
  @Operation(summary = "Retrieve the parameters.")
  public List<String> getAvailableParameters(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "name",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String name) {
    return workflowService.getAvailableParameters(team, name);
  }
}

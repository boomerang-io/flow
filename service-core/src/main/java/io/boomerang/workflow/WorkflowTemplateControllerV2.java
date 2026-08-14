package io.boomerang.workflow;

import io.boomerang.client.EngineClient;
import io.boomerang.client.WorkflowTemplateResponsePage;
import io.boomerang.common.model.WorkflowTemplate;
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
@RequestMapping("/api/v2/workflowtemplate")
@Tag(name = "Workflow Templates", description = "Create, List, and Manage your Workflows.")
public class WorkflowTemplateControllerV2 {

  EngineClient engineClient;

  public WorkflowTemplateControllerV2(EngineClient engineClient) {
    this.engineClient = engineClient;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session},
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(
      summary = "Retrieve a Workflow Template",
      description =
          "Retrieve a version of the Workflow Template. Defaults to latest. Optionally without Tasks")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplate get(
      @Parameter(name = "name", description = "Name of Workflow Template", required = true)
          @PathVariable
          String name,
      @Parameter(name = "version", description = "Workflow Template Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version,
      @Parameter(name = "withTasks", description = "Include Tasks", required = false)
          @RequestParam(defaultValue = "true")
          boolean withTasks) {
    return engineClient.getWorkflowTemplate(name, version, withTasks);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session},
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(summary = "Search for Workflow Templates")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplateResponsePage query(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "names",
              description = "List of WorkflowTemplate names to filter for. Defaults to all.",
              example = "mongodb-email-query-results",
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
              name = "sort",
              description = "Ascending (ASC) or Descending (DESC) sort on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> sort) {
    return engineClient.queryWorkflowTemplates(limit, page, sort, labels, names);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session},
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(summary = "Create a new Workflow Template")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplate create(@RequestBody WorkflowTemplate request) {
    return engineClient.createWorkflowTemplate(request);
  }

  @PutMapping(value = "")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session},
      action = PermissionAction.WRITE,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(summary = "Update, replace, or create new, Workflow Template")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplate apply(
      @RequestBody WorkflowTemplate request,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return engineClient.applyWorkflowTemplate(request, replace);
  }

  @DeleteMapping(value = "/{name}")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session},
      action = PermissionAction.DELETE,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(summary = "Delete a Workflow Template")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Void> deleteWorkflow(
      @Parameter(name = "name", description = "Name of Workflow Template", required = true)
          @PathVariable
          String name) {
    return engineClient.deleteWorkflowTemplate(name);
  }
  //
  //  @GetMapping(value = "/{name}/export", produces = "application/json")
  //  @AuthScope(types = {TokenScope.global}, access = TokenAccess.read, object =
  // TokenObject.workflowtemplate)
  //  @Operation(summary = "Export the Workflow Template as JSON.")
  //  public ResponseEntity<InputStreamResource> export(@Parameter(name = "name",
  //      description = "Name of Workflow Template", required = true) @PathVariable String name) {
  //    return engineClient.export(name);
  //  }
  //
  //  @GetMapping(value = "/{workflowId}/compose")
  //  @AuthScope(types = {TokenScope.global}, access = TokenAccess.read, object =
  // TokenObject.parameter)
  //  @Operation(summary = "Convert workflow to compose model for UI Designer and detailed Activity
  // screens.")
  //  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK"),
  //      @ApiResponse(responseCode = "400", description = "Bad Request")})
  //  public ResponseEntity<WorkflowCanvas> compose(
  //      @Parameter(name = "workflowId", description = "ID of Workflow",
  //          required = true) @PathVariable String workflowId,
  //      @Parameter(name = "version", description = "Workflow Version",
  //          required = false) @RequestParam(required = false) Optional<Integer> version) {
  //    return workflowTemplateService.compose(workflowId, version);
  //  }
  //
  //  @PostMapping(value = "/{workflowId}/duplicate")
  //  @Operation(summary = "Duplicates the workflow.")
  //  public ResponseEntity<Workflow> duplicateWorkflow(
  //      @Parameter(name = "workflowId", description = "ID of Workflow",
  //      required = true) @PathVariable String workflowId) {
  //    return workflowTemplateService.duplicate(workflowId);
  //  }
  //
  //  @GetMapping(value = "/{workflowId}/available-parameters")
  //  @Operation(summary = "Retrieve the parameters.")
  //  public List<String> getAvailableParameters(@PathVariable String workflowId) {
  //    return workflowTemplateService.getAvailableParameters(workflowId);
  //  }
}

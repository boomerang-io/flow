package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowTemplate;
import io.boomerang.common.model.WorkflowTemplateResponsePage;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workflow Templates are read-only content: they are seeded by the loader and, on an upgrade from
 * v3, imported from workflows that carried {@code scope=template}. A caller reads a template and
 * creates a Workflow from it through the workspace Workflow create route; there is no API to
 * author, change or remove one.
 */
@RestController
@RequestMapping("/api/v2/workflowtemplate")
@Tag(
    name = "Workflow Templates",
    description = "Retrieve and search the Workflow Templates available to every workspace.")
public class WorkflowTemplateControllerV2 {

  WorkflowTemplateService workflowTemplateService;

  public WorkflowTemplateControllerV2(WorkflowTemplateService workflowTemplateService) {
    this.workflowTemplateService = workflowTemplateService;
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
    // WorkflowTemplateService#get never accepted the withTasks param either - it always
    // returned the template with false regardless of what the caller asked for. Preserved here
    // bug-for-bug: withTasks is intentionally ignored.
    return workflowTemplateService.get(name, version, false);
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
    Page<WorkflowTemplate> resultPage =
        workflowTemplateService.query(limit, page, sort, labels, names);
    return new WorkflowTemplateResponsePage(
        resultPage.getContent(), resultPage.getPageable(), resultPage.getTotalElements());
  }
}

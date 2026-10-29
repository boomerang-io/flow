package io.boomerang.workflow;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowResponsePage;
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
 * The templates and starters every workspace may read: the system workspace's workflows of type
 * {@code template} (run by reference through a workflow carrying a {@code templateRef}) and
 * {@code starter} (copied by posting the body, with {@code type} set to {@code workflow}, to the
 * workspace Workflow create route). Authoring them is the system workspace's workflow routes.
 */
@RestController
@RequestMapping("/api/v2/workflowtemplate")
@Tag(
    name = "Workflow Templates",
    description = "Retrieve and search the Workflow Templates and starters available to every workspace.")
public class WorkflowTemplateControllerV2 {

  private final WorkflowTemplateService workflowTemplateService;

  public WorkflowTemplateControllerV2(WorkflowTemplateService workflowTemplateService) {
    this.workflowTemplateService = workflowTemplateService;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session},
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(
      summary = "Retrieve a Workflow Template or starter",
      description = "Retrieve a version of the Workflow Template. Defaults to latest.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Workflow get(
      @Parameter(name = "name", description = "Name of Workflow Template", required = true)
          @PathVariable
          String name,
      @Parameter(name = "version", description = "Workflow Template Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return workflowTemplateService.get(name, version);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session},
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWTEMPLATE)
  @Operation(summary = "Search for Workflow Templates and starters")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowResponsePage query(
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
      @Parameter(
              name = "types",
              description = "Which kinds to list: template, starter. Defaults to both.",
              example = "starter",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> types,
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
    Page<Workflow> resultPage =
        workflowTemplateService.query(limit, page, sort, labels, names, types);
    return new WorkflowResponsePage(
        resultPage.getContent(), resultPage.getPageable(), resultPage.getTotalElements());
  }
}

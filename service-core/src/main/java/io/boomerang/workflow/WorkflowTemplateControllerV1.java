package io.boomerang.engine;

import io.boomerang.common.model.WorkflowTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/workflowtemplate")
@Tag(name = "WorkflowTemplate Management", description = "Create and Manage the WorkflowTemplates.")
public class WorkflowTemplateControllerV1 {

  private final WorkflowTemplateService workflowTemplateService;

  public WorkflowTemplateControllerV1(WorkflowTemplateService workflowTemplateService) {
    this.workflowTemplateService = workflowTemplateService;
  }

  @GetMapping(value = "/{name}")
  @Operation(
      summary =
          "Retrieve a specific WorkflowTemplate. If no version specified, the latest version is returned.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplate get(
      @Parameter(name = "name", description = "Name of WorkflowTemplate", required = true)
          @PathVariable
          String name,
      @Parameter(name = "version", description = "WorkflowTemplate Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return workflowTemplateService.get(name, version, false);
  }

  @GetMapping(value = "/query")
  @Operation(summary = "Search for WorkflowTemplates")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<WorkflowTemplate> query(
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
    return workflowTemplateService.query(limit, page, sort, labels, names);
  }

  @PostMapping(value = "")
  @Operation(
      summary = "Create a new Workflow Template",
      description =
          "The name needs to be unique and must only contain alphanumeric and - characeters.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowTemplate create(@RequestBody WorkflowTemplate request) {
    return workflowTemplateService.create(request);
  }

  @PutMapping(value = "")
  @Operation(
      summary = "Update, replace, or create new, Task Template",
      description =
          "The name must only contain alphanumeric and - characeters. If the name exists, apply will create a new version.")
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
    return workflowTemplateService.apply(request, replace);
  }

  @DeleteMapping(value = "/{name}")
  @Operation(summary = "Delete a WorkflowTemplate")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "No Content"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Void> delete(
      @Parameter(name = "name", description = "Name of WorkflowTemplate", required = true)
          @PathVariable
          String name) {
    workflowTemplateService.delete(name);
    return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }
}

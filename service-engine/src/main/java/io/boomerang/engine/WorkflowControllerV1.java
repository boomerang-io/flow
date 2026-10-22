package io.boomerang.engine;

import io.boomerang.common.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/workflow")
@Tag(name = "Workflow Management", description = "Create, List, and Manage your Workflows.")
public class WorkflowControllerV1 {

  private final WorkflowService workflowService;

  public WorkflowControllerV1(WorkflowService workflowService) {
    this.workflowService = workflowService;
  }

  @GetMapping(value = "/{workflowId}")
  @Operation(summary = "Retrieve a version of the Workflow. Defaults to latest.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Workflow> getWorkflow(
      @Parameter(name = "workflowId", description = "ID of Workflow", required = true) @PathVariable
          String workflowId,
      @Parameter(name = "version", description = "Workflow Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version,
      @Parameter(name = "withTasks", description = "Include Workflow Tasks", required = false)
          @RequestParam(defaultValue = "true")
          boolean withTasks) {
    return workflowService.get(workflowId, version, withTasks);
  }

  @GetMapping(value = "/query")
  @Operation(summary = "Search for Workflows")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<Workflow> queryWorkflows(
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
              example = "active,archived",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> status,
      @Parameter(
              name = "workflows",
              description =
                  "List of Workflows to filter for. Does not validate the IDs provided. Defaults to all.",
              example = "63d3656ca845957db7d25ef0,63a3e732b0496509a7f1d763",
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
          Optional<Direction> sort,
      @Parameter(
              name = "summary",
              description = "Only return a summary of each Workflow",
              required = false)
          @RequestParam(required = false, defaultValue = "false")
          Boolean summary) {
    return workflowService.query(limit, page, sort, labels, status, workflows);
  }

  @GetMapping(value = "/count")
  @Operation(summary = "Retrieve a count of Workflows by Status.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<WorkflowCount> count(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "workflows",
              description =
                  "List of Workflows to filter for. Does not validate the IDs provided. Defaults to all.",
              example = "63d3656ca845957db7d25ef0,63a3e732b0496509a7f1d763",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflows,
      @Parameter(
              name = "fromDate",
              description = "The unix timestamp / date to search from in milliseconds since epoch",
              example = "1677589200000",
              required = false)
          @RequestParam
          Optional<Long> fromDate,
      @Parameter(
              name = "toDate",
              description = "The unix timestamp / date to search to in milliseconds since epoch",
              example = "1680267600000",
              required = false)
          @RequestParam
          Optional<Long> toDate) {
    Optional<Date> from = Optional.empty();
    Optional<Date> to = Optional.empty();
    if (fromDate.isPresent()) {
      from = Optional.of(new Date(fromDate.get()));
    }
    if (toDate.isPresent()) {
      to = Optional.of(new Date(toDate.get()));
    }

    return workflowService.count(from, to, labels, workflows);
  }

  @PostMapping(value = "")
  @Operation(summary = "Create a new Workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Workflow> createWorkflow(@RequestBody Workflow workflow) {
    return workflowService.create(workflow, false);
  }

  @PutMapping(value = "")
  @Operation(summary = "Update, replace, or create new, Workflow")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<Workflow> applyWorkflow(
      @RequestBody Workflow workflow,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workflowService.apply(workflow, replace);
  }

  @PostMapping(value = "/{workflowId}/submit")
  @Operation(
      summary = "Submit a Workflow to be run. Will queue the WorkflowRun ready for execution.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun submitWorkflow(
      @Parameter(name = "workflowId", description = "ID of Workflow", required = true) @PathVariable
          String workflowId,
      @Parameter(
              name = "start",
              description = "Start the WorkflowRun immediately after submission",
              required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean start,
      @RequestBody WorkflowSubmitRequest request) {
    return workflowService.submit(workflowId, request, start);
  }

  @GetMapping(value = "/{workflowId}/changelog")
  @Operation(
      summary = "Retrieve the changlog",
      description = "Retrieves each versions changelog and returns them all as a list.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<ChangeLogVersion>> getChangelog(
      @Parameter(name = "workflowId", description = "ID of Workflow", required = true) @PathVariable
          String workflowId) {
    return workflowService.changelog(workflowId);
  }

  @DeleteMapping(value = "/{workflowId}")
  @Operation(summary = "Delete all versions of a Workflow. This is destructive and irreversible.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "No Content"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void deleteWorkflow(
      @Parameter(name = "workflowId", description = "ID of Workflow", required = true) @PathVariable
          String workflowId) {
    workflowService.delete(workflowId);
  }
}

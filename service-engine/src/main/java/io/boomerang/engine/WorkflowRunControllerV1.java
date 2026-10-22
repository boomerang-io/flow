package io.boomerang.engine;

import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.engine.model.WorkflowRunEventRequest;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflowrun")
@Tag(
    name = "WorkflowRun",
    description = "Submit, View, Start, End, and Update Status of your WorkflowRuns.")
public class WorkflowRunControllerV1 {

  private final WorkflowRunService workflowRunService;

  public WorkflowRunControllerV1(WorkflowRunService workflowRunService) {
    this.workflowRunService = workflowRunService;
  }

  @GetMapping(value = "/{workflowRunId}")
  @Operation(summary = "Retrieve a specific WorkflowRun.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun get(
      @Parameter(name = "workflowRunId", description = "ID of WorkflowRun", required = true)
          @PathVariable
          String workflowRunId,
      @Parameter(
              name = "withTasks",
              description = "Include Task Runs in the response",
              required = false)
          @RequestParam(defaultValue = "false")
          boolean withTasks) {
    return workflowRunService.get(workflowRunId, withTasks);
  }

  @GetMapping(value = "/query")
  @Operation(summary = "Search for WorkflowRuns")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<WorkflowRun> query(
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
              example = "succeeded,skipped",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> status,
      @Parameter(
              name = "phase",
              description = "List of phases to filter for. Defaults to all.",
              example = "completed,finalized",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> phase,
      @Parameter(
              name = "workflowruns",
              description =
                  "List of Workflowrun IDs  to filter for. Does not validate the IDs provided. Defaults to all.",
              example = "63d3656ca845957db7d25ef0,63a3e732b0496509a7f1d763",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflowruns,
      @Parameter(
              name = "workflows",
              description =
                  "List of Workflow IDs  to filter for. Does not validate the IDs provided. Defaults to all.",
              example = "63d3656ca845957db7d25ef0,63a3e732b0496509a7f1d763",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflows,
      @Parameter(
              name = "triggers",
              description = "List of Triggers to filter for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> triggers,
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
    return workflowRunService.query(
        from, to, limit, page, sort, labels, status, phase, workflowruns, workflows, triggers);
  }

  @GetMapping(value = "/insight")
  @Operation(summary = "Retrieve WorkflowRun Insights.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRunInsight insights(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "workflowruns",
              description =
                  "List of Workflowrun IDs  to filter for. Does not validate the IDs provided. Defaults to all.",
              example = "63d3656ca845957db7d25ef0,63a3e732b0496509a7f1d763",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflowruns,
      @Parameter(
              name = "workflows",
              description =
                  "List of Workflow IDs  to filter for. Does not validate the IDs provided. Defaults to all.",
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

    return workflowRunService.insights(from, to, labels, workflowruns, workflows);
  }

  @GetMapping(value = "/count")
  @Operation(summary = "Retrieve a count of WorkflowRuns by Status.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRunCount count(
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
                  "List of Workflow IDs  to filter for. Does not validate the IDs provided. Defaults to all.",
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

    return workflowRunService.count(from, to, labels, workflows);
  }

  @PutMapping(value = "/{workflowRunId}/start")
  @Operation(
      summary = "Start WorkflowRun execution. The WorkflowRun has to already have been queued.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun start(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Start",
              required = true)
          @PathVariable(required = true)
          String workflowRunId,
      @RequestBody Optional<WorkflowRunRequest> runRequest) {
    return workflowRunService.start(workflowRunId, runRequest);
  }

  @PutMapping(value = "/{workflowRunId}/event")
  @Operation(
      summary =
          "Provide an event to the WorkflowRun execution. The WorkflowRun has to already have been started.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void event(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Start",
              required = true)
          @PathVariable(required = true)
          String workflowRunId,
      @RequestBody WorkflowRunEventRequest request) {
    workflowRunService.event(workflowRunId, request);
  }

  @PutMapping(value = "/{workflowRunId}/finalize")
  @Operation(summary = "End a WorkflowRun")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun finalize(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Finalize",
              required = true)
          @PathVariable(required = true)
          String workflowRunId) {
    return workflowRunService.finalize(workflowRunId);
  }

  @DeleteMapping(value = "/{workflowRunId}/cancel")
  @Operation(summary = "Cancel a WorkflowRun")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun cancel(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Cancel",
              required = true)
          @PathVariable(required = true)
          String workflowRunId) {
    return workflowRunService.cancel(workflowRunId);
  }

  @PutMapping(value = "/{workflowRunId}/retry")
  @Operation(summary = "Retry WorkflowRun execution.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun retry(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Retry.",
              required = true)
          @PathVariable(required = true)
          String workflowRunId,
      @Parameter(
              name = "start",
              description = "Start the WorkflowRun immediately after submission",
              required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean start) {
    return workflowRunService.retry(workflowRunId, start, 1);
  }

  @DeleteMapping(value = "/{workflowRunId}")
  @Operation(
      summary =
          "Delete a WorkflowRun and associated TaskRuns. This is destructive and irreversible.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "No Content"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void delete(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Retry.",
              required = true)
          @PathVariable(required = true)
          String workflowRunId) {
    workflowRunService.delete(workflowRunId);
  }
}

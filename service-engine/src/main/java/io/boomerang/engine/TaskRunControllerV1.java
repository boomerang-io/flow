package io.boomerang.engine;

import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/taskrun")
@Tag(name = "Task Run", description = "View, Start, Stop, and Update Status of your Task Runs.")
public class TaskRunControllerV1 {

  private final TaskRunService taskRunService;

  public TaskRunControllerV1(TaskRunService taskRunService) {
    this.taskRunService = taskRunService;
  }

  @GetMapping(value = "/query")
  @Operation(summary = "Search for Task Runs.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<TaskRun> queryTaskRuns(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "status",
              description = "List of statuses to filter for. Defaults to 'ready'.",
              example = "succeeded,skipped",
              required = false)
          @RequestParam(defaultValue = "ready", required = false)
          Optional<List<String>> status,
      @Parameter(
              name = "phase",
              description = "List of phases to filter for. Defaults to 'pending'.",
              example = "completed,finalized",
              required = false)
          @RequestParam(defaultValue = "pending", required = false)
          Optional<List<String>> phase,
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
    return taskRunService.query(from, to, limit, page, sort, labels, status, phase);
  }

  @GetMapping(value = "/{taskRunId}")
  @Operation(summary = "Retrieve a specific Task Run.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<TaskRun> getTaskRuns(
      @Parameter(name = "taskRunId", description = "ID of Task Run to Start", required = true)
          @PathVariable(required = true)
          String taskRunId) {
    return taskRunService.get(taskRunId);
  }

  @PutMapping(value = "/{taskRunId}/start")
  @Operation(summary = "Start a Task Run. The Task Run has to already be queued.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<TaskRun> startTaskRun(
      @Parameter(name = "taskRunId", description = "ID of Task Run to Start", required = true)
          @PathVariable(required = true)
          String taskRunId,
      @RequestBody Optional<TaskRunStartRequest> taskRunRequest) {
    return taskRunService.start(taskRunId, taskRunRequest);
  }

  @PutMapping(value = "/{taskRunId}/end")
  @Operation(summary = "End the Task Run.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<TaskRun> endTaskRun(
      @Parameter(name = "taskRunId", description = "ID of Task Run to End", required = true)
          @PathVariable(required = true)
          String taskRunId,
      @RequestBody Optional<TaskRunEndRequest> taskRunRequest) {
    return taskRunService.end(taskRunId, taskRunRequest);
  }

  @PutMapping(value = "/{taskRunId}/cancel")
  @Operation(summary = "Cancel a Task Run")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<TaskRun> cancelTaskRun(
      @Parameter(name = "taskRunId", description = "ID of Task Run to Cancel", required = true)
          @PathVariable(required = true)
          String taskRunId) {
    return taskRunService.cancel(taskRunId);
  }

  @GetMapping(value = "/{taskRunId}/log")
  @Operation(summary = "Start a Task Run. The Task Run has to already be queued.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  @ResponseBody
  public ResponseEntity<StreamingResponseBody> getTaskRunLog(
      HttpServletResponse response,
      @Parameter(name = "taskRunId", description = "ID of Task Run to Start", required = true)
          @PathVariable(required = true)
          String taskRunId) {
    response.setContentType("text/plain");
    response.setCharacterEncoding("UTF-8");
    return new ResponseEntity<StreamingResponseBody>(
        taskRunService.streamLog(taskRunId), HttpStatus.OK);
  }
}

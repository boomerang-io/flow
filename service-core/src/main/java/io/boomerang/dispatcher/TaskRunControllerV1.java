package io.boomerang.dispatcher;

import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.engine.TaskRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The v1 platform-facing surface (query/get/cancel/log-stream) was removed at the merge (ruling
 * J1: "the engine's noauth V1 controllers are scrapped at the merge; the agent wire protocol is
 * the only additional surface"). Embedders/hosts use the v2 surface ({@link
 * io.boomerang.api.TaskRunControllerV2} for log streaming; query/get/cancel have no v2 caller
 * today and were not carried forward). What remains here is ONLY what the dispatcher worker's
 * {@code EngineClient} actually calls (byte-identical paths: {@code PUT
 * /api/v1/taskrun/{taskRunId}/start} and {@code .../end}) — the agent lifecycle callback surface.
 */
@RestController
@RequestMapping("/api/v1/taskrun")
@Tag(name = "Task Run", description = "Dispatcher agent lifecycle callbacks for Task Runs.")
public class TaskRunControllerV1 {

  private final TaskRunService taskRunService;

  public TaskRunControllerV1(TaskRunService taskRunService) {
    this.taskRunService = taskRunService;
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
}

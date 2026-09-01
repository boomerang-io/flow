package io.boomerang.dispatcher;

import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.HeartbeatRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.engine.TaskRunService;
import io.boomerang.workflow.WorkflowRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The whole v1 dispatcher protocol — registration, the two queue polls, and the four lifecycle
 * callbacks — served under the single {@code /api/v1/dispatcher} path root.
 *
 * <p>The callbacks previously lived on {@code /api/v1/workflowrun} and {@code /api/v1/taskrun} in
 * separate {@code WorkflowRunControllerV1}/{@code TaskRunControllerV1} classes — v4 residue from
 * when the engine was a separate deployable with its own platform-facing v1 surface. That surface
 * was removed at the merge (ruling J1: "the engine's noauth V1 controllers are scrapped at the
 * merge; the agent wire protocol is the only additional surface"), leaving three path roots for one
 * protocol with exactly one consumer, {@code service-dispatcher}. Consolidating them here is a
 * maintainer-approved wire change; {@code service-dispatcher}'s {@code flow.engine.*.url} properties move
 * with it. Embedders/hosts use the v2 surface ({@link
 * io.boomerang.workflow.WorkspaceWorkflowRunControllerV2}, {@link io.boomerang.workflow.TaskRunControllerV2}).
 *
 * <p>{@code /{id}/workflows} and {@code /workflowrun/{id}/start} overlap in shape; Spring's
 * {@code RequestMappingInfo} ordering prefers a literal segment over a path variable, so a
 * dispatcher whose id is literally {@code workflowrun} still routes to the queue endpoint —
 * pinned by {@code DispatcherRouteCollisionTest}.
 */
@RestController
@RequestMapping("/api/v1/dispatcher")
@Tag(
    name = "Dispatcher",
    description =
        "Manage Dispatcher operations. Register dispatcher. Check for WorkflowRuns and TaskRuns")
public class DispatcherControllerV1 {
  private final DispatcherService dispatcherService;
  private final WorkflowRunService workflowRunService;
  private final TaskRunService taskRunService;

  public DispatcherControllerV1(
      DispatcherService dispatcherService,
      WorkflowRunService workflowRunService,
      TaskRunService taskRunService) {
    this.dispatcherService = dispatcherService;
    this.workflowRunService = workflowRunService;
    this.taskRunService = taskRunService;
  }

  @PostMapping(value = "/register")
  @Operation(summary = "Register a Dispatcher")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public String registerDispatcher(@RequestBody DispatcherRegistrationRequest request) {
    return dispatcherService.register(request);
  }

  @GetMapping(value = "/{id}/workflows")
  @Operation(summary = "Retrieve a dispatcher's Workflows queue")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "204", description = "No Items Found"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<WorkflowRun>> dispatcherWorkflowQueue(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id) {
    return dispatcherService.getWorkflowQueue(id);
  }

  @GetMapping(value = "/{id}/tasks")
  @Operation(summary = "Retrieve a dispatcher's Tasks queue")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "204", description = "No Items Found"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<TaskRun>> dispatcherTasksQueue(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id) {
    return dispatcherService.getTaskQueue(id);
  }

  @PutMapping(value = "/{id}/heartbeat")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Renew the lease on the dispatcher's still-owned TaskRuns")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "No Content"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void heartbeat(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id,
      @RequestBody HeartbeatRequest request) {
    dispatcherService.heartbeat(id, request.ids());
  }

  @PutMapping(value = "/workflowrun/{workflowRunId}/start")
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

  @PutMapping(value = "/workflowrun/{workflowRunId}/finalize")
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

  @PutMapping(value = "/taskrun/{taskRunId}/start")
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

  @PutMapping(value = "/taskrun/{taskRunId}/end")
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

package io.boomerang.dispatcher;

import io.boomerang.core.security.AuthExempt;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.HeartbeatRequest;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.common.model.WorkspaceReleaseQuery;
import io.boomerang.common.model.WorkspaceReleaseResponse;
import io.boomerang.dispatcher.model.DispatchTaskRun;
import io.boomerang.dispatcher.model.DispatchWorkflowRun;
import io.boomerang.engine.TaskRunService;
import io.boomerang.workflow.WorkflowRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The whole v1 dispatcher protocol — registration, the two queue polls, the lifecycle callbacks and
 * the workspace release query — served under the single {@code /dispatcher/v1} path root. The
 * prefix is its own, apart from the public {@code /api/v2}, so that whatever fronts the engine can
 * route the dispatcher wire separately: a dispatcher may run outside the engine's network.
 *
 * <p>Every dispatcher route lives here, under one root, and runs as the dispatcher's token
 * ({@link DispatcherAuthFilter}). Embedders and hosts use the v2 surface ({@link
 * io.boomerang.workflow.WorkspaceWorkflowRunControllerV2}, {@link io.boomerang.workflow.TaskRunControllerV2}).
 *
 * <p>The runs go out as the wire's own models, {@link DispatchWorkflowRun} and {@link
 * DispatchTaskRun}, not the public {@code WorkflowRun} and {@code TaskRun}: a field added to a
 * public run model for people and hosts does not reach a dispatcher.
 *
 * <p>{@code /{id}/workflows} and {@code /workflowrun/{id}/start} overlap in shape; Spring's
 * {@code RequestMappingInfo} ordering prefers a literal segment over a path variable, so a
 * dispatcher whose id is literally {@code workflowrun} still routes to the queue endpoint —
 * pinned by {@code DispatcherRouteCollisionTest}.
 */
@RestController
@RequestMapping("/dispatcher/v1")
@AuthExempt(
    reason =
        "The dispatcher wire. Identity is a dispatcher actor token (a global token with an actorKind)"
            + " checked by DispatcherAuthFilter on the /dispatcher/** chain; there is no per-route"
            + " permission model for a dispatcher, it may do everything the protocol defines.")
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
  public ResponseEntity<List<DispatchWorkflowRun>> dispatcherWorkflowQueue(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id) {
    return toWire(
        dispatcherService.getWorkflowQueue(id),
        runs -> runs.stream().map(DispatchWorkflowRun::of).toList());
  }

  @GetMapping(value = "/{id}/tasks")
  @Operation(summary = "Retrieve a dispatcher's Tasks queue")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "204", description = "No Items Found"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<List<DispatchTaskRun>> dispatcherTasksQueue(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id,
      @Parameter(
              name = "limit",
              description =
                  "Most new TaskRuns to claim for execution (the dispatcher's free slots), capped"
                      + " at a page of 20. 0 claims none and still returns termination orders.")
          @RequestParam(required = false)
          Integer limit,
      @Parameter(
              name = "type",
              description =
                  "Task types to claim, a subset of those registered; comma-separated, `*` the only"
                      + " wildcard. Absent means every registered type.")
          @RequestParam(required = false)
          String type,
      @Parameter(
              name = "task",
              description = "Task slugs to claim; comma-separated, `*` the only wildcard.")
          @RequestParam(required = false)
          String task,
      @Parameter(
              name = "workflowLabel",
              description =
                  "One label on the workflow definition, as key=value[,value]; `*` the only"
                      + " wildcard in the values.")
          @RequestParam(required = false)
          String workflowLabel) {
    return toWire(
        dispatcherService.getTaskQueue(id, limit, type, task, workflowLabel),
        taskRuns -> taskRuns.stream().map(DispatchTaskRun::of).toList());
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
  public DispatchWorkflowRun start(
      @Parameter(
              name = "workflowRunId",
              description = "ID of WorkflowRun to Start",
              required = true)
          @PathVariable(required = true)
          String workflowRunId,
      @RequestBody Optional<WorkflowRunRequest> runRequest) {
    return DispatchWorkflowRun.of(workflowRunService.start(workflowRunId, runRequest));
  }

  @PostMapping(value = "/workspaces/releasable")
  @Operation(
      summary =
          "Ask which of the runs a dispatcher holds a workspace volume for are finished, so those"
              + " volumes can be released.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkspaceReleaseResponse releasableWorkspaces(@RequestBody WorkspaceReleaseQuery query) {
    return dispatcherService.releasable(query);
  }

  @PutMapping(value = "/taskrun/{taskRunId}/start")
  @Operation(summary = "Start a Task Run. The Task Run has to already be queued.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<DispatchTaskRun> startTaskRun(
      @Parameter(name = "taskRunId", description = "ID of Task Run to Start", required = true)
          @PathVariable(required = true)
          String taskRunId,
      @RequestBody Optional<TaskRunStartRequest> taskRunRequest) {
    return toWire(dispatcherService.startTaskRun(taskRunId, taskRunRequest), DispatchTaskRun::of);
  }

  @PutMapping(value = "/taskrun/{taskRunId}/end")
  @Operation(summary = "End the Task Run.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public ResponseEntity<DispatchTaskRun> endTaskRun(
      @Parameter(name = "taskRunId", description = "ID of Task Run to End", required = true)
          @PathVariable(required = true)
          String taskRunId,
      @RequestBody Optional<TaskRunEndRequest> taskRunRequest) {
    return toWire(dispatcherService.endTaskRun(taskRunId, taskRunRequest), DispatchTaskRun::of);
  }

  @PutMapping(value = "/taskrun/{taskRunId}/log", consumes = MediaType.ALL_VALUE)
  @Operation(
      summary =
          "Store the Task Run's finished log. Optional: send it before the end so the log outlives"
              + " the runtime; a dispatcher that serves logs at its registered logsUrl may skip it.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "The log, as text",
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.TEXT_PLAIN_VALUE,
                      schema = @Schema(type = "string"))))
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Stored"),
        @ApiResponse(responseCode = "409", description = "The claim is held by another dispatcher"),
        @ApiResponse(
            responseCode = "413",
            description = "The log is over flow.engine.task.log.max-bytes and was not stored"),
        @ApiResponse(responseCode = "503", description = "No artifact store is configured")
      })
  public ResponseEntity<Void> storeTaskRunLog(
      @Parameter(name = "taskRunId", description = "ID of the Task Run", required = true)
          @PathVariable(required = true)
          String taskRunId,
      @Parameter(
              name = "dispatcherRef",
              description = "The registered id of the dispatcher holding the claim")
          @RequestParam(required = false)
          Optional<String> dispatcherRef,
      HttpServletRequest request)
      throws IOException {
    taskRunService.storeLog(taskRunId, dispatcherRef, request.getInputStream());
    return ResponseEntity.noContent().build();
  }

  // The answer as the wire carries it: the same status and headers, the body in the wire's model.
  private static <T, R> ResponseEntity<R> toWire(ResponseEntity<T> answer, Function<T, R> model) {
    return ResponseEntity.status(answer.getStatusCode())
        .headers(answer.getHeaders())
        .body(answer.getBody() == null ? null : model.apply(answer.getBody()));
  }
}

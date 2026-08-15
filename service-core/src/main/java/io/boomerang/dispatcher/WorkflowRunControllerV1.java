package io.boomerang.dispatcher;

import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.engine.WorkflowRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The v1 platform-facing definition/query surface (get/query/insight/count/event/cancel/pause
 * /resume/retry/delete) was removed at the merge (ruling J1: "the engine's noauth V1 controllers
 * are scrapped at the merge; the agent wire protocol is the only additional surface").
 * Embedders/hosts use the v2 surface ({@link io.boomerang.api.WorkspaceWorkflowRunControllerV2}).
 * What remains here is ONLY what the dispatcher worker's {@code EngineClient} actually calls
 * (byte-identical paths: {@code PUT /api/v1/workflowrun/{workflowRunId}/start} and {@code
 * .../finalize}) — the agent lifecycle callback surface.
 */
@RestController
@RequestMapping("/api/v1/workflowrun")
@Tag(name = "WorkflowRun", description = "Dispatcher agent lifecycle callbacks for WorkflowRuns.")
public class WorkflowRunControllerV1 {

  private final WorkflowRunService workflowRunService;

  public WorkflowRunControllerV1(WorkflowRunService workflowRunService) {
    this.workflowRunService = workflowRunService;
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
}

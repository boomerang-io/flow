package io.boomerang.engine;

import io.boomerang.common.model.AgentRegistrationRequest;
import io.boomerang.common.model.TaskRunDispatch;
import io.boomerang.common.model.WorkflowRunDispatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dispatcher")
@Tag(
    name = "Dispatcher",
    description =
        "Manage Dispatcher operations. Register dispatcher. Check for WorkflowRuns and TaskRuns")
public class DispatcherControllerV1 {
  private final DispatcherService dispatcherService;

  public DispatcherControllerV1(DispatcherService dispatcherService) {
    this.dispatcherService = dispatcherService;
  }

  @PostMapping(value = "/register")
  @Operation(summary = "Register a Dispatcher")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  // TODO when these are exposed externally for public / private dispatchers, require token
  // authentication
  public String registerDispatcher(@RequestBody AgentRegistrationRequest request) {
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
  // TODO when these are exposed externally for public / private dispatchers, require token
  // authentication
  public ResponseEntity<List<WorkflowRunDispatch>> dispatcherWorkflowQueue(
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
  // TODO when these are exposed externally for public / private dispatchers, require token
  // authentication
  public ResponseEntity<List<TaskRunDispatch>> dispatcherTasksQueue(
      @Parameter(name = "id", description = "Dispatcher ID", required = true) @PathVariable
          String id) {
    return dispatcherService.getTaskQueue(id);
  }
}

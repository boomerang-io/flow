package io.boomerang.workflow;

import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v2/taskrun")
@Tag(name = "TaskRuns", description = "View, Start, Stop, and Update Status of your Task Runs.")
public class TaskRunControllerV2 {

  private final TaskRunService taskRunService;

  public TaskRunControllerV2(TaskRunService taskRunService) {
    this.taskRunService = taskRunService;
  }

  @GetMapping(value = "/{taskRunId}/log")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASKRUN,
      assignableScopes = {
        AuthScope.team,
        AuthScope.session,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.global
      })
  @Operation(summary = "Retrieve a TaskRuns log from a specific WorkflowRun.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  @ResponseBody
  public ResponseEntity<StreamingResponseBody> streamTaskRunLog(
      @Parameter(name = "taskRunId", description = "Id of TaskRun", required = true) @PathVariable
          String taskRunId,
      HttpServletResponse response) {
    response.setContentType("text/plain");
    response.setCharacterEncoding("UTF-8");
    return new ResponseEntity<StreamingResponseBody>(
        taskRunService.streamLog(taskRunId), HttpStatus.OK);
  }
}

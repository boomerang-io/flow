package io.boomerang.core;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

// TODO: Implement the controller
@RestController
@RequestMapping("/api/v2/audit")
@Tag(name = "Audit Management", description = "View the audit and activity log.")
public class AuditControllerV2 {

  //  @Autowired
  //  private TaskRunService taskRunService;
  //
  //  @GetMapping(value = "/{taskRunId}/log")
  //  @AuthScope(action = PermissionAction.READ, scope = PermissionScope.TASKRUN, types =
  // {AuthType.team})
  //  @Operation(summary = "Retrieve a TaskRuns log from a specific WorkflowRun.")
  //  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK"),
  //      @ApiResponse(responseCode = "400", description = "Bad Request")})
  //  @ResponseBody
  //  public ResponseEntity<StreamingResponseBody> streamTaskRunLog(
  //      @Parameter(name = "taskRunId",
  //      description = "Id of TaskRun",
  //      required = true) @PathVariable String taskRunId,
  //      HttpServletResponse response) {
  //  response.setContentType("text/plain");
  //  response.setCharacterEncoding("UTF-8");
  //  return new ResponseEntity<StreamingResponseBody>(taskRunService.streamLog(taskRunId),
  // HttpStatus.OK);
  //  }
}

package io.boomerang.workflow;

import io.boomerang.common.model.TaskRun;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspace/{workspace}/taskrun")
@Tag(name = "TaskRuns", description = "View, Start, Stop, and Update Status of your Task Runs.")
@SecurityRequirement(name = "BearerAuth")
@SecurityRequirement(name = "x-access-token")
public class WorkspaceTaskRunControllerV2 {

  private final WorkflowRunService workflowRunService;

  public WorkspaceTaskRunControllerV2(WorkflowRunService workflowRunService) {
    this.workflowRunService = workflowRunService;
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASKRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Search for TaskRuns across WorkflowRuns",
      description =
          "Lists task runs by label or by WorkflowRun; one of the two is required. A row leaves out"
              + " params, results, spec, annotations and the status message - retrieve the"
              + " WorkflowRun for those.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<TaskRun> query(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. A WorkflowRun's labels are on each of its TaskRuns"
                      + " and a child WorkflowRun has its parent's. For example"
                      + " Organization=Boomerang,customKey=test would be encoded as"
                      + " Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "statuses",
              description = "List of statuses to filter for. Defaults to all.",
              example = "failed,cancelled",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> statuses,
      @Parameter(
              name = "phase",
              description = "List of phases to filter for. Defaults to all.",
              example = "running,completed",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> phase,
      @Parameter(
              name = "workflowruns",
              description = "List of WorkflowRun IDs to filter for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflowruns,
      @Parameter(
              name = "workflows",
              description = "List of Workflow IDs to filter for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflows,
      @Parameter(
              name = "tasks",
              description =
                  "List of task names to filter for. A name also matches its for-each items.",
              example = "checklist,review",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> tasks,
      @Parameter(
              name = "foreach",
              description =
                  "true lists for-each parents only, the task run that stands for a for-each"
                      + " task's items and never runs itself; false leaves them out. Defaults to"
                      + " both.",
              example = "false",
              required = false)
          @RequestParam(required = false)
          Optional<Boolean> foreach,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "order",
              description = "Ascending (ASC) or Descending (DESC) sort order on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> order,
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
    return workflowRunService.queryTaskRuns(
        workspace,
        fromDate,
        toDate,
        limit,
        page,
        order,
        labels,
        statuses,
        phase,
        workflowruns,
        workflows,
        tasks,
        foreach);
  }
}

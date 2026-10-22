package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightTasks;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Insights page's reads: statistics over the runs a workspace still holds. The quota's run
 * figures come from the audit trail instead ({@code AuditQueryService.runTotals}).
 */
@RestController
@RequestMapping("/api/v2/workspace/{workspace}/insights")
@Tag(name = "Insights", description = "Provide the ability to search and retrieve Insights.")
public class WorkspaceInsightsControllerV2 {

  private static final Duration DEFAULT_PERIOD = Duration.ofDays(90);
  private static final String LABELS_DESCRIPTION =
      "Run labels to narrow by, as the run query takes them: URL-encoded key=value, comma-separated,"
          + " all of which must match.";

  private final WorkflowRunInsightService workflowRunInsightService;

  public WorkspaceInsightsControllerV2(WorkflowRunInsightService workflowRunInsightService) {
    this.workflowRunInsightService = workflowRunInsightService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Summarise a workspace's runs over a period",
      description =
          "Counts, success rate, duration and queue-wait percentiles, runs per day, one row per"
              + " workflow and the model usage of its ai tasks, with the same-length previous period"
              + " for comparison. Computed from the runs the workspace still holds; the period"
              + " defaults to the last 90 days.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public WorkflowRunInsightSummary summary(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @RequestParam Optional<List<String>> workflows,
      @Parameter(name = "labels", description = LABELS_DESCRIPTION) @RequestParam
          Optional<List<String>> labels,
      @RequestParam Optional<List<String>> statuses,
      @RequestParam Optional<List<String>> triggers) {
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.summary(
        workspace, from, to, workflows, labels, statuses, triggers);
  }

  @GetMapping(value = "/workflow/{workflow}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Detail one workflow's runs over a period",
      description =
          "Where a typical run spends its time (per task), why runs did not succeed and the model"
              + " usage of its ai tasks, from the runs and task runs the workspace still holds.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public WorkflowRunInsightWorkflowDetail workflow(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "workflow", description = "Workflow name or id.", required = true)
          @PathVariable
          String workflow,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @Parameter(name = "labels", description = LABELS_DESCRIPTION) @RequestParam
          Optional<List<String>> labels) {
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.workflowDetail(workspace, workflow, from, to, labels);
  }

  @GetMapping(value = "/tasks")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Group a workspace's task runs over a period",
      description =
          "Counts and duration statistics (mean, p50, p95, max over succeeded task runs) per"
              + " group and, optionally, per hour, day or month, with the usage its ai task runs"
              + " reported; the wall clock of the runs sharing a label; and the slowest task runs."
              + " A task run counts once it completed, unless it was skipped, and a for-each task"
              + " counts its items under its own name. Computed from the runs the workspace still"
              + " holds; the period defaults to the last 90 days.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public WorkflowRunInsightTasks tasks(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @RequestParam Optional<List<String>> workflows,
      @Parameter(name = "labels", description = LABELS_DESCRIPTION) @RequestParam
          Optional<List<String>> labels,
      @Parameter(name = "tasks", description = "Task node names to count.") @RequestParam
          Optional<List<String>> tasks,
      @Parameter(name = "types", description = "Task types to count, such as ai.") @RequestParam
          Optional<List<String>> types,
      @Parameter(
              name = "groupBy",
              description =
                  "task, type, workflow or label:<key> (a task run's label, such as the server's"
                      + " boomerang.io/model or boomerang.io/item); one row when absent.",
              example = "label:boomerang.io/model")
          @RequestParam
          Optional<String> groupBy,
      @Parameter(name = "bucket", description = "hour, day or month (UTC), by task run start.")
          @RequestParam
          Optional<String> bucket,
      @Parameter(
              name = "spanBy",
              description =
                  "label:<key>: per row, the first start to the last end of the task runs of the"
                      + " runs sharing each value of that run label.")
          @RequestParam
          Optional<String> spanBy,
      @Parameter(name = "slowest", description = "How many slowest task runs to list, up to 100.")
          @RequestParam(defaultValue = "0")
          int slowest) {
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.tasks(
        workspace,
        from,
        to,
        workflows,
        labels,
        tasks,
        types,
        groupBy,
        bucket,
        spanBy,
        slowest);
  }
}

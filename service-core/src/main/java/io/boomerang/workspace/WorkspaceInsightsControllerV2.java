package io.boomerang.workspace;

import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightWorkflowDetail;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workflow.WorkflowRunInsightService;
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
 * The Insights page's reads: statistics over the runs a workspace still holds. The audit-trail
 * roll-up ({@code InsightsService}) is not served here; it feeds the quotas.
 */
@RestController
@RequestMapping("/api/v2/workspace/{workspace}/insights")
@Tag(name = "Insights", description = "Provide the ability to search and retrieve Insights.")
public class WorkspaceInsightsControllerV2 {

  private static final Duration DEFAULT_PERIOD = Duration.ofDays(90);

  private final WorkflowRunInsightService workflowRunInsightService;

  public WorkspaceInsightsControllerV2(WorkflowRunInsightService workflowRunInsightService) {
    this.workflowRunInsightService = workflowRunInsightService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Summarise a workspace's runs over a period",
      description =
          "Counts, success rate, duration and queue-wait percentiles, runs per day and one row per"
              + " workflow, with the same-length previous period for comparison. Computed from the"
              + " runs the workspace still holds; the period defaults to the last 90 days.")
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
      @RequestParam Optional<List<String>> statuses,
      @RequestParam Optional<List<String>> triggers) {
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.summary(workspace, from, to, workflows, statuses, triggers);
  }

  @GetMapping(value = "/workflow/{workflow}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Detail one workflow's runs over a period",
      description =
          "Where a typical run spends its time (per task) and why runs did not succeed, from the"
              + " runs and task runs the workspace still holds.")
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
      @RequestParam Optional<Long> toDate) {
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.workflowDetail(workspace, workflow, from, to);
  }
}

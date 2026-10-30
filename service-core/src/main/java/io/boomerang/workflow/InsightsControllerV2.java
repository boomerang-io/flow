package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowRunInsightSummary;
import io.boomerang.common.model.WorkflowRunInsightTaskRunPage;
import io.boomerang.common.model.WorkflowRunInsightTasks;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.IdentityService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insights across workspaces, for a platform-wide grant: the same reads a workspace has, computed
 * over the union of the named workspaces' runs, or every workspace's when none is named, plus a
 * page of task runs with their workspace. {@code @AuthCriteria} admits the token classes an admin
 * and an automation carry; the grant must then be {@code global}, as the audit routes insist, so
 * a workspace-scoped token holding {@code insights/read} is refused.
 */
@RestController
@RequestMapping("/api/v2/insights")
@Tag(name = "Insights", description = "Provide the ability to search and retrieve Insights.")
public class InsightsControllerV2 {

  private static final Duration DEFAULT_PERIOD = Duration.ofDays(90);
  private static final String WORKSPACES_DESCRIPTION =
      "Workspace names to aggregate over; absent means every workspace. One that does not exist"
          + " contributes nothing.";
  private static final String LABELS_DESCRIPTION =
      "Run labels to narrow by, as the run query takes them: URL-encoded key=value, comma-separated,"
          + " all of which must match.";

  private final WorkflowRunInsightService workflowRunInsightService;
  private final IdentityService identityService;

  public InsightsControllerV2(
      WorkflowRunInsightService workflowRunInsightService, IdentityService identityService) {
    this.workflowRunInsightService = workflowRunInsightService;
    this.identityService = identityService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Summarise runs across workspaces over a period",
      description =
          "The workspace summary computed over the union of the named workspaces' runs, or every"
              + " workspace's when none is named, with the same-length previous period for"
              + " comparison. Needs a global insights grant; the period defaults to the last 90 days.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  public WorkflowRunInsightSummary summary(
      @Parameter(name = "workspaces", description = WORKSPACES_DESCRIPTION) @RequestParam
          Optional<List<String>> workspaces,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @RequestParam Optional<List<String>> workflows,
      @Parameter(name = "labels", description = LABELS_DESCRIPTION) @RequestParam
          Optional<List<String>> labels,
      @RequestParam Optional<List<String>> statuses,
      @RequestParam Optional<List<String>> triggers) {
    identityService.requireGlobalGrant(PermissionResource.INSIGHTS, PermissionAction.READ);
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.summaryAcross(
        workspaces, from, to, workflows, labels, statuses, triggers);
  }

  @GetMapping(value = "/tasks")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Group task runs across workspaces over a period",
      description =
          "The workspace task breakdown computed over the union of the named workspaces' runs, or"
              + " every workspace's when none is named. Needs a global insights grant; the period"
              + " defaults to the last 90 days.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  public WorkflowRunInsightTasks tasks(
      @Parameter(name = "workspaces", description = WORKSPACES_DESCRIPTION) @RequestParam
          Optional<List<String>> workspaces,
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
              description = "task, type, workflow or label:<key>; one row when absent.",
              example = "label:boomerang.io/model")
          @RequestParam
          Optional<String> groupBy,
      @Parameter(name = "bucket", description = "hour, day or month (UTC), by task run start.")
          @RequestParam
          Optional<String> bucket,
      @Parameter(
              name = "spanBy",
              description = "label:<key>: the wall clock of the runs sharing each value of a run label.")
          @RequestParam
          Optional<String> spanBy,
      @Parameter(name = "slowest", description = "How many of the slowest task runs to list, up to 100.")
          @RequestParam(defaultValue = "0")
          int slowest) {
    identityService.requireGlobalGrant(PermissionResource.INSIGHTS, PermissionAction.READ);
    Date to = toDate.map(Date::new).orElseGet(Date::new);
    Date from = fromDate.map(Date::new).orElse(new Date(to.getTime() - DEFAULT_PERIOD.toMillis()));
    return workflowRunInsightService.tasksAcross(
        workspaces, from, to, workflows, labels, tasks, types, groupBy, bucket, spanBy, slowest);
  }

  @GetMapping(value = "/taskruns")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "List task runs across workspaces",
      description =
          "One page of task runs over the named workspaces, or every workspace, newest first, each"
              + " with its workspace, its identity and its lifecycle: never params, results or spec."
              + " Task runs are indexed by label, so labels is required. Needs a global insights grant.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
      })
  public WorkflowRunInsightTaskRunPage taskRuns(
      @Parameter(name = "workspaces", description = WORKSPACES_DESCRIPTION) @RequestParam
          Optional<List<String>> workspaces,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @Parameter(name = "labels", description = LABELS_DESCRIPTION + " Required.", required = true)
          @RequestParam
          Optional<List<String>> labels,
      @RequestParam Optional<List<String>> statuses,
      @RequestParam Optional<List<String>> phases,
      @Parameter(name = "tasks", description = "Task node names; a name also matches its for-each items.")
          @RequestParam
          Optional<List<String>> tasks,
      @Parameter(name = "foreach", description = "true keeps for-each parents only, false leaves them out.")
          @RequestParam
          Optional<Boolean> foreach,
      @Parameter(name = "limit", description = "Result Size", example = "50") @RequestParam
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0") @RequestParam
          Optional<Integer> page) {
    identityService.requireGlobalGrant(PermissionResource.INSIGHTS, PermissionAction.READ);
    return workflowRunInsightService.taskRuns(
        workspaces, fromDate, toDate, labels, statuses, phases, tasks, foreach, limit, page);
  }
}

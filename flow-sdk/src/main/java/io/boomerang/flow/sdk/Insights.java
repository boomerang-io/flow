package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.WorkflowRunInsightSummary;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTaskRunPage;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTasks;
import io.boomerang.flow.sdk.model.WorkflowRunInsightWorkflowDetail;
import java.util.Map;
import tools.jackson.databind.JavaType;

/**
 * The Insights of one workspace ({@code contracts/api-v2.yaml}, {@code
 * /api/v2/workspace/{workspace}/insights}): statistics over the runs the workspace still holds.
 * Each route reads the part of an {@link InsightsQuery} it takes; the period defaults to the last
 * 90 days.
 */
public final class Insights {

  private static final JavaType SUMMARY =
      WireFormat.JSON.constructType(WorkflowRunInsightSummary.class);
  private static final JavaType TASKS =
      WireFormat.JSON.constructType(WorkflowRunInsightTasks.class);
  private static final JavaType WORKFLOW =
      WireFormat.JSON.constructType(WorkflowRunInsightWorkflowDetail.class);

  private static final JavaType TASK_RUNS =
      WireFormat.JSON.constructType(WorkflowRunInsightTaskRunPage.class);

  private final ApiClient api;
  // Null for the platform-wide reads under /insights, which a global grant makes across workspaces.
  private final String workspace;

  Insights(ApiClient api, String workspace) {
    this.api = api;
    this.workspace = workspace;
  }

  private String path(String suffix) {
    return (workspace != null) ? "/workspace/{workspace}/insights" + suffix : "/insights" + suffix;
  }

  private Map<String, Object> pathVariables() {
    return (workspace != null) ? Map.of("workspace", workspace) : Map.of();
  }

  // The platform-wide routes take the workspaces to aggregate over; a workspace's own do not.
  private Params scoped(InsightsQuery query, Params params) {
    return (workspace != null) ? params : params.addAll("workspaces", query.workspaceNames());
  }

  /**
   * {@code GET /insights}: counts, success rate, duration and queue-wait percentiles, runs per
   * day, a row per workflow and model usage, with the previous period beside it. Narrowed by the
   * period, workflows, labels, statuses and triggers.
   */
  public WorkflowRunInsightSummary summary(InsightsQuery query) {
    return api.get(path(""), pathVariables(), scoped(query, query.summaryParams()), SUMMARY);
  }

  /**
   * {@code GET /insights/tasks}: the period's task runs grouped by task, type, workflow or a
   * label, optionally per hour, day or month, with durations, model usage, the span of the runs
   * sharing a label and the slowest task runs. Narrowed by the period, workflows, labels, task
   * names and types.
   */
  public WorkflowRunInsightTasks tasks(InsightsQuery query) {
    return api.get(path("/tasks"), pathVariables(), scoped(query, query.tasksParams()), TASKS);
  }

  /**
   * {@code GET /insights/workflow/{workflow}}: where one workflow's runs spend their time, per
   * task, and why they did not succeed. Narrowed by the period and labels.
   */
  public WorkflowRunInsightWorkflowDetail workflow(String workflow, InsightsQuery query) {
    if (workspace == null) {
      throw new IllegalStateException("a workflow's detail is read through its workspace's insights");
    }
    return api.get(
        "/workspace/{workspace}/insights/workflow/{workflow}",
        Map.of("workspace", workspace, "workflow", workflow),
        query.workflowParams(),
        WORKFLOW);
  }

  /**
   * {@code GET /insights/taskruns}, platform-wide only: one page of task runs across the named
   * workspaces, newest first, each with its workspace. Narrowed by the period, labels (required),
   * statuses, phases, task names and the for-each switch; paged by {@code limit} and {@code page}.
   */
  public WorkflowRunInsightTaskRunPage taskRuns(InsightsQuery query) {
    if (workspace != null) {
      throw new IllegalStateException("task runs across workspaces are read without a workspace");
    }
    return api.get(path("/taskruns"), pathVariables(), scoped(query, query.taskRunsParams()), TASK_RUNS);
  }
}

package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.WorkflowRun;
import io.boomerang.flow.sdk.model.WorkflowRunCount;
import java.util.Map;
import tools.jackson.databind.JavaType;

/**
 * The workflow runs of one workspace, one method per route ({@code contracts/api-v2.yaml},
 * {@code /api/v2/workspace/{workspace}/workflowrun} and {@code .../workflow/{name}/submit}).
 */
public final class Runs {

  static final JavaType RUN = WireFormat.JSON.constructType(WorkflowRun.class);
  static final JavaType RUN_PAGE =
      WireFormat.JSON.getTypeFactory().constructParametricType(Page.class, WorkflowRun.class);
  private static final JavaType COUNT = WireFormat.JSON.constructType(WorkflowRunCount.class);

  private final ApiClient api;
  private final String workspace;

  Runs(ApiClient api, String workspace) {
    this.api = api;
    this.workspace = workspace;
  }

  /** {@code POST /workflow/{name}/submit}: queue a run of the workflow and start it. */
  public WorkflowRun submit(String workflow, SubmitRequest request) {
    return submit(workflow, request, true);
  }

  /**
   * {@code POST /workflow/{name}/submit?start=}: queue a run of the workflow. With {@code start}
   * false the run parks at {@code ready} for a later start.
   */
  public WorkflowRun submit(String workflow, SubmitRequest request, boolean start) {
    return api.post(
        "/workspace/{workspace}/workflow/{workflow}/submit",
        Map.of("workspace", workspace, "workflow", workflow),
        Params.none().add("start", start),
        request,
        RUN);
  }

  /** {@code GET /workflowrun/{id}}: the run with its task runs. */
  public WorkflowRun get(String id) {
    return get(id, true);
  }

  /** {@code GET /workflowrun/{id}?withTasks=}: the run, with or without its task runs. */
  public WorkflowRun get(String id, boolean withTasks) {
    return api.get(
        "/workspace/{workspace}/workflowrun/{id}",
        Map.of("workspace", workspace, "id", id),
        Params.none().add("withTasks", withTasks),
        RUN);
  }

  /**
   * {@code PUT /workflowrun/{id}/start}: start a run parked at {@code ready}, submitted with
   * {@code start} false or created by a retry; the answer is its state after.
   */
  public WorkflowRun start(String id) {
    return api.put(
        "/workspace/{workspace}/workflowrun/{id}/start",
        Map.of("workspace", workspace, "id", id),
        RUN);
  }

  /**
   * {@code PUT /workflowrun/{id}/pause}: ask the run to pause; tasks already running finish, and
   * no further task is admitted until {@link #resume}. The answer is its state after.
   */
  public WorkflowRun pause(String id) {
    return api.put(
        "/workspace/{workspace}/workflowrun/{id}/pause",
        Map.of("workspace", workspace, "id", id),
        RUN);
  }

  /** {@code PUT /workflowrun/{id}/resume}: lift a pause; the answer is the run's state after. */
  public WorkflowRun resume(String id) {
    return api.put(
        "/workspace/{workspace}/workflowrun/{id}/resume",
        Map.of("workspace", workspace, "id", id),
        RUN);
  }

  /** {@code DELETE /workflowrun/{id}/cancel}: cancel the run; the answer is its state after. */
  public WorkflowRun cancel(String id) {
    return api.delete(
        "/workspace/{workspace}/workflowrun/{id}/cancel",
        Map.of("workspace", workspace, "id", id),
        RUN);
  }

  /**
   * {@code PUT /workflowrun/{id}/retry}: create a new run from this one - the whole graph, the
   * tasks that did not succeed, or named tasks - with the params and labels of {@code retry}
   * merged over the origin's. The answer is the new run.
   */
  public WorkflowRun retry(String id, Retry retry) {
    return api.put(
        "/workspace/{workspace}/workflowrun/{id}/retry",
        Map.of("workspace", workspace, "id", id),
        retry,
        RUN);
  }

  /** {@code GET /workflowrun/query}: the runs matching the query, one page at a time. */
  public Page<WorkflowRun> query(RunQuery query) {
    Page<WorkflowRun> page =
        api.get(
            "/workspace/{workspace}/workflowrun/query",
            Map.of("workspace", workspace),
            query.params(),
            RUN_PAGE);
    return page.following(number -> query(query.withPage(number)));
  }

  /**
   * {@code GET /workflowrun/count}: how many runs match, by status. The route narrows by labels,
   * workflows and dates only; the query's other filters and its paging are not sent.
   */
  public WorkflowRunCount count(RunQuery query) {
    return api.get(
        "/workspace/{workspace}/workflowrun/count",
        Map.of("workspace", workspace),
        query.countParams(),
        COUNT);
  }
}

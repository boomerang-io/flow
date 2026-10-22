package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.TaskRun;
import java.util.Map;
import tools.jackson.databind.JavaType;

/**
 * The task runs of one workspace, queried across workflow runs ({@code contracts/api-v2.yaml},
 * {@code /api/v2/workspace/{workspace}/taskrun/query}).
 */
public final class TaskRuns {

  static final JavaType TASK_RUN_PAGE =
      WireFormat.JSON.getTypeFactory().constructParametricType(Page.class, TaskRun.class);

  private final ApiClient api;
  private final String workspace;

  TaskRuns(ApiClient api, String workspace) {
    this.api = api;
    this.workspace = workspace;
  }

  /**
   * {@code GET /taskrun/query}: the task runs matching the query, one page at a time. The route
   * requires labels or workflow runs. A row leaves out params, results, spec, annotations and the
   * status message; read the workflow run for those.
   */
  public Page<TaskRun> query(TaskRunQuery query) {
    Page<TaskRun> page =
        api.get(
            "/workspace/{workspace}/taskrun/query",
            Map.of("workspace", workspace),
            query.params(),
            TASK_RUN_PAGE);
    return page.following(number -> query(query.withPage(number)));
  }
}

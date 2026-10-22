package io.boomerang.flow.sdk;

import tools.jackson.databind.JavaType;
import io.boomerang.flow.sdk.model.Action;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The manual and approval actions of one workspace ({@code contracts/api-v2.yaml}, {@code
 * /api/v2/workspace/{workspace}/action}): what a run waits on until someone decides.
 */
public final class Actions {

  static final JavaType ACTION = WireFormat.JSON.constructType(Action.class);
  static final JavaType ACTION_PAGE =
      WireFormat.JSON.getTypeFactory().constructParametricType(Page.class, Action.class);
  private static final JavaType NOTHING = WireFormat.JSON.constructType(Void.class);

  private final ApiClient api;
  private final String workspace;

  Actions(ApiClient api, String workspace) {
    this.api = api;
    this.workspace = workspace;
  }

  /** {@code GET /action/{id}}: the action with its decisions so far. */
  public Action get(String id) {
    return api.get(
        "/workspace/{workspace}/action/{id}",
        Map.of("workspace", workspace, "id", id),
        Params.none(),
        ACTION);
  }

  /** {@code GET /action/query}: the actions matching the query, one page at a time. */
  public Page<Action> query(ActionQuery query) {
    Page<Action> page =
        api.get(
            "/workspace/{workspace}/action/query",
            Map.of("workspace", workspace),
            query.params(),
            ACTION_PAGE);
    return page.following(number -> query(query.withPage(number)));
  }

  /**
   * {@code PUT /action}: record each decision as the token's principal. An approval completes
   * once it holds the approvals it needs, or fails on a rejection; a manual task completes on
   * approve and fails on reject. The server answers nothing.
   */
  public void decide(ActionDecision... decisions) {
    decide(Arrays.asList(decisions));
  }

  public void decide(List<ActionDecision> decisions) {
    api.put("/workspace/{workspace}/action", Map.of("workspace", workspace), decisions, NOTHING);
  }
}

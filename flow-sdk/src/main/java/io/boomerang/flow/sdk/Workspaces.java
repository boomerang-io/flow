package io.boomerang.flow.sdk;

import io.boomerang.flow.sdk.model.Workspace;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JavaType;

/**
 * Workspaces, one method per route ({@code contracts/api-v2.yaml}, {@code /api/v2/workspace}).
 * An embedding product that mirrors its own records as workspaces keys them by {@code
 * externalRef}: {@link #ensure} makes the mirror once, however often it is retried.
 */
public final class Workspaces {

  static final JavaType WORKSPACE = WireFormat.JSON.constructType(Workspace.class);
  static final JavaType WORKSPACE_PAGE =
      WireFormat.JSON.getTypeFactory().constructParametricType(Page.class, Workspace.class);
  private static final JavaType NOTHING = WireFormat.JSON.constructType(Void.class);

  /** The reason a create answers when a workspace already carries the request's external reference. */
  public static final String EXTERNAL_REF_TAKEN = "WORKSPACE_EXTERNAL_REF_TAKEN";

  private final ApiClient api;

  Workspaces(ApiClient api) {
    this.api = api;
  }

  /**
   * {@code POST /workspace}: create the workspace, the caller as its owner. A name already taken is
   * {@code WORKSPACE_NON_UNIQUE_NAME}; an {@code externalRef} another workspace carries is a
   * {@link FlowApiException} with reason {@link #EXTERNAL_REF_TAKEN}, whose message names it.
   */
  public Workspace create(WorkspaceRequest request) {
    return api.post("/workspace", Map.of(), Params.none(), request, WORKSPACE);
  }

  /**
   * The workspace carrying the request's {@code externalRef}, created when there is none: a create
   * retried after a crash, or for a record that already has its mirror, reads the one that holds it.
   */
  public Workspace ensure(WorkspaceRequest request) {
    if (request.getExternalRef() == null) {
      throw new IllegalArgumentException("ensure needs an externalRef to key the workspace by");
    }
    try {
      return create(request);
    } catch (FlowApiException taken) {
      if (!EXTERNAL_REF_TAKEN.equals(taken.reason())) {
        throw taken;
      }
      return byExternalRef(request.getExternalRef())
          .orElseThrow(
              () -> new IllegalStateException("The workspace carrying the reference vanished"));
    }
  }

  /** {@code GET /workspace/{name}}: the workspace by name. */
  public Workspace get(String name) {
    return api.get("/workspace/{name}", Map.of("name", name), Params.none(), WORKSPACE);
  }

  /** {@code GET /workspace/query?externalRef=}: the one workspace carrying the reference. */
  public Optional<Workspace> byExternalRef(String externalRef) {
    return query(WorkspaceQuery.all().externalRef(externalRef).page(0, 1)).content().stream()
        .findFirst();
  }

  /** {@code GET /workspace/query}: the workspaces matching the query, one page at a time. */
  public Page<Workspace> query(WorkspaceQuery query) {
    Page<Workspace> page = api.get("/workspace/query", Map.of(), query.params(), WORKSPACE_PAGE);
    return page.following(number -> query(query.withPage(number)));
  }

  /**
   * {@code DELETE /workspace/{name}}: remove the workspace. Its workflows are marked deleted at
   * once and its tokens, tasks and membership go with it; the engine's sweeps then cancel the
   * runs in flight and remove every run, task run, artifact and log, a page per sweep.
   */
  public void delete(String name) {
    api.delete("/workspace/{name}", Map.of("name", name), NOTHING);
  }
}

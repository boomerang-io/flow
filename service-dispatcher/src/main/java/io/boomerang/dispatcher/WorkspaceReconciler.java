package io.boomerang.dispatcher;

import io.boomerang.dispatcher.sdk.DispatcherClient;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseQuery;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseResponse;
import io.boomerang.kube.KubeService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases workspace storage by reconciling the cluster against the engine: the cluster says which
 * workspace claims this dispatcher's namespace holds, and the engine says which of their runs are
 * finished. Nothing on the run records the release, so an interrupted tick costs only a delay - the
 * next tick asks the same question again.
 */
@Component
@ConditionalOnProperty(
    name = "flow.dispatcher.workspace.reconcile.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WorkspaceReconciler {

  private static final Logger LOGGER = LogManager.getLogger(WorkspaceReconciler.class);

  private static final String WORKSPACE_REF_LABEL = "boomerang.io/workspace-ref";

  // The engine caps a release query at this many ids, so a tick pages.
  private static final int PAGE_SIZE = 500;

  private final KubeService kubeService;

  private final WorkspaceService workspaceService;

  private final DispatcherClient dispatcherClient;

  public WorkspaceReconciler(
      KubeService kubeService,
      WorkspaceService workspaceService,
      DispatcherClient dispatcherClient) {
    this.kubeService = kubeService;
    this.workspaceService = workspaceService;
    this.dispatcherClient = dispatcherClient;
  }

  // Kubernetes ownerReferences on the claim would add a second, cluster-side line of defence once
  // a run is itself a cluster object; the reconciliation above stands on its own without them.
  @Scheduled(fixedDelayString = "${flow.dispatcher.workspace.reconcile-ms:60000}")
  public void reconcile() {
    try {
      List<String> held = held();
      int released = 0;
      for (int page = 0; page < pageCount(held); page++) {
        WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
        query.setWorkflowRunRefs(page(held, page));
        released += release(releasable(query).getWorkflowRunRefs());
      }
      LOGGER.info("Workspace reconcile: held={}, released={}", held.size(), released);
    } catch (Exception e) {
      // A tick is best effort - the claims stay held and the next tick asks again.
      LOGGER.warn("Workspace reconcile failed: {}", e.getMessage());
    }
  }

  /**
   * Ask the engine which runs in one page are finished. A failure is not fatal: that page's volumes
   * stay held and the next tick asks again.
   */
  private WorkspaceReleaseResponse releasable(WorkspaceReleaseQuery query) {
    try {
      return dispatcherClient.releasable(query);
    } catch (Exception e) {
      LOGGER.warn("Error retrieving releasable workspaces: {}", e.getMessage());
      return new WorkspaceReleaseResponse();
    }
  }

  /** The run refs this dispatcher's namespace holds a workspace claim for. */
  private List<String> held() {
    Set<String> refs = new LinkedHashSet<>();
    for (PersistentVolumeClaim claim : kubeService.listWorkspacePVCs()) {
      Map<String, String> labels =
          (claim.getMetadata() != null && claim.getMetadata().getLabels() != null)
              ? claim.getMetadata().getLabels()
              : Map.of();
      String ref = labels.get(WORKSPACE_REF_LABEL);
      if (ref != null) {
        refs.add(ref);
      }
    }
    return new ArrayList<>(refs);
  }

  private int release(List<String> refs) {
    int released = 0;
    for (String ref : Objects.requireNonNullElse(refs, List.<String>of())) {
      try {
        // Deleting by label is idempotent: an absent claim is already released.
        workspaceService.delete(ref);
        released++;
      } catch (Exception e) {
        // One claim the API server will not delete must not cost the rest of the tick.
        LOGGER.error("Unable to release workspace ({}): {}", ref, e.getMessage());
      }
    }
    return released;
  }

  private static int pageCount(List<String> refs) {
    return (refs.size() + PAGE_SIZE - 1) / PAGE_SIZE;
  }

  private static List<String> page(List<String> refs, int page) {
    int from = page * PAGE_SIZE;
    return (from < refs.size())
        ? new ArrayList<>(refs.subList(from, Math.min(from + PAGE_SIZE, refs.size())))
        : new ArrayList<>();
  }
}

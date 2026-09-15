package io.boomerang.dispatcher;

import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.StorageType;
import io.boomerang.common.model.WorkspaceReleaseQuery;
import io.boomerang.common.model.WorkspaceReleaseResponse;
import io.boomerang.kube.KubeService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * workspace claims this dispatcher's namespace holds, and the engine says which of their owners are
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

  private static final String WORKSPACE_TYPE_LABEL = "boomerang.io/workspace-type";

  // The engine caps each list of a release query at this many ids, so a tick pages.
  private static final int PAGE_SIZE = 500;

  private final KubeService kubeService;

  private final WorkspaceService workspaceService;

  private final EngineClient engineClient;

  public WorkspaceReconciler(
      KubeService kubeService, WorkspaceService workspaceService, EngineClient engineClient) {
    this.kubeService = kubeService;
    this.workspaceService = workspaceService;
    this.engineClient = engineClient;
  }

  // Kubernetes ownerReferences on the claim would add a second, cluster-side line of defence once
  // a run is itself a cluster object; the reconciliation above stands on its own without them.
  @Scheduled(fixedDelayString = "${flow.dispatcher.workspace.reconcile-ms:60000}")
  public void reconcile() {
    try {
      Map<StorageType, List<String>> held = held();
      List<String> workflowRunRefs = held.getOrDefault(StorageType.workflowRun, List.of());
      List<String> workflowRefs = held.getOrDefault(StorageType.workflow, List.of());
      int released = 0;
      int pages = Math.max(pageCount(workflowRunRefs), pageCount(workflowRefs));
      for (int page = 0; page < pages; page++) {
        WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
        query.setWorkflowRunRefs(page(workflowRunRefs, page));
        query.setWorkflowRefs(page(workflowRefs, page));
        WorkspaceReleaseResponse response = engineClient.releasableWorkspaces(query);
        released += release(StorageType.workflowRun, response.getWorkflowRunRefs());
        released += release(StorageType.workflow, response.getWorkflowRefs());
      }
      LOGGER.info(
          "Workspace reconcile: held={}, released={}",
          workflowRunRefs.size() + workflowRefs.size(),
          released);
    } catch (Exception e) {
      // A tick is best effort - the claims stay held and the next tick asks again.
      LOGGER.warn("Workspace reconcile failed: {}", e.getMessage());
    }
  }

  /** The workspace refs this dispatcher's namespace holds a claim for, grouped by storage type. */
  private Map<StorageType, List<String>> held() {
    Map<StorageType, Set<String>> refs = new EnumMap<>(StorageType.class);
    for (PersistentVolumeClaim claim : kubeService.listWorkspacePVCs()) {
      Map<String, String> labels =
          (claim.getMetadata() != null && claim.getMetadata().getLabels() != null)
              ? claim.getMetadata().getLabels()
              : Map.of();
      String ref = labels.get(WORKSPACE_REF_LABEL);
      StorageType type = StorageType.fromLabel(labels.get(WORKSPACE_TYPE_LABEL)).orElse(null);
      if (ref != null && type != null) {
        refs.computeIfAbsent(type, key -> new LinkedHashSet<>()).add(ref);
      }
    }
    Map<StorageType, List<String>> held = new EnumMap<>(StorageType.class);
    refs.forEach((type, values) -> held.put(type, new ArrayList<>(values)));
    return held;
  }

  private int release(StorageType type, List<String> refs) {
    int released = 0;
    for (String ref : Objects.requireNonNullElse(refs, List.<String>of())) {
      try {
        // Deleting by label is idempotent: an absent claim is already released.
        workspaceService.delete(type.getLabel(), ref);
        released++;
      } catch (Exception e) {
        // One claim the API server will not delete must not cost the rest of the tick.
        LOGGER.error(
            "Unable to release {} workspace ({}): {}", type.getLabel(), ref, e.getMessage());
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

package io.boomerang.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import io.boomerang.dispatcher.WorkspaceStore;
import io.boomerang.dispatcher.model.HeldWorkspace;
import io.boomerang.kube.KubeHelperService;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Backs workspaces with named Docker volumes on the one host the dispatcher drives. The volumes
 * carry exactly the labels the Kubernetes claims carry, so {@code WorkspaceReconciler} releases
 * them through the same code path. A local Docker volume has no size, storage class or access mode:
 * those parts of the authored spec are recorded on the run and ignored here.
 */
@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "docker")
public class DockerWorkspaceStore implements WorkspaceStore {

  private static final Logger LOGGER = LogManager.getLogger(DockerWorkspaceStore.class);

  private static final String WORKSPACE_REF_LABEL = "boomerang.io/workspace-ref";

  private static final String WORKSPACE_TYPE_LABEL = "boomerang.io/workspace-type";

  private final DockerClient client;

  private final KubeHelperService helperKubeService;

  public DockerWorkspaceStore(DockerClient client, KubeHelperService helperKubeService) {
    this.client = client;
    this.helperKubeService = helperKubeService;
  }

  @Override
  public void create(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds) {
    Map<String, String> labels =
        helperKubeService.getWorkspaceLabels(workflowRef, workspaceRef, workspaceType, customLabels);
    if (!volumes(labels).isEmpty()) {
      LOGGER.debug("Volume for workspace ({}/{}) already exists.", workspaceType, workspaceRef);
      return;
    }
    String name = volumeName(workspaceRef, workspaceType);
    LOGGER.info("Creating Docker volume {} (size={} is not enforced on a local volume).", name, size);
    client.createVolumeCmd().withName(name).withLabels(labels).exec();
  }

  @Override
  public boolean exists(String workspaceRef, String workspaceType) {
    return workspaceRef != null
        && workspaceType != null
        && !volumes(helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null))
            .isEmpty();
  }

  @Override
  public void delete(String workspaceRef, String workspaceType) {
    for (InspectVolumeResponse volume :
        volumes(helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null))) {
      client.removeVolumeCmd(volume.getName()).exec();
    }
  }

  @Override
  public List<HeldWorkspace> held() {
    return volumes(helperKubeService.getBaseLabels("workspace")).stream()
        .map(volume -> (volume.getLabels() != null) ? volume.getLabels() : Map.<String, String>of())
        .map(labels -> new HeldWorkspace(labels.get(WORKSPACE_REF_LABEL), labels.get(WORKSPACE_TYPE_LABEL)))
        .toList();
  }

  /**
   * The name of the volume backing a workspace, for mounting it into a task. Looked up by label so
   * a volume created by an earlier release, or by hand, is still found.
   */
  public String volumeNameFor(String workspaceRef, String workspaceType) {
    List<InspectVolumeResponse> volumes =
        volumes(helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null));
    if (volumes.isEmpty()) {
      throw new DockerException(
          "No Docker volume found for workspace (" + workspaceType + "/" + workspaceRef + ")", 404);
    }
    return volumes.get(0).getName();
  }

  private String volumeName(String workspaceRef, String workspaceType) {
    return helperKubeService.getPrefixVol() + "-ws-" + workspaceType + "-" + workspaceRef;
  }

  private List<InspectVolumeResponse> volumes(Map<String, String> labels) {
    try {
      List<InspectVolumeResponse> volumes =
          client
              .listVolumesCmd()
              .withFilter(
                  "label",
                  labels.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList())
              .exec()
              .getVolumes();
      return (volumes != null) ? volumes : List.of();
    } catch (DockerException e) {
      // Best effort, like the Kubernetes store: the volumes stay held and the next tick re-lists.
      LOGGER.warn("Unable to list Docker workspace volumes: {}", e.getMessage());
      return List.of();
    }
  }
}

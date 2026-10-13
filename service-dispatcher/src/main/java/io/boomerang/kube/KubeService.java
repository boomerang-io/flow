package io.boomerang.kube;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;

public interface KubeService {

  PersistentVolumeClaim createWorkspacePVC(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws KubernetesClientException, InterruptedException;

  void deleteWorkspacePVC(String workspaceRef, String workspaceType);

  List<PersistentVolumeClaim> listWorkspacePVCs();

  boolean checkWorkspacePVCExists(
      String workspaceRef, String workspaceType, boolean failIfNotBound);
}

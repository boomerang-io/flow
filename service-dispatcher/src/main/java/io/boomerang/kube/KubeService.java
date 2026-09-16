package io.boomerang.kube;

import io.boomerang.dispatcher.WorkspaceStore;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;

/** The Kubernetes-backed workspace store, plus the two reads that return the claims themselves. */
public interface KubeService extends WorkspaceStore {

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

  List<PersistentVolumeClaim> listWorkspacePVCs();
}

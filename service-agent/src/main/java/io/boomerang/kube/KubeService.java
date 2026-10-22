package io.boomerang.kube;

import io.boomerang.common.model.RunParam;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;

public interface KubeService {

  void deleteWorkflowConfigMap(String workflowId, String workflowActivityId);

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

  boolean checkWorkspacePVCExists(
      String workspaceRef, String workspaceType, boolean failIfNotBound);

  void deleteTaskConfigMap(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels);

  ConfigMap createTaskConfigMap(
      String workflowId,
      String workflowActivityId,
      String taskName,
      String taskActivityId,
      Map<String, String> customLabels,
      List<RunParam> list);
}

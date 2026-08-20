package io.boomerang.kube;

import io.boomerang.common.model.RunParam;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
// @Configurable
public class KubeServiceImpl implements KubeService {

  private static final Logger LOGGER = LogManager.getLogger(KubeServiceImpl.class);

  @Autowired protected KubeHelperService helperKubeService;

  protected static final Integer ONE_DAY_IN_SECONDS = 86400; // 60*60*24

  @Value("${kube.image.pullPolicy}")
  protected String kubeImagePullPolicy;

  @Value("${kube.image.pullSecret}")
  protected String kubeImagePullSecret;

  @Value("${kube.task.backOffLimit}")
  protected Integer kubeJobBackOffLimit;

  @Value("${kube.task.restartPolicy}")
  protected String kubeJobRestartPolicy;

  @Value("${kube.task.ttlDays}")
  protected Integer kubeJobTTLDays;

  @Value("${agent.tasks.serviceaccount}")
  protected String kubeJobServiceAccount;

  @Value("${kube.resource.limit.ephemeral-storage}")
  private String kubeResourceLimitEphemeralStorage;

  @Value("${kube.resource.request.ephemeral-storage}")
  private String kubeResourceRequestEphemeralStorage;

  @Value("${kube.resource.limit.memory}")
  private String kubeResourceLimitMemory;

  @Value("${kube.resource.request.memory}")
  private String kubeResourceRequestMemory;

  @Value("${kube.task.storage.data.memory}")
  private Boolean kubeWorkerStorageDataMemory;

  @Value("${agent.tasks.hostaliases}")
  protected String kubeHostAliases;

  protected KubernetesClient client = null;

  public KubeServiceImpl() {
    this.client = new KubernetesClientBuilder().build();
  }

  //  Using setter instead of Constructor due to autowiring issues
  public void setClient(KubernetesClient client) {
    LOGGER.info("Creating Client with default namespace: " + client.getNamespace());
    this.client = client;
  }

  @Override
  public boolean checkWorkspacePVCExists(
      String workspaceRef, String workspaceType, boolean failIfNotBound) {
    return workspaceRef != null && workspaceType != null
        ? checkPVCExists(
            helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null),
            failIfNotBound)
        : false;
  }

  private boolean checkPVCExists(Map<String, String> labelSelector, boolean failIfNotBound) {
    boolean pvcExists = false;
    try {
      PersistentVolumeClaimList pvcList =
          client.persistentVolumeClaims().withLabels(labelSelector).list();

      LOGGER.info("PVC List: " + pvcList);

      pvcExists = isPVCAvailable(failIfNotBound, pvcList);

      LOGGER.info("Is PVC Available: " + pvcExists);
    } catch (KubernetesClientException e) {
      LOGGER.error("No PVC found matching selector: " + labelSelector, e);
      return false;
    }
    return pvcExists;
  }

  protected String getPVCName(Map<String, String> labels) {
    PersistentVolumeClaimList pvcList = client.persistentVolumeClaims().withLabels(labels).list();

    LOGGER.debug("PVC List: " + pvcList);

    List<PersistentVolumeClaim> pvcs = pvcList.getItems();
    if (pvcs.isEmpty()) {
      throw new KubeRuntimeException(
          "No PersistentVolumeClaim found matching label selector: " + labels);
    }
    if (pvcs.size() > 1) {
      LOGGER.warn(
          "Found "
              + pvcs.size()
              + " PersistentVolumeClaims matching label selector "
              + labels
              + ", using the first: "
              + pvcs.stream()
                  .map(
                      pvc ->
                          pvc.getMetadata().getName()
                              + "("
                              + pvc.getMetadata().getCreationTimestamp()
                              + ")")
                  .collect(Collectors.joining(", ")));
    }
    String name = pvcs.get(0).getMetadata().getName();
    if (name == null) {
      throw new KubeRuntimeException(
          "PersistentVolumeClaim matching label selector " + labels + " has no name.");
    }
    LOGGER.debug(" Chosen PVC Name: " + name);
    return name;
  }

  @Override
  public PersistentVolumeClaim createWorkspacePVC(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws KubernetesClientException, InterruptedException {
    return createPVC(
        helperKubeService.getWorkspaceAnnotations(workflowRef, workspaceRef, workspaceType),
        helperKubeService.getWorkspaceLabels(
            workflowRef, workspaceRef, workspaceType, customLabels),
        size,
        className,
        accessMode,
        waitSeconds);
  }

  private PersistentVolumeClaim createPVC(
      Map<String, String> annotations,
      Map<String, String> labels,
      String size,
      String className,
      String accessMode,
      long waitSeconds)
      throws KubernetesClientException, InterruptedException {

    LOGGER.debug("Creating PersistentVolumeClaim object");

    PersistentVolumeClaim persistentVolumeClaim =
        new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withGenerateName(helperKubeService.getPrefixPVC() + "-")
            .withLabels(labels)
            .withAnnotations(annotations)
            .endMetadata()
            .withNewSpec()
            .withStorageClassName(className)
            .withAccessModes(accessMode)
            .withNewResources()
            .addToRequests("storage", new Quantity(size))
            .endResources()
            .endSpec()
            .build();

    PersistentVolumeClaim result =
        client.persistentVolumeClaims().resource(persistentVolumeClaim).create();

    client
        .resource(result)
        .waitUntilCondition(
            r ->
                "Bound".equals(r.getStatus().getPhase())
                    || "Pending".equals(r.getStatus().getPhase()),
            waitSeconds,
            TimeUnit.SECONDS);

    LOGGER.info(result);
    return result;
  }

  private boolean isPVCAvailable(
      boolean failIfNotBound, PersistentVolumeClaimList persistentVolumeClaimList) {
    if (!persistentVolumeClaimList.getItems().isEmpty()) {
      persistentVolumeClaimList
          .getItems()
          .forEach(
              pvc ->
                  LOGGER.info(
                      "PVC: "
                          + pvc.getMetadata().getName()
                          + " ("
                          + pvc.getStatus().getPhase()
                          + ")"));
      if (failIfNotBound) {
        if (persistentVolumeClaimList.getItems().stream()
                .filter(pvc -> "Bound".equalsIgnoreCase(pvc.getStatus().getPhase()))
                .count()
            > 0) {
          // TODO update to check if they are terminating (even though they are still bound)
          return true;
        }
      } else {
        return true;
      }
    }

    return false;
  }

  @Override
  public void deleteWorkspacePVC(String workspaceRef, String workspaceType) {
    deletePVC(helperKubeService.getWorkspaceLabels(null, workspaceRef, workspaceType, null));
  }

  private void deletePVC(Map<String, String> labels) {

    LOGGER.debug("Deleting PersistentVolumeClaim...");

    LOGGER.debug(client.persistentVolumeClaims().list().toString());

    client.persistentVolumeClaims().withLabels(labels).delete();
  }

  //  public ConfigMap createWorkflowConfigMap(String workflowName, String workflowId,
  //      String workflowActivityId, Map<String, String> customLabels, Map<String, String>
  // inputProps) {
  //
  //  Map<String, String> dataMap = new HashMap<>();
  //  Map<String, String> sysProps = new HashMap<>();
  //  sysProps.put("workflow-name", workflowName);
  //  sysProps.put("workflow-id", workflowId);
  //  sysProps.put("workflow-activity-id", workflowActivityId);
  //  dataMap.put("workflow.input.properties",
  //      helperKubeService.createConfigMapProp(inputProps));
  //  dataMap.put("workflow.system.properties",
  //      helperKubeService.createConfigMapProp(sysProps));
  //
  ////  Watch cmWatcher = watchWorkflowConfigMap(client,
  // helperKubeService.getWorkflowLabels(workflowId, workflowActivityId, customLabels));
  //
  ////  try (Watch ignored = watchWorkflowConfigMap(client,
  ////      helperKubeService.getWorkflowLabels(workflowId, workflowActivityId, customLabels));) {
  //    ConfigMap configMap = new ConfigMapBuilder().withNewMetadata()
  //        .withGenerateName(helperKubeService.getPrefixCM() + "-")
  //        .withLabels(
  //            helperKubeService.getWorkflowLabels(workflowId, workflowActivityId, customLabels))
  //        .withAnnotations(helperKubeService.getAnnotations("workflow", workflowName, workflowId,
  //            workflowActivityId, null, null))
  //        .endMetadata().addToData(dataMap).build();
  //    ConfigMap result = client.configMaps().create(configMap);
  ////  }
  //
  //    return result;
  //  }

  //  private static Watch watchWorkflowConfigMap(KubernetesClient client, Map<String, String>
  // labels) {
  //
  //    return client.configMaps().withLabels(labels).watch(new Watcher<ConfigMap>() {
  //      @Override
  //      public void eventReceived(Action action, ConfigMap resource) {
  //        LOGGER.info("Watch event received {}: {}", action.name(),
  // resource.getMetadata().getName());
  //        switch (action.name()) {
  //          case "DELETED":
  //            LOGGER.info(resource.getMetadata().getName() + "got deleted");
  //              break;
  //        }
  //      }
  //
  //      @Override
  //      public void onClose(WatcherException e) {
  //        LOGGER.error("Watch error received: {}", e.getMessage(), e);
  //        //Cause the pod to restart
  //        System.exit(1);
  //      }
  //
  //      @Override
  //      public void onClose() {
  //        LOGGER.info("Watch gracefully closed");
  //      }
  //    });
  //  }

  @Override
  public ConfigMap createTaskConfigMap(
      String workflowId,
      String workflowRunId,
      String taskName,
      String taskRunId,
      Map<String, String> customLabels,
      List<RunParam> params) {

    //  Map<String, String> dataMap = new HashMap<>();
    //  Map<String, String> sysProps = new HashMap<>();
    //  sysProps.put("taskrun-name", taskName);
    //  sysProps.put("taskrun-ref", taskRunId);
    //  sysProps.put("workflow-ref", workflowId);
    //  sysProps.put("workflowrun-ref", workflowRunId);
    //  dataMap.put("task.input.properties", helperKubeService.createConfigMapProp(params));
    //  dataMap.put("task.system.properties", helperKubeService.createConfigMapProp(sysProps));

    ConfigMap configMap =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withGenerateName(helperKubeService.getPrefixCM() + "-")
            .withLabels(
                helperKubeService.getTaskLabels(workflowId, workflowRunId, taskRunId, customLabels))
            .withAnnotations(
                helperKubeService.getAnnotations("task", workflowId, workflowRunId, taskRunId))
            .endMetadata()
            .addToData(helperKubeService.createConfigMapData(params))
            .build();

    LOGGER.debug("ConfigMap: " + configMap.toString());

    ConfigMap result = client.configMaps().resource(configMap).create();

    LOGGER.info(result.toString());

    return result;
  }

  // protected V1ConfigMap createTaskConfigMapBodyFromEnv(String workflowName, String workflowId,
  // String workflowActivityId, String taskName, String taskId, Map<String, String> parameters) {
  // V1ConfigMap body = new V1ConfigMap();
  //
  // body.metadata(
  // helperKubeService.getMetadata(workflowName, workflowId, workflowActivityId, taskId,
  // helperKubeService.getPrefixCFGMAP()));
  //
  //// Create Data
  // Map<String, String> envParameters = new HashMap<>();
  // envParameters.put("SYSTEM_ACTIVITY_ID", workflowActivityId);
  // envParameters.put("SYSTEM_WORKFLOW_NAME", workflowName);
  // envParameters.put("SYSTEM_WORKFLOW_ID", workflowId);
  // envParameters.put("SYSTEM_CONTROLLER_URL", bmrgControllerServiceURL);
  //
  // for (Map.Entry<String, String> entry : parameters.entrySet()) {
  // envParameters.put(entry.getKey().replace("-", "").replace(" ", "").replace(".",
  // "_").toUpperCase(), entry.getValue());
  // }
  //
  // body.data(envParameters);
  // return body;
  // }

  @Override
  public void deleteWorkflowConfigMap(String workflowId, String workflowActivityId) {
    deleteConfigMap(helperKubeService.getWorkflowLabels(workflowId, workflowActivityId, null));
  }

  @Override
  public void deleteTaskConfigMap(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {
    deleteConfigMap(
        helperKubeService.getTaskLabels(
            workflowId, workflowActivityId, taskActivityId, customLabels));
  }

  private void deleteConfigMap(Map<String, String> labels) {

    LOGGER.debug("Deleting ConfigMap...");

    LOGGER.debug(client.configMaps().list().toString());

    client.configMaps().withLabels(labels).delete();
  }

  protected String getConfigMapName(Map<String, String> labels) {
    ConfigMapList configMapList = client.configMaps().withLabels(labels).list();

    LOGGER.debug("ConfigMap List: " + configMapList);

    List<ConfigMap> configMaps = configMapList.getItems();
    if (configMaps.isEmpty()) {
      throw new KubeRuntimeException(
          "No ConfigMap found matching label selector: " + labels);
    }
    if (configMaps.size() > 1) {
      LOGGER.warn(
          "Found "
              + configMaps.size()
              + " ConfigMaps matching label selector "
              + labels
              + ", using the first: "
              + configMaps.stream()
                  .map(
                      cm ->
                          cm.getMetadata().getName()
                              + "("
                              + cm.getMetadata().getCreationTimestamp()
                              + ")")
                  .collect(Collectors.joining(", ")));
    }
    String name = configMaps.get(0).getMetadata().getName();
    if (name == null) {
      throw new KubeRuntimeException("ConfigMap matching label selector " + labels + " has no name.");
    }
    LOGGER.debug(" Chosen ConfigMap Name: " + name);
    return name;
  }

  protected Boolean isTaskRunResultTooLarge(Map<String, String> labels) {
    try {
      List<Pod> pods = client.pods().withLabels(labels).list().getItems();

      if (pods != null && !pods.isEmpty()) {
        Pod pod = pods.get(0);
        //        LogWatch watch =
        // client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName()).tailingLines(10).watchLog(out);
        String lastLine =
            client
                .pods()
                .inNamespace(pod.getMetadata().getNamespace())
                .withName(pod.getMetadata().getName())
                .tailingLines(1)
                .getLog();
        ;
        if (lastLine.contains("Termination message is above max allowed size 4096")) {
          return Boolean.TRUE;
        }
      }
    } catch (Exception e) {
      return Boolean.FALSE;
    }
    return Boolean.FALSE;
  }
}

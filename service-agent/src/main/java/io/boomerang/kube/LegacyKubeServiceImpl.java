package io.boomerang.kube;
// package net.boomerangplatform.kube.service;
//
// import java.io.ByteArrayOutputStream;
// import java.io.IOException;
// import java.io.InputStream;
// import java.lang.reflect.Type;
// import java.net.SocketTimeoutException;
// import java.nio.charset.StandardCharsets;
// import java.util.ArrayList;
// import java.util.Arrays;
// import java.util.HashMap;
// import java.util.List;
// import java.util.Map;
// import java.util.Optional;
// import java.util.concurrent.TimeUnit;
// import javax.servlet.http.HttpServletResponse;
// import org.apache.commons.lang3.ArrayUtils;
// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;
// import org.joda.time.DateTime;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.http.HttpStatus;
// import org.springframework.stereotype.Component;
// import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
// import com.fasterxml.jackson.databind.JsonNode;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.google.common.io.ByteStreams;
// import com.google.gson.Gson;
// import com.google.gson.JsonElement;
// import com.google.gson.JsonObject;
// import com.google.gson.JsonSyntaxException;
// import com.google.gson.reflect.TypeToken;
// import io.kubernetes.client.ApiClient;
// import io.kubernetes.client.ApiException;
// import io.kubernetes.client.Configuration;
// import io.kubernetes.client.Exec;
// import io.kubernetes.client.PodLogs;
// import io.kubernetes.client.apis.BatchV1Api;
// import io.kubernetes.client.apis.CoreV1Api;
// import io.kubernetes.client.custom.Quantity;
// import io.kubernetes.client.models.V1ConfigMap;
// import io.kubernetes.client.models.V1ConfigMapList;
// import io.kubernetes.client.models.V1ConfigMapProjection;
// import io.kubernetes.client.models.V1Container;
// import io.kubernetes.client.models.V1ContainerStatus;
// import io.kubernetes.client.models.V1DeleteOptions;
// import io.kubernetes.client.models.V1EmptyDirVolumeSource;
// import io.kubernetes.client.models.V1EnvVar;
// import io.kubernetes.client.models.V1HostAlias;
// import io.kubernetes.client.models.V1Job;
// import io.kubernetes.client.models.V1JobList;
// import io.kubernetes.client.models.V1JobSpec;
// import io.kubernetes.client.models.V1JobStatus;
// import io.kubernetes.client.models.V1LocalObjectReference;
// import io.kubernetes.client.models.V1ObjectMeta;
// import io.kubernetes.client.models.V1PersistentVolumeClaim;
// import io.kubernetes.client.models.V1PersistentVolumeClaimList;
// import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;
// import io.kubernetes.client.models.V1PersistentVolumeClaimStatus;
// import io.kubernetes.client.models.V1PersistentVolumeClaimVolumeSource;
// import io.kubernetes.client.models.V1Pod;
// import io.kubernetes.client.models.V1PodCondition;
// import io.kubernetes.client.models.V1PodSpec;
// import io.kubernetes.client.models.V1PodTemplateSpec;
// import io.kubernetes.client.models.V1ProjectedVolumeSource;
// import io.kubernetes.client.models.V1ResourceRequirements;
// import io.kubernetes.client.models.V1SecurityContext;
// import io.kubernetes.client.models.V1Status;
// import io.kubernetes.client.models.V1Volume;
// import io.kubernetes.client.models.V1VolumeMount;
// import io.kubernetes.client.models.V1VolumeProjection;
// import io.kubernetes.client.util.Watch;
// import net.boomerangplatform.error.BoomerangException;
// import net.boomerangplatform.kube.exception.KubeRuntimeException;
// import net.boomerangplatform.model.TaskConfiguration;
// import net.boomerangplatform.model.TaskDeletionEnum;
//
// @Component
// public class KubeServiceImpl implements KubeService {
//
//  private static final Logger LOGGER = LogManager.getLogger(KubeService.class);
//
////  protected static final String TIER = "worker";
//
//  private static final int TIMEOUT = 600;
//
//  private static final int BYTE_SIZE = 1024;
//
//  protected static final int TIMEOUT_ONE_MINUTE = 60;
//
//  private static final String EXCEPTION = "Exception: ";
//
//  protected static final Integer ONE_DAY_IN_SECONDS = 86400; // 60*60*24
//
//  private static final String[] waitingErrorReasons = new String[]
// {"CrashLoopBackOff","ErrImagePull","ImagePullBackOff","CreateContainerConfigError","InvalidImageName","CreateContainerError"};
//
//  @Value("${kube.api.base.path}")
//  protected String kubeApiBasePath;
//
//  @Value("${kube.api.token}")
//  protected String kubeApiToken;
//
//  @Value("${kube.api.debug}")
//  protected Boolean kubeApiDebug;
//
//  @Value("${kube.api.type}")
//  protected String kubeApiType;
//
//  @Value("${kube.api.pretty}")
//  protected String kubeApiPretty;
//
//  @Value("${kube.api.includeunitialized}")
//  protected Boolean kubeApiIncludeuninitialized;
//
//  @Value("${kube.api.timeout}")
//  private Integer kubeApiTimeOut;
//
//  @Value("${kube.namespace}")
//  protected String kubeNamespace;
//
//  @Value("${kube.image.pullPolicy}")
//  protected String kubeImagePullPolicy;
//
//  @Value("${kube.image.pullSecret}")
//  protected String kubeImagePullSecret;
//
//  @Value("${kube.worker.job.backOffLimit}")
//  protected Integer kubeJobBackOffLimit;
//
//  @Value("${kube.worker.job.restartPolicy}")
//  protected String kubeJobRestartPolicy;
//
//  @Value("${kube.worker.job.ttlDays}")
//  protected Integer kubeJobTTLDays;
//
//  @Value("${kube.worker.hostaliases}")
//  protected String kubeJobHostAliases;
//
//  @Value("${kube.worker.node.dedicated}")
//  protected Boolean kubeJobDedicatedNodes;
//
//  @Value("${kube.worker.serviceaccount}")
//  protected String kubeJobServiceAccount;
//
//  @Value("${proxy.enable}")
//  protected Boolean proxyEnabled;
//
//  @Value("${proxy.host}")
//  protected String proxyHost;
//
//  @Value("${proxy.port}")
//  protected String proxyPort;
//
//  @Value("${proxy.ignore}")
//  protected String proxyIgnore;
//
//  @Value("${controller.service.host}")
//  protected String bmrgControllerServiceURL;
//
//  @Value("${boomerang.product}")
//  private String bmrgProduct;
//
//  @Value("${kube.lifecycle.image}")
//  private String kubeLifecycleImage;
//
//  @Value("${kube.resource.limit.ephemeral-storage}")
//  private String kubeResourceLimitEphemeralStorage;
//
//  @Value("${kube.resource.request.ephemeral-storage}")
//  private String kubeResourceRequestEphemeralStorage;
//
//  @Value("${kube.resource.limit.memory}")
//  private String kubeResourceLimitMemory;
//
//  @Value("${kube.resource.request.memory}")
//  private String kubeResourceRequestMemory;
//
//  @Value("${kube.worker.storage.data.memory}")
//  private Boolean kubeWorkerStorageDataMemory;
//
//  @Override
//  public V1Status deleteJob(TaskDeletionEnum taskDeletion, String workflowId, String
// workflowActivityId,
//      String taskId, String taskActivityId) {
//    V1DeleteOptions deleteOptions = new V1DeleteOptions();
//    deleteOptions.setPropagationPolicy("Background");
//    V1Status result = new V1Status();
//    String jobName = getJobName(TaskDeletionEnum.OnSuccess.equals(taskDeletion) ? true : false,
//        workflowId, workflowActivityId, taskId, taskActivityId);
//    if (!jobName.isEmpty()) {
//      try {
//        LOGGER.info("Deleting Job ( " + jobName + ")...");
//        result = getBatchApi().deleteNamespacedJob(jobName, kubeNamespace, kubeApiPretty,
//            deleteOptions, null, null, null, null);
//      } catch (JsonSyntaxException e) {
//        if (e.getCause() instanceof IllegalStateException) {
//          IllegalStateException ise = (IllegalStateException) e.getCause();
//          if (ise.getMessage() != null
//              && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT")) {
//            LOGGER.error(
//                "Catching exception because of issue
// https://github.com/kubernetes-client/java/issues/86");
//          } else {
//            LOGGER.error("Exception when running deleteJob()", e);
//          }
//        }
//      } catch (ApiException e) {
//        LOGGER.error("Exception when running deleteJob()", e);
//      }
//    }
//    return result;
//  }
//
//  @Override
//  public V1ConfigMap createWorkflowConfigMap(String workflowName, String workflowId,
//      String activityId, Map<String, String> customLabels, Map<String, String> inputProps) {
//    return createConfigMap(
//        createWorkflowConfigMapBody(workflowName, workflowId, activityId, customLabels,
// inputProps));
//  }
//
//  @Override
//  public V1ConfigMap createTaskConfigMap(String workflowName, String workflowId,
//      String workflowActivityId, String taskName, String taskId, String taskActivityId,
// Map<String, String> customLabels, Map<String, String> inputProps) {
//    LOGGER.info("ConfigMapBody: " + inputProps);
//    return createConfigMap(createTaskConfigMapBody(workflowName, workflowId, workflowActivityId,
//        taskName, taskId, taskActivityId, customLabels, inputProps));
//  }
//
//  private V1ConfigMap createConfigMap(V1ConfigMap body) {
//    V1ConfigMap result = new V1ConfigMap();
//    try {
//      result = getCoreApi().createNamespacedConfigMap(kubeNamespace, body,
//          kubeApiIncludeuninitialized, kubeApiPretty, null);
//      LOGGER.info(result);
//    } catch (ApiException e) {
//      LOGGER.error("Exception when calling CoreV1Api#createNamespacedConfigMap", e);
//      throw new KubeRuntimeException("Error create configMap", e);
//    }
//    return result;
//  }
//
//  @Override
//  public V1ConfigMap watchWorkflowConfigMap(String workflowId, String workflowActivityId) {
//    return watchConfigMap(helperKubeService.getLabelSelector("workflow", workflowId,
// workflowActivityId, null, null));
//  }
//
//  @Override
//  public V1ConfigMap watchTaskConfigMap(String workflowId, String workflowActivityId, String
// taskId, String taskActivityId) {
//    return watchConfigMap(helperKubeService.getLabelSelector("task", workflowId,
// workflowActivityId, taskId, taskActivityId));
//  }
//
//  private V1ConfigMap watchConfigMap(String labelSelector) {
//    try {
//      Watch<V1ConfigMap> watch = Watch.createWatch(createWatcherApiClient(),
//          getCoreApi().listNamespacedConfigMapCall(kubeNamespace, kubeApiIncludeuninitialized,
//              kubeApiPretty, null, null, labelSelector, null, null, null, true, null, null),
//          new TypeToken<Watch.Response<V1ConfigMap>>() {}.getType());
//
//      return getConfigMapResult(watch);
//    } catch (IOException | ApiException e) {
//      throw new KubeRuntimeException("Error create configMap watch", e);
//    }
//  }
//
//  @Override
//  public void patchTaskConfigMap(String workflowId, String workflowActivityId, String taskId,
//      String taskName, Map<String, String> properties) {
//    V1ConfigMap wfConfigMap = getConfigMap(helperKubeService.getLabelSelector("workflow",
// workflowId, workflowActivityId, null, null));
//    String fileName = taskName + ".output.properties";
//    patchConfigMap(getConfigMapName(wfConfigMap), fileName,
//        getConfigMapDataProp(wfConfigMap, fileName),
// helperKubeService.createConfigMapProp(properties));
//  }
//
//  @Override
//  public Map<String, String> getTaskOutPutConfigMapData(String workflowId,
//      String workflowActivityId, String taskId, String taskName) {
//    V1ConfigMap wfConfigMap = getConfigMap(helperKubeService.getLabelSelector("workflow",
// workflowId, workflowActivityId, null, null));
//    String fileName = taskName.replace(" ", "") + ".output.properties";
//    String dataString = getConfigMapDataProp(wfConfigMap, fileName);
//    try {
//      return helperKubeService.getConfigMapProp(dataString);
//    } catch (IOException e) {
//      return new HashMap<String, String>();
//    }
//  }
//
//  @Override
//  public V1Status deleteWorkflowConfigMap(String workflowId, String workflowActivityId) {
//    return
// deleteConfigMap(getConfigMapName(getConfigMap(helperKubeService.getLabelSelector("workflow",
// workflowId, workflowActivityId, null, null))));
//  }
//
//  @Override
//  public V1Status deleteTaskConfigMap(String workflowId, String workflowActivityId, String taskId,
// String taskActivityId) {
//    return
// deleteConfigMap(getConfigMapName(getConfigMap(helperKubeService.getLabelSelector("task",
// workflowId, workflowActivityId, taskId, taskActivityId))));
//  }
//
//  private V1Status deleteConfigMap(String configMapName) {
//    V1DeleteOptions deleteOptions = new V1DeleteOptions();
//    V1Status result = new V1Status();
//
//    try {
//      LOGGER.info("Deleting ConfigMap (" + configMapName + ")...");
//      result = getCoreApi().deleteNamespacedConfigMap(configMapName, kubeNamespace, kubeApiPretty,
//          deleteOptions, null, null, null, null);
//    } catch (JsonSyntaxException e) {
//      if (e.getCause() instanceof IllegalStateException) {
//        IllegalStateException ise = (IllegalStateException) e.getCause();
//        if (ise.getMessage() != null
//            && ise.getMessage().contains("Expected a string but was BEGIN_OBJECT")) {
//          LOGGER.error(
//              "Catching exception because of issue
// https://github.com/kubernetes-client/java/issues/86");
//        }
//
//        LOGGER.error("Exception when running deleteConfigMap()", e);
//      }
//    } catch (ApiException e) {
//      LOGGER.error("Exception when running deleteConfigMap()", e);
//    }
//    return result;
//  }
//
//  private V1ConfigMap getConfigMap(String labelSelector) {
//    V1ConfigMap configMap = null;
//    try {
//      V1ConfigMapList configMapList =
//          getCoreApi().listNamespacedConfigMap(kubeNamespace, kubeApiIncludeuninitialized,
//              kubeApiPretty, null, null, labelSelector, null, null, TIMEOUT_ONE_MINUTE, false);
//      if (!configMapList.getItems().isEmpty()) {
//        LOGGER.info(" getConfigMap() - Found " + configMapList.getItems().size() + " configmaps: "
//            + configMapList.getItems().stream().reduce("", (configMapNames, cm) -> configMapNames
// +=
//                cm.getMetadata().getName() + "(" + cm.getMetadata().getCreationTimestamp() + ")",
//                String::concat));
//        configMap = configMapList.getItems().get(0);
//        LOGGER.info(" getConfigMap() - chosen configmap: " + configMap.getMetadata().getName() +
// "("
//            + configMap.getMetadata().getCreationTimestamp() + ")");
//      }
//    } catch (ApiException e) {
//      LOGGER.error("Error: ", e);
//    }
//    return configMap;
//  }
//
//  protected V1ConfigMap getFirstConfigmapByDateTime(V1ConfigMapList configMapList) {
//    V1ConfigMap configMap = null;
//    if (!configMapList.getItems().isEmpty()) {
//      configMap = configMapList.getItems().get(0);
//      DateTime configMapDateTime = configMap.getMetadata().getCreationTimestamp();
//      for (int i = 0; i < configMapList.getItems().size(); i++) {
//        DateTime configMapDateTimeIter =
//            configMapList.getItems().get(i).getMetadata().getCreationTimestamp();
//        // if (configMapDateTimeIter != null) &&
//        // configMapDateTime.compareTo(configMapDateTimeIter) > 0) {
//        if (configMapDateTimeIter != null) {
//          LOGGER.info("Comparing " + configMapDateTime + " to " + configMapDateTimeIter + " = "
//              + configMapDateTime.compareTo(configMapDateTimeIter));
//          if (configMapDateTime.compareTo(configMapDateTimeIter) > 0) {
//            configMap = configMapList.getItems().get(i);
//            configMapDateTime = configMap.getMetadata().getCreationTimestamp();
//          }
//        }
//      }
//    }
//    return configMap;
//  }
//
//  protected String getConfigMapName(V1ConfigMap configMap) {
//    String configMapName = "";
//
//    if (configMap != null && !configMap.getMetadata().getName().isEmpty()) {
//      configMapName = configMap.getMetadata().getName();
//      LOGGER.info(" ConfigMap Name: " + configMapName);
//    }
//    return configMapName;
//  }
//
//  private V1ConfigMap getConfigMapResult(Watch<V1ConfigMap> watch) throws IOException {
//    V1ConfigMap result = null;
//    try {
//      if (watch.hasNext()) {
//        Watch.Response<V1ConfigMap> item = watch.next();
//        LOGGER.info(String.format("%s : %s%n", item.type, item.object.getMetadata().getName()));
//        result = item.object;
//      }
//    } finally {
//      watch.close();
//    }
//    return result;
//  }
//
//  private String getConfigMapDataProp(V1ConfigMap configMap, String key) {
//    String configMapDataProp = "";
//
//    if (configMap.getData().get(key) != null) {
//      configMapDataProp = configMap.getData().get(key);
//    }
//    return configMapDataProp;
//  }
//
//  private void patchConfigMap(String name, String dataKey, String origData, String newData) {
//    JsonObject jsonPatchObj = new JsonObject();
//    jsonPatchObj.addProperty("op", "add");
//    jsonPatchObj.addProperty("path", "/data/" + dataKey);
//    jsonPatchObj.addProperty("value",
//        origData.endsWith("\n") ? (origData + newData) : (origData + "\n" + newData));
//
//    ArrayList<JsonObject> arr = new ArrayList<>();
//    arr.add(((new Gson()).fromJson(jsonPatchObj.toString(),
// JsonElement.class)).getAsJsonObject());
//    try {
//      V1ConfigMap result =
//          getCoreApi().patchNamespacedConfigMap(name, kubeNamespace, arr, kubeApiPretty, null);
//      LOGGER.info(result);
//    } catch (ApiException e) {
//      LOGGER.error("Exception when calling CoreV1Api#patchNamespacedConfigMap", e);
//    }
//  }
//
//  protected V1ConfigMap createTaskConfigMapBody(String workflowName, String workflowId,
//      String workflowActivityId, String taskName, String taskId, String taskActivityId,
// Map<String, String> customLabels, Map<String, String> parameters) {
//    V1ConfigMap body = new V1ConfigMap();
//
//    body.metadata(
//        helperKubeService.getMetadata("task", workflowName, workflowId, workflowActivityId,
// taskId, taskActivityId, helperKubeService.getPrefixCFGMAP(), customLabels));
//
//    // Create Data
//    Map<String, String> inputsWithFixedKeys = new HashMap<>();
//    Map<String, String> sysProps = new HashMap<>();
//    sysProps.put("task-id", taskId);
//    sysProps.put("task-name", taskName);
//    sysProps.put("task-activity-id", taskActivityId);
//    sysProps.put("controller-service-url", bmrgControllerServiceURL);
//    sysProps.put("workflow-name", workflowName);
//    sysProps.put("workflow-id", workflowId);
//    sysProps.put("workflow-activity-id", workflowActivityId);
//    inputsWithFixedKeys.put("task.input.properties",
// helperKubeService.createConfigMapProp(parameters));
//    inputsWithFixedKeys.put("task.system.properties",
// helperKubeService.createConfigMapProp(sysProps));
//    body.data(inputsWithFixedKeys);
//    return body;
//  }
//
////  protected V1ConfigMap createTaskConfigMapBodyFromEnv(String workflowName, String workflowId,
////      String workflowActivityId, String taskName, String taskId, Map<String, String> parameters)
// {
////    V1ConfigMap body = new V1ConfigMap();
////
////    body.metadata(
////        helperKubeService.getMetadata(workflowName, workflowId, workflowActivityId, taskId,
// helperKubeService.getPrefixCFGMAP()));
////
////    // Create Data
////    Map<String, String> envParameters = new HashMap<>();
////    envParameters.put("SYSTEM_ACTIVITY_ID", workflowActivityId);
////    envParameters.put("SYSTEM_WORKFLOW_NAME", workflowName);
////    envParameters.put("SYSTEM_WORKFLOW_ID", workflowId);
////    envParameters.put("SYSTEM_CONTROLLER_URL", bmrgControllerServiceURL);
////
////    for (Map.Entry<String, String> entry : parameters.entrySet()) {
////      envParameters.put(entry.getKey().replace("-", "").replace(" ", "").replace(".",
// "_").toUpperCase(), entry.getValue());
////    }
////
////    body.data(envParameters);
////    return body;
////  }
//
//  protected V1ConfigMap createWorkflowConfigMapBody(String workflowName, String workflowId,
//      String workflowActivityId, Map<String, String> customLabels, Map<String, String> inputProps)
// {
//    V1ConfigMap body = new V1ConfigMap();
//
//    body.metadata(
//        helperKubeService.getMetadata("workflow", workflowName, workflowId, workflowActivityId,
// null, null, helperKubeService.getPrefixCFGMAP(), customLabels));
//
//    // Create Data
//    Map<String, String> inputsWithFixedKeys = new HashMap<>();
//    Map<String, String> sysProps = new HashMap<>();
//    sysProps.put("controller-service-url", bmrgControllerServiceURL);
//    sysProps.put("workflow-name", workflowName);
//    sysProps.put("workflow-id", workflowId);
//    sysProps.put("workflow-activity-id", workflowActivityId);
//    inputsWithFixedKeys.put("workflow.input.properties",
// helperKubeService.createConfigMapProp(inputProps));
//    inputsWithFixedKeys.put("workflow.system.properties",
// helperKubeService.createConfigMapProp(sysProps));
//    body.data(inputsWithFixedKeys);
//    return body;
//  }
// }

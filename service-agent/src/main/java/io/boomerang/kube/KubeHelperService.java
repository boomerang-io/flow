package io.boomerang.kube;

import io.boomerang.common.model.RunParam;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.WeightedPodAffinityTerm;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KubeHelperService {

  private static final Logger LOGGER = LogManager.getLogger(KubeHelperService.class);

  @Value("${proxy.enable}")
  protected Boolean proxyEnabled;

  @Value("${proxy.host}")
  protected String proxyHost;

  @Value("${proxy.port}")
  protected String proxyPort;

  @Value("${proxy.ignore}")
  protected String proxyIgnore;

  @Value("${flow.product:bmrg-flow}")
  protected String bmrgProduct;

  @Value("${flow.instance:bmrg-flow}")
  protected String bmrgInstance;

  // Utilized by LogServiceImpl
  // @Override
  public String getPrefixTask() {
    return bmrgProduct + "-task";
  }

  protected String getPrefixPVC() {
    return bmrgProduct + "-pvc";
  }

  protected String getPrefixPV() {
    return bmrgProduct + "-pv";
  }

  public String getPrefixCM() {
    return bmrgProduct + "-cfg";
  }

  protected String getPrefixVol() {
    return bmrgProduct + "-vol";
  }

  protected List<EnvVar> createProxyEnvVars() {
    List<EnvVar> proxyEnvVars = new ArrayList<>();

    if (proxyEnabled) {
      final String proxyUrl = "http://" + proxyHost + ":" + proxyPort;
      proxyEnvVars.add(createEnvVar("PROXY_HOST", proxyHost));
      proxyEnvVars.add(createEnvVar("PROXY_PORT", proxyPort));
      proxyEnvVars.add(createEnvVar("HTTP_PROXY", proxyUrl));
      proxyEnvVars.add(createEnvVar("HTTPS_PROXY", proxyUrl));
      proxyEnvVars.add(createEnvVar("http_proxy", proxyUrl));
      proxyEnvVars.add(createEnvVar("https_proxy", proxyUrl));
      proxyEnvVars.add(createEnvVar("NO_PROXY", proxyIgnore));
      proxyEnvVars.add(createEnvVar("no_proxy", proxyIgnore));
      proxyEnvVars.add(createEnvVar("use_proxy", "on"));
    }

    return proxyEnvVars;
  }

  protected EnvVar createEnvVar(String key, String value) {
    EnvVar envVar = new EnvVar();
    envVar.setName(key);
    envVar.setValue(value);
    return envVar;
  }

  protected List<EnvVar> createEnvVars(
      String workflowId, String workflowActivityId, String taskName, String taskActivityId) {
    List<EnvVar> envVars = new ArrayList<>();
    envVars.add(createEnvVar("BMRG_WORKFLOW_ID", workflowId));
    envVars.add(createEnvVar("BMRG_WORKFLOWRUN_ID", workflowActivityId));
    envVars.add(createEnvVar("BMRG_TASKRUN_ID", taskActivityId));
    envVars.add(createEnvVar("BMRG_TASKRUN_NAME", taskName.replace(" ", "")));
    return envVars;
  }

  protected String createConfigMapProp(List<RunParam> params) {
    LOGGER.info("Building ConfigMap Body");
    Properties props = new Properties();
    StringWriter propsSW = new StringWriter();
    if (params != null && !params.isEmpty()) {
      params.stream()
          .forEach(
              p -> {
                props.setProperty(p.getName(), p.getValue().toString());
              });
    }

    try {
      props.store(propsSW, null);
      LOGGER.info("" + propsSW.toString());
    } catch (IOException ex) {
      LOGGER.error(ex);
    }

    return propsSW.toString();
  }

  protected Map<String, String> createConfigMapData(List<RunParam> params) {
    LOGGER.info("Building ConfigMap Data");
    Map<String, String> data = new HashMap<>();
    if (params != null && !params.isEmpty()) {
      params.stream()
          .forEach(
              p -> {
                data.put(p.getName(), p.getValue().toString());
              });
    }

    return data;
  }

  protected String createConfigMapProp(Map<String, String> properties) {
    LOGGER.info("Building ConfigMap Body");
    Properties props = new Properties();
    StringWriter propsSW = new StringWriter();
    if (properties != null && !properties.isEmpty()) {
      properties.forEach(
          (key, value) -> {
            String valueStr = value != null ? value : "";
            LOGGER.info("createConfigMapProp() - " + key + "=" + valueStr);
            props.setProperty(key, valueStr);
          });
    }

    try {
      props.store(propsSW, null);
      LOGGER.info("" + propsSW.toString());
    } catch (IOException ex) {
      LOGGER.error(ex);
    }

    return propsSW.toString();
  }

  //
  // protected Map<String, String> getConfigMapProp(String properties) throws IOException {
  // LOGGER.info("Retrieving ConfigMap Properties File");
  // Properties props = new Properties();
  // if (properties != null && !properties.isEmpty()) {
  // props.load(new StringReader(properties));
  // }
  // props.list(System.out);
  // Map<String, String> propsMap = new HashMap<>();
  // for (Map.Entry<Object, Object> e : props.entrySet()) {
  // String key = (String)e.getKey();
  // String value = (String)e.getValue();
  // propsMap.put(key, value);
  // }
  // return propsMap;
  // }
  //
  // /*
  // * Passes through optional method inputs to the sub methods which need to handle this.
  // */
  // protected V1ObjectMeta getMetadata(String tier, String workflowName, String workflowId,
  // String workflowActivityId, String taskId, String taskActivityId, String generateName,
  // Map<String, String> labels) {
  // V1ObjectMeta metadata = new V1ObjectMeta();
  // metadata.annotations(createAnnotations(tier, workflowName, workflowId, workflowActivityId,
  // taskId, taskActivityId));
  // metadata.labels(createLabels(tier, workflowId, workflowActivityId, taskId, taskActivityId,
  // labels));
  // if (StringUtils.isNotBlank(generateName)) {
  // metadata.generateName(generateName + "-");
  // }
  // return metadata;
  // }
  //
  // /*
  // * Sets the tolerations and nodeSelector to match the dedicated node taints and node-role label
  // */
  // protected void getTolerationAndSelector(V1PodSpec podSpec) {
  // V1Toleration nodeTolerationItem = new V1Toleration();
  // nodeTolerationItem.key("dedicated");
  // nodeTolerationItem.value("bmrg-worker");
  // nodeTolerationItem.effect("NoSchedule");
  // nodeTolerationItem.operator("Equal");
  // podSpec.addTolerationsItem(nodeTolerationItem);
  // podSpec.putNodeSelectorItem("node-role.kubernetes.io/bmrg-worker", "true");
  // }
  //
  /*
   * Sets the pod anti affinity
   */
  protected Affinity getPodAffinity(Map<String, String> labels) {
    Affinity affinity = new Affinity();
    PodAntiAffinity podAntiAffinity = new PodAntiAffinity();
    List<WeightedPodAffinityTerm> weightedPodAffinityTerms = new ArrayList<>();
    LabelSelector labelSelector = new LabelSelector();
    labelSelector.setMatchLabels(labels);
    PodAffinityTerm podAffinityTerm = new PodAffinityTerm();
    podAffinityTerm.setLabelSelector(labelSelector);
    podAffinityTerm.setTopologyKey("kubernetes.io/hostname");
    WeightedPodAffinityTerm weightedPodAffinityTerm = new WeightedPodAffinityTerm();
    weightedPodAffinityTerm.setWeight(100);
    weightedPodAffinityTerm.setPodAffinityTerm(podAffinityTerm);
    weightedPodAffinityTerms.add(weightedPodAffinityTerm);
    podAntiAffinity.setPreferredDuringSchedulingIgnoredDuringExecution(weightedPodAffinityTerms);
    affinity.setPodAntiAffinity(podAntiAffinity);
    return affinity;
  }

  protected Map<String, String> createAntiAffinityLabels(String tier) {
    Map<String, String> labels = new HashMap<>();
    labels.put("boomerang.io/product", bmrgProduct);
    labels.put("boomerang.io/tier", tier);
    return labels;
  }

  private Map<String, String> getBaseLabels(String tier) {
    Map<String, String> labels = new HashMap<>();
    labels.put("app.kubernetes.io/name", bmrgProduct);
    labels.put("app.kubernetes.io/instance", bmrgInstance);
    labels.put("app.kubernetes.io/managed-by", "controller");
    labels.put("boomerang.io/product", bmrgProduct);
    labels.put("boomerang.io/tier", tier);
    return labels;
  }

  private Map<String, String> getLabels(
      String tier,
      String workflowRef,
      String workflowRunRef,
      String taskRunRef,
      Map<String, String> customLabels) {
    Map<String, String> labels = new HashMap<>();
    labels.putAll(getBaseLabels(tier));
    Optional.ofNullable(workflowRef).ifPresent(str -> labels.put("boomerang.io/workflow-ref", str));
    Optional.ofNullable(workflowRunRef)
        .ifPresent(str -> labels.put("boomerang.io/workflowrun-ref", str));
    Optional.ofNullable(taskRunRef).ifPresent(str -> labels.put("boomerang.io/taskrun-ref", str));
    Optional.ofNullable(customLabels).ifPresent(lbl -> labels.putAll(lbl));
    return labels;
  }

  protected Map<String, String> getTaskLabels(
      String workflowRef,
      String workflowRunRef,
      String taskRunRef,
      Map<String, String> customLabels) {
    return getLabels("task", workflowRef, workflowRunRef, taskRunRef, customLabels);
  }

  protected Map<String, String> getWorkflowLabels(
      String workflowRef, String workflowRunRef, Map<String, String> customLabels) {
    return getLabels("workflow", workflowRef, workflowRunRef, null, customLabels);
  }

  protected Map<String, String> getWorkspaceLabels(
      String workflowRef,
      String workspaceRef,
      String workspaceType,
      Map<String, String> customLabels) {
    Map<String, String> labels = new HashMap<>();
    labels.putAll(getBaseLabels("workspace"));
    Optional.ofNullable(workflowRef).ifPresent(str -> labels.put("boomerang.io/workflow-ref", str));
    Optional.ofNullable(workspaceRef)
        .ifPresent(str -> labels.put("boomerang.io/workspace-ref", str));
    Optional.ofNullable(workspaceType)
        .ifPresent(str -> labels.put("boomerang.io/workspace-type", str));
    Optional.ofNullable(customLabels).ifPresent(lbl -> labels.putAll(lbl));
    return labels;
  }

  protected Map<String, String> getAnnotations(
      String tier, String workflowRef, String workflowRunRef, String taskRunRef) {
    Map<String, String> annotations = new HashMap<>();
    annotations.put("boomerang.io/workflow-ref", workflowRef);
    annotations.put(
        "boomerang.io/selector", labelSelector(tier, workflowRef, workflowRunRef, taskRunRef));
    return annotations;
  }

  protected Map<String, String> getWorkspaceAnnotations(
      String workflowRef, String workspaceRef, String workspaceType) {
    Map<String, String> annotations = new HashMap<>();
    annotations.put("boomerang.io/workflow-ref", workflowRef);
    annotations.put("boomerang.io/workspace-ref", workspaceRef);
    annotations.put("boomerang.io/workspace-type", workspaceRef);
    annotations.put("boomerang.io/selector", workspaceLabelSelector(workspaceRef, workspaceType));
    return annotations;
  }

  protected String labelSelector(
      String tier, String workflowRef, String workflowRunRef, String taskRunRef) {
    StringBuilder labelSelector =
        new StringBuilder("boomerang.io/product=" + bmrgProduct + ",boomerang.io/tier=" + tier);
    Optional.ofNullable(workflowRef)
        .ifPresent(str -> labelSelector.append(",boomerang.io/workflow-ref=" + str));
    Optional.ofNullable(workflowRunRef)
        .ifPresent(str -> labelSelector.append(",boomerang.io/workflowrun-ref=" + str));
    Optional.ofNullable(taskRunRef)
        .ifPresent(str -> labelSelector.append(",boomerang.io/taskrun-ref=" + str));

    LOGGER.info("  labelSelector: " + labelSelector.toString());
    return labelSelector.toString();
  }

  protected String workspaceLabelSelector(String workspaceRef, String workspaceType) {
    StringBuilder labelSelector =
        new StringBuilder(
            "boomerang.io/product="
                + bmrgProduct
                + ",boomerang.io/tier=workspace,boomerang.io/workspace-ref="
                + workspaceRef
                + ",boomerang.io/workspace-type="
                + workspaceType);

    LOGGER.info("  labelSelector: " + labelSelector.toString());
    return labelSelector.toString();
  }
}

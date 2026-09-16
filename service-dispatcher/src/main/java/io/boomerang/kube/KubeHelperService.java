package io.boomerang.kube;

import io.boomerang.common.model.RunParam;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import tools.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodAffinityTerm;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.WeightedPodAffinityTerm;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class KubeHelperService {

  private static final Logger LOGGER = LogManager.getLogger(KubeHelperService.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final int LABEL_SEGMENT_MAX_LENGTH = 63;
  private static final int LABEL_PREFIX_MAX_LENGTH = 253;

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

  @Value("${flow.version:0.0.0}")
  protected String flowVersion;

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

  public String getPrefixVol() {
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

  public EnvVar createEnvVar(String key, String value) {
    EnvVar envVar = new EnvVar();
    envVar.setName(key);
    envVar.setValue(value);
    return envVar;
  }

  /*
   * The task container environment, in increasing precedence (later entries win on a name
   * collision): proxy vars, DEBUG/CI, executor-specific runtimeVars, one PARAM_<NAME> per Task
   * Param plus PARAM_NAMES (the original names, comma-separated, so a task library can map
   * PARAM_PRIVATEKEY back to privateKey; params whose sanitised names collide fail the Task
   * rather than silently overwrite each other), then the Task-defined envVars. $(params.x)
   * references in script/args are substituted by the engine.
   */
  public List<EnvVar> createTaskEnvVars(
      Boolean debug, List<RunParam> params, List<TaskEnvVar> envVars, EnvVar... runtimeVars) {
    Map<String, EnvVar> byName = new LinkedHashMap<>();
    createProxyEnvVars().forEach(var -> byName.put(var.getName(), var));
    byName.put("DEBUG", createEnvVar("DEBUG", String.valueOf(debug)));
    byName.put("CI", createEnvVar("CI", "true"));
    // Which Flow contract/platform the task is running against - part of the documented task
    // contract, so a task (or task-core) can branch on capability by version.
    byName.put("FLOW_VERSION", createEnvVar("FLOW_VERSION", flowVersion));
    for (EnvVar var : runtimeVars) {
      byName.put(var.getName(), var);
    }
    if (params != null) {
      byName.put(
          "PARAM_NAMES",
          createEnvVar(
              "PARAM_NAMES",
              params.stream().map(RunParam::getName).collect(Collectors.joining(","))));
      Map<String, String> paramNameByEnvName = new HashMap<>();
      for (RunParam p : params) {
        String name = "PARAM_" + ParameterUtil.envFold(p.getName());
        String collidingParam = paramNameByEnvName.put(name, p.getName());
        if (collidingParam != null) {
          throw new BoomerangException(
              BoomerangError.TASK_EXECUTION_ERROR,
              "PARAM_NAME_COLLISION - Params '"
                  + collidingParam
                  + "' and '"
                  + p.getName()
                  + "' both map to env var "
                  + name);
        }
        byName.put(name, createEnvVar(name, paramValueAsString(p.getValue())));
      }
    }
    if (envVars != null) {
      envVars.forEach(var -> byName.put(var.getName(), createEnvVar(var.getName(), var.getValue())));
    }
    return new ArrayList<>(byName.values());
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

  /**
   * Return a param value as a String: a String value is returned unchanged, a null value
   * becomes an empty String, and anything else (object, array, number, boolean) is
   * JSON-serialised.
   */
  protected String paramValueAsString(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof String stringValue) {
      return stringValue;
    }
    return OBJECT_MAPPER.writeValueAsString(value);
  }

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

  // A Toleration with no key, operator, or effect matches nothing and Kubernetes rejects the
  // empty operator outright, so a legacy "[{}]"-shaped property value must become a no-op here.
  protected List<Toleration> withData(List<Toleration> tolerations) {
    return tolerations.stream()
        .filter(
            t ->
                StringUtils.hasText(t.getKey())
                    || StringUtils.hasText(t.getOperator())
                    || StringUtils.hasText(t.getEffect()))
        .toList();
  }

  // A HostAlias with no ip has nothing to alias; drop it the same way.
  protected List<HostAlias> withHostData(List<HostAlias> hostAliases) {
    return hostAliases.stream().filter(h -> StringUtils.hasText(h.getIp())).toList();
  }

  protected Map<String, String> createAntiAffinityLabels(String tier) {
    Map<String, String> labels = new HashMap<>();
    labels.put("boomerang.io/product", bmrgProduct);
    labels.put("boomerang.io/tier", tier);
    return labels;
  }

  public Map<String, String> getBaseLabels(String tier) {
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
    applyCustomLabels(labels, customLabels);
    return labels;
  }

  /*
   * Run labels are user metadata: they reach the dispatcher unchecked, but Kubernetes rejects the
   * whole object when one breaks its rules - a "/" in a value or anything over 63 characters -
   * so a workflow labelled "team/name=platform/flow" failed its volume with a 422 the author
   * could not see. Each user key and value is coerced into the Kubernetes shape instead:
   * characters outside [A-Za-z0-9._-] become "_" (the key's optional DNS-subdomain prefix keeps
   * its own narrower [a-z0-9.-] set), each part is truncated to its length ceiling and trimmed to
   * alphanumeric ends. A key left with no name at all cannot be written, so it is dropped; the
   * labels the dispatcher sets itself are placed first and a user label never replaces one -
   * they are the selectors every lookup, watch and delete runs on.
   */
  private void applyCustomLabels(Map<String, String> labels, Map<String, String> customLabels) {
    if (customLabels == null) {
      return;
    }
    customLabels.forEach(
        (key, value) -> {
          String sanitisedKey = sanitiseLabelKey(key);
          if (sanitisedKey == null) {
            LOGGER.debug("Dropped label with an unusable key ({}).", key);
            return;
          }
          String sanitisedValue = sanitiseLabelSegment(value == null ? "" : value);
          if (!sanitisedKey.equals(key) || !sanitisedValue.equals(value)) {
            LOGGER.debug(
                "Sanitised label {}={} to {}={}.", key, value, sanitisedKey, sanitisedValue);
          }
          if (labels.putIfAbsent(sanitisedKey, sanitisedValue) != null) {
            LOGGER.debug("Ignored label ({}): the dispatcher sets it.", sanitisedKey);
          }
        });
  }

  // A label key is an optional DNS-subdomain prefix, "/", then a name. Null when no name survives.
  private static String sanitiseLabelKey(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    int separator = key.indexOf('/');
    String name = sanitiseLabelSegment(separator < 0 ? key : key.substring(separator + 1));
    if (name.isEmpty()) {
      return null;
    }
    if (separator < 0) {
      return name;
    }
    String prefix = sanitiseLabelPrefix(key.substring(0, separator));
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  // Label names and values: at most 63 of [A-Za-z0-9._-], alphanumeric at both ends.
  private static String sanitiseLabelSegment(String segment) {
    String coerced = segment.replaceAll("[^A-Za-z0-9._-]", "_");
    if (coerced.length() > LABEL_SEGMENT_MAX_LENGTH) {
      coerced = coerced.substring(0, LABEL_SEGMENT_MAX_LENGTH);
    }
    return trimToAlphanumericEnds(coerced);
  }

  // A key prefix is a DNS subdomain: at most 253 of [a-z0-9.-], each dot-separated part
  // alphanumeric at both ends.
  private static String sanitiseLabelPrefix(String prefix) {
    String coerced = prefix.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
    if (coerced.length() > LABEL_PREFIX_MAX_LENGTH) {
      coerced = coerced.substring(0, LABEL_PREFIX_MAX_LENGTH);
    }
    return Arrays.stream(coerced.split("\\."))
        .map(KubeHelperService::trimToAlphanumericEnds)
        .filter(part -> !part.isEmpty())
        .collect(Collectors.joining("."));
  }

  private static String trimToAlphanumericEnds(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && !isAlphanumeric(value.charAt(start))) {
      start++;
    }
    while (end > start && !isAlphanumeric(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  private static boolean isAlphanumeric(char character) {
    return (character >= 'a' && character <= 'z')
        || (character >= 'A' && character <= 'Z')
        || (character >= '0' && character <= '9');
  }

  public Map<String, String> getTaskLabels(
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

  public Map<String, String> getWorkspaceLabels(
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
    applyCustomLabels(labels, customLabels);
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

  /**
   * Inspect the failed Task's "task" container for a more specific cause than a generic
   * failure: {@code OOMKilled} from a terminated container, or an image-pull failure from a
   * waiting one. Returns null when neither applies, so the caller falls back to a generic
   * JobFailed/TaskRunFailed reason.
   */
  protected String getPodFailureReason(KubernetesClient client, Map<String, String> taskLabels) {
    List<Pod> pods = client.pods().withLabels(taskLabels).list().getItems();
    if (pods.isEmpty()) {
      return null;
    }
    return Optional.ofNullable(pods.get(0).getStatus())
        .map(PodStatus::getContainerStatuses)
        .orElse(List.of())
        .stream()
        .filter(cs -> "task".equals(cs.getName()))
        .map(ContainerStatus::getState)
        .filter(Objects::nonNull)
        .findFirst()
        .map(this::reasonFromContainerState)
        .orElse(null);
  }

  private String reasonFromContainerState(ContainerState state) {
    if (state.getTerminated() != null && "OOMKilled".equals(state.getTerminated().getReason())) {
      return "OOMKilled";
    }
    if (state.getWaiting() != null) {
      String reason = state.getWaiting().getReason();
      if ("ImagePullBackOff".equals(reason)
          || "ErrImagePull".equals(reason)
          || "InvalidImageName".equals(reason)) {
        return "ImagePull";
      }
    }
    return null;
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

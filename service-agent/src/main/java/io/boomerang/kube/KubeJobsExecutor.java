package io.boomerang.kube;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.boomerang.agent.WorkspaceService;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.executor.JobWatcher;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TerminationMessageParser;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapProjection;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSource;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.ProjectedVolumeSource;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeProjection;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import java.lang.reflect.Type;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Executes Tasks as Kubernetes batch/v1 Jobs instead of Tekton TaskRuns. Reuses the
 * runtime-agnostic PVC/ConfigMap plumbing in {@link KubeServiceImpl}/{@link KubeHelperService};
 * builds the same volumes (data/params/workspace) that {@link TektonServiceImpl} builds.
 */
@Component
@ConditionalOnProperty(name = "agent.executor", havingValue = "kube-jobs")
public class KubeJobsExecutor implements TaskExecutor {

  private static final Logger LOGGER = LogManager.getLogger(KubeJobsExecutor.class);

  private static final Integer ONE_DAY_IN_SECONDS = 86400;

  @Autowired protected KubeHelperService helperKubeService;

  @Autowired protected KubeServiceImpl kubeService;

  @Autowired private WorkspaceService workspaceService;

  @Value("${kube.image.pullPolicy}")
  private String kubeImagePullPolicy;

  @Value("${kube.image.pullSecret}")
  private String kubeImagePullSecret;

  @Value("${kube.task.backOffLimit}")
  private Integer kubeJobBackOffLimit;

  @Value("${kube.task.restartPolicy}")
  private String kubeJobRestartPolicy;

  @Value("${kube.task.ttlDays}")
  private Integer kubeJobTTLDays;

  @Value("${kube.task.storage.data.memory}")
  private Boolean kubeTaskStorageDataMemory;

  @Value("${agent.tasks.serviceaccount}")
  private String kubeWorkerServiceAccount;

  @Value("${agent.tasks.hostaliases}")
  private String kubeWorkerHostAliases;

  @Value("#{${agent.tasks.nodeselector}}")
  private Map<String, String> kubeWorkerNodeSelector;

  @Value("${agent.tasks.tolerations}")
  private String kubeWorkerTolerations;

  @Value("${agent.tasks.runtimeClassName}")
  private String kubeWorkerRuntimeClassName;

  private KubernetesClient client;

  public KubeJobsExecutor() {
    this.client = new KubernetesClientBuilder().build();
  }

  // Using a setter instead of the constructor due to autowiring issues (matches KubeServiceImpl).
  public void setClient(KubernetesClient client) {
    this.client = client;
  }

  @Override
  public void create(TaskRun task, Long timeoutMinutes) throws InterruptedException, ParseException {
    LOGGER.info("Initializing Task...");

    String workflowRef = task.getWorkflowRef();
    String workflowRunRef = task.getWorkflowRunRef();
    String taskRunRef = task.getId();
    TaskRunSpec spec = task.getSpec();
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(workflowRef, workflowRunRef, taskRunRef, task.getLabels());

    List<Volume> volumes = new ArrayList<>();
    List<VolumeMount> volumeMounts = new ArrayList<>();
    addDataVolume(volumes, volumeMounts, task.getParams());
    addParamsVolume(volumes, volumeMounts, taskLabels);
    addWorkspaceVolumes(volumes, volumeMounts, workflowRef, workflowRunRef, task.getWorkspaces());

    List<String> containerCommand =
        addScriptOrCommand(volumes, volumeMounts, taskLabels, spec.getScript(), spec.getCommand());

    Container container = new Container();
    container.setName("task");
    container.setImage(spec.getImage());
    container.setImagePullPolicy(kubeImagePullPolicy);
    container.setWorkingDir(spec.getWorkingDir());
    container.setArgs(spec.getArguments());
    container.setCommand(containerCommand);
    container.setEnv(createContainerEnvVars(spec.getDebug(), spec.getEnvs()));
    container.setVolumeMounts(volumeMounts);
    container.setTerminationMessagePath("/dev/termination-log");
    container.setTerminationMessagePolicy("File");

    PodSpec podSpec = new PodSpec();
    podSpec.setRestartPolicy(kubeJobRestartPolicy);
    podSpec.setContainers(List.of(container));
    podSpec.setVolumes(volumes);
    podSpec.setNodeSelector(nodeSelectors());
    podSpec.setTolerations(tolerations());
    podSpec.setHostAliases(hostAliases());
    podSpec.setImagePullSecrets(imagePullSecrets());
    if (kubeWorkerServiceAccount != null && !kubeWorkerServiceAccount.isBlank()) {
      podSpec.setServiceAccountName(kubeWorkerServiceAccount);
    }
    if (kubeWorkerRuntimeClassName != null && !kubeWorkerRuntimeClassName.isBlank()) {
      podSpec.setRuntimeClassName(kubeWorkerRuntimeClassName);
    }

    ObjectMeta podMeta = new ObjectMeta();
    podMeta.setLabels(taskLabels);
    PodTemplateSpec podTemplate = new PodTemplateSpec();
    podTemplate.setMetadata(podMeta);
    podTemplate.setSpec(podSpec);

    JobSpec jobSpec = new JobSpec();
    jobSpec.setBackoffLimit(kubeJobBackOffLimit);
    jobSpec.setTtlSecondsAfterFinished(kubeJobTTLDays * ONE_DAY_IN_SECONDS);
    jobSpec.setActiveDeadlineSeconds(timeoutMinutes * 60);
    jobSpec.setTemplate(podTemplate);

    ObjectMeta jobMeta = new ObjectMeta();
    jobMeta.setGenerateName(helperKubeService.getPrefixTask() + "-" + taskRunRef + "-");
    jobMeta.setLabels(taskLabels);
    jobMeta.setAnnotations(
        helperKubeService.getAnnotations("task", workflowRef, workflowRunRef, taskRunRef));

    Job job = new Job();
    job.setMetadata(jobMeta);
    job.setSpec(jobSpec);

    LOGGER.info(job);
    Job result = client.batch().v1().jobs().resource(job).create();
    LOGGER.info(result);
  }

  private void addDataVolume(List<Volume> volumes, List<VolumeMount> volumeMounts, List<RunParam> params) {
    String name = helperKubeService.getPrefixVol() + "-data";

    VolumeMount mount = new VolumeMount();
    mount.setName(name);
    mount.setMountPath("/data");
    volumeMounts.add(mount);

    Object memoryFlag =
        Optional.ofNullable(params).orElse(List.of()).stream()
            .filter(p -> "worker.storage.data.memory".equals(p.getName()))
            .map(RunParam::getValue)
            .findFirst()
            .orElse(null);
    EmptyDirVolumeSource emptyDir = new EmptyDirVolumeSource();
    if (Boolean.TRUE.equals(kubeTaskStorageDataMemory)
        && memoryFlag != null
        && Boolean.valueOf((boolean) memoryFlag)) {
      LOGGER.info("Setting data to in memory storage...");
      emptyDir.setMedium("Memory");
    }

    Volume volume = new Volume();
    volume.setName(name);
    volume.setEmptyDir(emptyDir);
    volumes.add(volume);
  }

  private void addParamsVolume(
      List<Volume> volumes, List<VolumeMount> volumeMounts, Map<String, String> taskLabels) {
    String name = helperKubeService.getPrefixVol() + "-params";

    VolumeMount mount = new VolumeMount();
    mount.setName(name);
    mount.setMountPath("/params");
    volumeMounts.add(mount);

    ConfigMapProjection configMapProjection = new ConfigMapProjection();
    configMapProjection.setName(kubeService.getConfigMapName(taskLabels));
    VolumeProjection projection = new VolumeProjection();
    projection.setConfigMap(configMapProjection);
    ProjectedVolumeSource projectedSource = new ProjectedVolumeSource();
    projectedSource.setSources(List.of(projection));

    Volume volume = new Volume();
    volume.setName(name);
    volume.setProjected(projectedSource);
    volumes.add(volume);
  }

  private void addWorkspaceVolumes(
      List<Volume> volumes,
      List<VolumeMount> volumeMounts,
      String workflowRef,
      String workflowRunRef,
      List<TaskWorkspace> workspaces) {
    if (workspaces == null) {
      return;
    }
    workspaces.forEach(
        ws -> {
          String type = ws.getType();
          if (!"workflow".equalsIgnoreCase(type) && !"workflowrun".equalsIgnoreCase(type)) {
            LOGGER.warn(
                "Skipping Workspace (" + ws.getName() + ") as we don't support custom workspaces yet.");
            return;
          }

          String workspaceRef = workspaceService.getWorkspaceRef(type, workflowRef, workflowRunRef);
          String name = helperKubeService.getPrefixVol() + "-ws-" + type;
          String pvcName =
              kubeService.getPVCName(
                  helperKubeService.getWorkspaceLabels(workflowRef, workspaceRef, type, null));

          PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSource();
          pvcSource.setClaimName(pvcName);
          Volume volume = new Volume();
          volume.setName(name);
          volume.setPersistentVolumeClaim(pvcSource);
          volumes.add(volume);

          VolumeMount mount = new VolumeMount();
          mount.setName(name);
          mount.setMountPath(
              ws.getMountPath() != null && !ws.getMountPath().isEmpty()
                  ? ws.getMountPath()
                  : "/workspace/" + type);
          volumeMounts.add(mount);
        });
  }

  /**
   * Non-blank script wins: it's mounted as an executable ConfigMap file (the script MUST start
   * with a shebang, exactly as Tekton relies on) and run as the container command; otherwise the
   * declared command runs as-is.
   */
  private List<String> addScriptOrCommand(
      List<Volume> volumes,
      List<VolumeMount> volumeMounts,
      Map<String, String> taskLabels,
      String script,
      List<String> command) {
    if (script == null || script.isBlank()) {
      return command;
    }

    ConfigMap scriptConfigMap =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withGenerateName(helperKubeService.getPrefixCM() + "-script-")
            .withLabels(taskLabels)
            .endMetadata()
            .addToData("script", script)
            .build();
    ConfigMap created = client.configMaps().resource(scriptConfigMap).create();

    String name = helperKubeService.getPrefixVol() + "-script";
    ConfigMapVolumeSource cmSource = new ConfigMapVolumeSource();
    cmSource.setName(created.getMetadata().getName());
    cmSource.setDefaultMode(0755);
    Volume volume = new Volume();
    volume.setName(name);
    volume.setConfigMap(cmSource);
    volumes.add(volume);

    VolumeMount mount = new VolumeMount();
    mount.setName(name);
    mount.setMountPath("/scripts");
    volumeMounts.add(mount);

    return List.of("/scripts/script");
  }

  private List<EnvVar> createContainerEnvVars(Boolean debug, List<TaskEnvVar> envVars) {
    List<EnvVar> vars = new ArrayList<>(helperKubeService.createProxyEnvVars());
    vars.add(helperKubeService.createEnvVar("DEBUG", String.valueOf(debug)));
    vars.add(helperKubeService.createEnvVar("CI", "true"));
    vars.add(helperKubeService.createEnvVar("RESULTS_PATH", "/dev/termination-log"));
    if (envVars != null) {
      envVars.forEach(var -> vars.add(helperKubeService.createEnvVar(var.getName(), var.getValue())));
    }
    return vars;
  }

  private Map<String, String> nodeSelectors() {
    return kubeWorkerNodeSelector != null ? kubeWorkerNodeSelector : Map.of();
  }

  private List<Toleration> tolerations() {
    if (kubeWorkerTolerations == null
        || kubeWorkerTolerations.isEmpty()
        || "null".equalsIgnoreCase(kubeWorkerTolerations)) {
      return List.of();
    }
    Type listType = new TypeToken<List<Toleration>>() {}.getType();
    return new Gson().fromJson(kubeWorkerTolerations, listType);
  }

  private List<HostAlias> hostAliases() {
    if (kubeWorkerHostAliases == null || kubeWorkerHostAliases.isEmpty()) {
      return List.of();
    }
    Type listType = new TypeToken<List<HostAlias>>() {}.getType();
    return new Gson().fromJson(kubeWorkerHostAliases, listType);
  }

  private List<LocalObjectReference> imagePullSecrets() {
    LocalObjectReference secret = new LocalObjectReference();
    secret.setName(kubeImagePullSecret);
    return List.of(secret);
  }

  @Override
  public List<RunResult> watch(TaskRun task, Long timeoutMinutes) throws InterruptedException {
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(
            task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());

    final CountDownLatch latch = new CountDownLatch(1);
    JobWatcher jobWatcher = new JobWatcher(latch);

    try (Watch ignore = client.batch().v1().jobs().withLabels(taskLabels).watch(jobWatcher)) {
      // Timeout is 10 minutes more than the Job's own deadline to allow for scheduling delays.
      boolean jobComplete = latch.await(timeoutMinutes + 10, TimeUnit.MINUTES);
      if (!jobComplete) {
        throw new BoomerangException(
            BoomerangError.TASK_EXECUTION_ERROR,
            "JobTimeout - Job timed out while waiting for completion.");
      }

      JobCondition condition = jobWatcher.getCondition();
      if (condition != null && "Complete".equals(condition.getType()) && "True".equals(condition.getStatus())) {
        LOGGER.info("Job completed successfully");
        return readResults(taskLabels, task.getResults());
      }

      String reason = condition != null ? condition.getReason() : "Unknown";
      String message = condition != null ? condition.getMessage() : "Job did not report a terminal condition.";
      LOGGER.info("Task execution error. " + reason + " - " + message);
      if ("DeadlineExceeded".equals(reason)) {
        throw new BoomerangException(BoomerangError.TASK_EXECUTION_ERROR, "DeadlineExceeded - " + message);
      }
      throw new BoomerangException(BoomerangError.TASK_EXECUTION_ERROR, reason + " - " + message);
    } catch (Exception e) {
      LOGGER.error(e.toString());
      throw e;
    }
  }

  private List<RunResult> readResults(Map<String, String> taskLabels, List<RunResult> declaredResults) {
    String message = getTerminationMessage(taskLabels);
    if (message == null || message.isBlank()) {
      if (kubeService.isTaskRunResultTooLarge(taskLabels)) {
        throw new BoomerangException(
            BoomerangError.TASK_EXECUTION_ERROR,
            "TaskRunResultTooLarge - Task has exceeded the maximum allowed 4096 byte size for Result Parameters.");
      }
      return List.of();
    }
    return TerminationMessageParser.parse(message, declaredResults);
  }

  private String getTerminationMessage(Map<String, String> taskLabels) {
    List<Pod> pods = client.pods().withLabels(taskLabels).list().getItems();
    if (pods.isEmpty()) {
      return null;
    }
    return pods.get(0).getStatus() != null && pods.get(0).getStatus().getContainerStatuses() != null
        ? pods.get(0).getStatus().getContainerStatuses().stream()
            .filter(cs -> "task".equals(cs.getName()))
            .map(ContainerStatus::getState)
            .filter(Objects::nonNull)
            .map(ContainerState::getTerminated)
            .filter(Objects::nonNull)
            .map(ContainerStateTerminated::getMessage)
            .findFirst()
            .orElse(null)
        : null;
  }

  @Override
  public void cancel(TaskRun task) {
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(
            task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());

    LOGGER.info("Cancelling Job with labels: " + taskLabels);

    List<Job> jobs = client.batch().v1().jobs().withLabels(taskLabels).list().getItems();
    if (jobs.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.TASK_EXECUTION_ERROR, "CANCEL_FAILURE - No jobs found matching the labels: " + taskLabels);
    }

    client
        .batch()
        .v1()
        .jobs()
        .withLabels(taskLabels)
        .withPropagationPolicy(DeletionPropagation.FOREGROUND)
        .delete();
  }

  @Override
  public void delete(TaskRun task) {
    LOGGER.debug("Deleting Job...");

    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(
            task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());

    client
        .batch()
        .v1()
        .jobs()
        .withLabels(taskLabels)
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete();
  }
}

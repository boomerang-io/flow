package io.boomerang.kube;

import com.fasterxml.jackson.core.type.TypeReference;
import io.boomerang.dispatcher.LeaseRegistry;
import io.boomerang.dispatcher.WorkspaceService;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.JobWatcher;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TerminationMessageParser;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSource;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
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
 * runtime-agnostic PVC plumbing in {@link KubeServiceImpl}/{@link KubeHelperService}; builds the
 * same volumes (data/workspace) that {@link TektonServiceImpl} builds. Params are delivered as
 * environment variables only ({@link KubeHelperService#createTaskEnvVars}); the script ConfigMap
 * is owned end-to-end by this class (created in {@link #create}, cleaned up in {@link #watch},
 * {@link #cancel}, and {@link #delete}).
 */
@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "kube-jobs")
public class KubeJobsExecutor implements TaskExecutor {

  private static final Logger LOGGER = LogManager.getLogger(KubeJobsExecutor.class);

  private static final Integer ONE_DAY_IN_SECONDS = 86400;

  @Autowired protected KubeHelperService helperKubeService;

  @Autowired protected KubeServiceImpl kubeService;

  @Autowired private WorkspaceService workspaceService;

  @Value("${kube.timeout.watchGraceMinutes}")
  private long watchGraceMinutes;

  @Value("${kube.timeout.reconcileSeconds}")
  private long reconcileSeconds;

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

  @Value("${dispatcher.tasks.serviceaccount}")
  private String kubeWorkerServiceAccount;

  @Value("${dispatcher.tasks.hostaliases}")
  private String kubeWorkerHostAliases;

  @Value("#{${dispatcher.tasks.nodeselector}}")
  private Map<String, String> kubeWorkerNodeSelector;

  @Value("${dispatcher.tasks.tolerations}")
  private String kubeWorkerTolerations;

  @Value("${dispatcher.tasks.runtimeClassName}")
  private String kubeWorkerRuntimeClassName;

  private final LeaseRegistry leaseRegistry;

  private KubernetesClient client;

  public KubeJobsExecutor(KubernetesClient client, LeaseRegistry leaseRegistry) {
    this.client = client;
    this.leaseRegistry = leaseRegistry;
  }

  // Tests swap in the mock-server client after the context is up.
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

    List<Job> existing = client.batch().v1().jobs().withLabels(taskLabels).list().getItems();
    if (!existing.isEmpty()) {
      LOGGER.info(
          "Adopting existing Job {} for TaskRun {}",
          existing.get(0).getMetadata().getName(),
          taskRunRef);
      return;
    }

    List<Volume> volumes = new ArrayList<>();
    List<VolumeMount> volumeMounts = new ArrayList<>();
    addDataVolume(volumes, volumeMounts, task.getParams());
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
    container.setEnv(
        helperKubeService.createTaskEnvVars(
            spec.getDebug(),
            task.getParams(),
            spec.getEnvs(),
            helperKubeService.createEnvVar("RESULTS_PATH", "/dev/termination-log")));
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

  private Map<String, String> nodeSelectors() {
    return kubeWorkerNodeSelector != null ? kubeWorkerNodeSelector : Map.of();
  }

  private List<Toleration> tolerations() {
    if (kubeWorkerTolerations == null
        || kubeWorkerTolerations.isEmpty()
        || "null".equalsIgnoreCase(kubeWorkerTolerations)) {
      return List.of();
    }
    List<Toleration> tolerations =
        Serialization.unmarshal(kubeWorkerTolerations, new TypeReference<List<Toleration>>() {});
    return helperKubeService.withData(tolerations);
  }

  private List<HostAlias> hostAliases() {
    if (kubeWorkerHostAliases == null || kubeWorkerHostAliases.isEmpty()) {
      return List.of();
    }
    List<HostAlias> hostAliases =
        Serialization.unmarshal(kubeWorkerHostAliases, new TypeReference<List<HostAlias>>() {});
    return helperKubeService.withHostData(hostAliases);
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
    Watch watch = client.batch().v1().jobs().withLabels(taskLabels).watch(jobWatcher);

    try {
      leaseRegistry.beat(task.getId());
      // A backstop only, for the case where the Job's own deadline never reaches this watch. The
      // engine owns the deadline and reaps at the task budget plus a few seconds, so it always
      // acts first; this grace exists to release the thread and report, not to wait for
      // scheduling. Raise kube.timeout.watchGraceMinutes where image pulls are slow.
      Instant deadline = Instant.now().plus(Duration.ofMinutes(timeoutMinutes + watchGraceMinutes));
      while (!latch.await(reconcileSeconds, TimeUnit.SECONDS)) {
        leaseRegistry.beat(task.getId());
        if (Instant.now().isAfter(deadline)) {
          throw new TaskExecutionException(
              "DeadlineExceeded", "JobTimeout - Job timed out while waiting for completion.");
        }
        // The watch is the fast path; this poll is the reconcile that catches whatever the watch
        // missed - a dropped connection, or a state transition that happened before the watch was
        // established.
        List<Job> jobs = client.batch().v1().jobs().withLabels(taskLabels).list().getItems();
        if (jobs.isEmpty()) {
          jobWatcher.markDeleted();
        } else {
          jobWatcher.evaluate(jobs.get(0));
        }
        if (jobWatcher.isWatchLost()) {
          watch.close();
          watch = client.batch().v1().jobs().withLabels(taskLabels).watch(jobWatcher);
          jobWatcher.resetWatchLost();
        }
      }

      JobCondition condition = jobWatcher.getCondition();
      if (condition != null && "Complete".equals(condition.getType()) && "True".equals(condition.getStatus())) {
        LOGGER.info("Job completed successfully");
        return readResults(taskLabels, task.getResults());
      }

      String reason = condition != null ? condition.getReason() : "Unknown";
      String message = condition != null ? condition.getMessage() : "Job did not report a terminal condition.";
      LOGGER.info("Task execution error. " + reason + " - " + message);
      // The container may have written its Result Parameters to the termination log before it
      // exited non-zero; carry them so a failed Task's output still reaches the Engine.
      List<RunResult> failureResults = readResults(taskLabels, task.getResults());
      throw failureFor(reason, message, taskLabels, failureResults);
    } catch (Exception e) {
      LOGGER.error(e.toString());
      throw e;
    } finally {
      watch.close();
      leaseRegistry.remove(task.getId());
      deleteConfigMaps(taskLabels);
    }
  }

  private TaskExecutionException failureFor(
      String reason, String message, Map<String, String> taskLabels, List<RunResult> results) {
    if ("DeadlineExceeded".equals(reason)) {
      return new TaskExecutionException("DeadlineExceeded", results, reason + " - " + message);
    }
    if ("JobDeleted".equals(reason)) {
      return new TaskExecutionException("JobDeleted", results, reason + " - " + message);
    }
    String podReason = helperKubeService.getPodFailureReason(client, taskLabels);
    if ("OOMKilled".equals(podReason)) {
      return new TaskExecutionException("OOMKilled", results, reason + " - " + message);
    }
    if ("ImagePull".equals(podReason)) {
      return new TaskExecutionException("ImagePull", results, reason + " - " + message);
    }
    return new TaskExecutionException("JobFailed", results, reason + " - " + message);
  }

  /**
   * Delete any ConfigMaps created for the task (currently just the script ConfigMap for script
   * tasks). Label-selector delete is idempotent — safe to call even when none exist.
   */
  private void deleteConfigMaps(Map<String, String> taskLabels) {
    client.configMaps().withLabels(taskLabels).delete();
  }

  private List<RunResult> readResults(Map<String, String> taskLabels, List<RunResult> declaredResults) {
    String message = getTerminationMessage(taskLabels);
    if (message == null || message.isBlank()) {
      if (kubeService.isTaskRunResultTooLarge(taskLabels)) {
        throw new TaskExecutionException(
            "ResultsTooLarge",
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
            .filter(Objects::nonNull)
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
    deleteConfigMaps(taskLabels);
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
    deleteConfigMaps(taskLabels);
  }
}

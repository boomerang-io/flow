package io.boomerang.kube;

import com.fasterxml.jackson.core.type.TypeReference;
import io.boomerang.dispatcher.sdk.model.RunParam;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import io.boomerang.dispatcher.sdk.model.WorkflowWorkspace;
import io.boomerang.dispatcher.sdk.StatusReason;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.JobWatcher;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.PodStartWatch;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.executor.TaskResourceResolver;
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
import java.util.concurrent.ConcurrentHashMap;
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

  // Kubernetes' own ceiling on a container termination message; above it the message is truncated.
  private static final int TERMINATION_MESSAGE_MAX_BYTES = 4096;

  private static final String RESULTS_TOO_LARGE_MESSAGE =
      "TaskRunResultTooLarge - Task has exceeded the maximum allowed "
          + TERMINATION_MESSAGE_MAX_BYTES
          + " byte size for Result Parameters. Pass large outputs by reference (workspace path or URI).";

  @Autowired protected KubeHelperService helperKubeService;

  @Autowired protected KubeServiceImpl kubeService;

  @Autowired protected TaskImageResolver imageResolver;

  @Autowired protected TaskResourceResolver resourceResolver;

  @Value("${kube.timeout.watchGraceMinutes}")
  private long watchGraceMinutes;

  @Value("${kube.timeout.reconcileSeconds}")
  private long reconcileSeconds;

  @Value("${kube.timeout.failedConditionGraceSeconds}")
  private long failedConditionGraceSeconds;

  @Value("${kube.timeout.startMinutes:15}")
  private long startMinutes;

  @Value("${kube.timeout.startFailureGraceSeconds:60}")
  private long startFailureGraceSeconds;

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

  private KubernetesClient client;

  public KubeJobsExecutor(KubernetesClient client) {
    this.client = client;
  }

  // Tests swap in the mock-server client after the context is up.
  public void setClient(KubernetesClient client) {
    this.client = client;
  }

  // The Job each task run in flight created or adopted, by task run id, from create until its
  // log has been read. Labels alone cannot find it: every attempt carries the same ones.
  private final Map<String, String> jobNames = new ConcurrentHashMap<>();

  // The Job controller stamps the Job's name on its pod; the task labels select every attempt's.
  private static Map<String, String> podLabels(String name) {
    return Map.of("job-name", name);
  }

  @Override
  public Optional<Map<String, String>> runtimePodSelector(String taskRunId) {
    return Optional.ofNullable(jobNames.get(taskRunId)).map(KubeJobsExecutor::podLabels);
  }

  @Override
  public void forgetRuntimeObject(String taskRunId) {
    jobNames.remove(taskRunId);
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

    // An attempt handed out again while its Job still runs (a dispatcher that stopped and let the
    // lease lapse) continues that Job; a finished one belongs to an earlier attempt.
    Job running =
        client.batch().v1().jobs().withLabels(taskLabels).list().getItems().stream()
            .filter(job -> !isFinished(job))
            .findFirst()
            .orElse(null);
    if (running != null) {
      LOGGER.info(
          "Adopting existing Job {} for TaskRun {}", running.getMetadata().getName(), taskRunRef);
      jobNames.put(taskRunRef, running.getMetadata().getName());
      return;
    }

    List<Volume> volumes = new ArrayList<>();
    List<VolumeMount> volumeMounts = new ArrayList<>();
    addDataVolume(volumes, volumeMounts, task.getParams());
    addWorkspaceVolume(volumes, volumeMounts, workflowRef, workflowRunRef, task.getWorkspace());

    List<String> containerCommand =
        addScriptOrCommand(
            volumes,
            volumeMounts,
            taskLabels,
            imageResolver.script(task),
            imageResolver.command(task));

    Container container = new Container();
    container.setName("task");
    container.setImage(imageResolver.image(task));
    container.setImagePullPolicy(kubeImagePullPolicy);
    container.setWorkingDir(spec.getWorkingDir());
    container.setArgs(imageResolver.arguments(task));
    container.setCommand(containerCommand);
    container.setEnv(
        helperKubeService.createTaskEnvVars(
            spec.getDebug(),
            task.getParams(),
            imageResolver.environment(task),
            helperKubeService.createEnvVar("RESULTS_PATH", "/dev/termination-log"),
            // The ceiling a task writes to, so it can budget its own payload rather than guess.
            helperKubeService.createEnvVar(
                "RESULTS_MAX_BYTES", String.valueOf(TERMINATION_MESSAGE_MAX_BYTES))));
    container.setVolumeMounts(volumeMounts);
    // Null when nothing is configured, so the container carries no resources block at all.
    container.setResources(resourceResolver.requirements());
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
        helperKubeService.getAnnotations(workflowRef));

    Job job = new Job();
    job.setMetadata(jobMeta);
    job.setSpec(jobSpec);

    Job result = client.batch().v1().jobs().resource(job).create();
    jobNames.put(taskRunRef, result.getMetadata().getName());
    // Name, namespace and image only: the pod spec carries the task's env values and the
    // filled-in script, any of which may be a secret.
    LOGGER.info(
        "Created Job {} in {} (image {})",
        result.getMetadata().getName(),
        result.getMetadata().getNamespace(),
        container.getImage());
  }

  // A Job is finished once it carries a true Complete or Failed condition, the same terminal
  // test the watch applies.
  private static boolean isFinished(Job job) {
    return job.getStatus() != null
        && job.getStatus().getConditions() != null
        && job.getStatus().getConditions().stream()
            .anyMatch(
                condition ->
                    ("Complete".equals(condition.getType()) || "Failed".equals(condition.getType()))
                        && "True".equals(condition.getStatus()));
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

  // The run's one workspace claim, mounted where the workspace says (blank means /workspace).
  private void addWorkspaceVolume(
      List<Volume> volumes,
      List<VolumeMount> volumeMounts,
      String workflowRef,
      String workflowRunRef,
      WorkflowWorkspace workspace) {
    if (workspace == null) {
      return;
    }
    String name = helperKubeService.getPrefixVol() + "-ws";
    PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSource();
    pvcSource.setClaimName(
        kubeService.getPVCName(
            helperKubeService.getWorkspaceLabels(workflowRef, workflowRunRef, null)));
    Volume volume = new Volume();
    volume.setName(name);
    volume.setPersistentVolumeClaim(pvcSource);
    volumes.add(volume);

    VolumeMount mount = new VolumeMount();
    mount.setName(name);
    mount.setMountPath(helperKubeService.workspaceMountPath(workspace));
    volumeMounts.add(mount);
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
    return watchJob(task, jobNames.get(task.getId()), timeoutMinutes);
  }

  /** Wait for the Job of that {@code name}, the one {@link #create} made or adopted. */
  private List<RunResult> watchJob(TaskRun task, String name, Long timeoutMinutes)
      throws InterruptedException {
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(
            task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());
    Map<String, String> podLabels = podLabels(String.valueOf(name));

    final CountDownLatch latch = new CountDownLatch(1);
    JobWatcher jobWatcher =
        new JobWatcher(latch, Duration.ofSeconds(failedConditionGraceSeconds), name);
    PodStartWatch podStartWatch =
        new PodStartWatch(
            Instant.now(), Duration.ofMinutes(startMinutes), Duration.ofSeconds(startFailureGraceSeconds));
    Watch watch = client.batch().v1().jobs().withLabels(taskLabels).watch(jobWatcher);

    try {
      // A backstop only, for the case where the Job's own deadline never reaches this watch. The
      // engine owns the deadline and reaps at the task budget plus a few seconds, so it always
      // acts first; this grace exists to release the thread and report, not to wait for
      // scheduling. Raise kube.timeout.watchGraceMinutes where image pulls are slow.
      Instant deadline = Instant.now().plus(Duration.ofMinutes(timeoutMinutes + watchGraceMinutes));
      while (!latch.await(reconcileSeconds, TimeUnit.SECONDS)) {
        if (Instant.now().isAfter(deadline)) {
          throw new TaskExecutionException(
              StatusReason.DEADLINE_EXCEEDED, "JobTimeout - Job timed out while waiting for completion.");
        }
        // The watch is the fast path; this poll is the reconcile that catches whatever the watch
        // missed - a dropped connection, or a state transition that happened before the watch was
        // established.
        // No name means no create recorded a Job for this attempt: nothing to wait for.
        Job job = (name != null) ? client.batch().v1().jobs().withName(name).get() : null;
        if (job == null) {
          jobWatcher.markDeleted();
        } else {
          jobWatcher.evaluate(job);
        }
        if (jobWatcher.isWatchLost()) {
          watch.close();
          watch = client.batch().v1().jobs().withLabels(taskLabels).watch(jobWatcher);
          jobWatcher.resetWatchLost();
        }
        if (latch.getCount() > 0 && !podStartWatch.hasStarted()) {
          TaskExecutionException startFailure =
              podStartWatch.check(helperKubeService.podStart(client, podLabels), Instant.now());
          if (startFailure != null) {
            // Nothing ran: remove the Job so a pod that cannot start stops holding capacity.
            client
                .batch()
                .v1()
                .jobs()
                .withLabels(taskLabels)
                .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                .delete();
            throw startFailure;
          }
        }
      }

      JobCondition condition = jobWatcher.getCondition();
      if (condition != null && "Complete".equals(condition.getType()) && "True".equals(condition.getStatus())) {
        LOGGER.info("Job completed successfully");
        return readResults(podLabels, task.getResults());
      }

      String reason = condition != null ? condition.getReason() : "Unknown";
      String message = condition != null ? condition.getMessage() : "Job did not report a terminal condition.";
      LOGGER.info("Task execution error. " + reason + " - " + message);
      // The container may have written its Result Parameters to the termination log before it
      // exited non-zero; carry them so a failed Task's output still reaches the Engine.
      List<RunResult> failureResults = readResults(podLabels, task.getResults());
      throw failureFor(reason, message, podLabels, failureResults);
    } catch (Exception e) {
      LOGGER.error(e.toString());
      throw e;
    } finally {
      watch.close();
      deleteConfigMaps(taskLabels);
    }
  }

  private TaskExecutionException failureFor(
      String reason, String message, Map<String, String> podLabels, List<RunResult> results) {
    // The status message is the Job's condition first, then the container's own account of the
    // failure (exit code, termination reason, log tail) while its pod can still be read.
    String detail = helperKubeService.failureDetail(client, podLabels);
    String text = (detail != null) ? reason + " - " + message + "\n" + detail : reason + " - " + message;
    if ("DeadlineExceeded".equals(reason)) {
      return new TaskExecutionException(StatusReason.DEADLINE_EXCEEDED, results, text);
    }
    if ("JobDeleted".equals(reason)) {
      return new TaskExecutionException(StatusReason.JOB_DELETED, results, text);
    }
    String podReason = helperKubeService.getPodFailureReason(client, podLabels);
    if (StatusReason.OOM_KILLED.equals(podReason)) {
      return new TaskExecutionException(StatusReason.OOM_KILLED, results, text);
    }
    if (StatusReason.IMAGE_PULL.equals(podReason)) {
      return new TaskExecutionException(StatusReason.IMAGE_PULL, results, text);
    }
    return new TaskExecutionException(StatusReason.JOB_FAILED, results, text);
  }

  /**
   * Delete any ConfigMaps created for the task (currently just the script ConfigMap for script
   * tasks). Label-selector delete is idempotent — safe to call even when none exist.
   */
  private void deleteConfigMaps(Map<String, String> taskLabels) {
    client.configMaps().withLabels(taskLabels).delete();
  }

  /*
   * Kubernetes truncates a termination message above TERMINATION_MESSAGE_MAX_BYTES and the
   * truncated prefix is broken JSON, so an oversize payload used to read as "no results" and the
   * task ended succeeded with every result missing and nothing said. The too-large check now runs
   * whether or not a message came back - truncation leaves one behind - and an unparseable
   * message at the ceiling fails the task with the same reason instead of being swallowed. A
   * short unparseable message is a task writing something that is not a results payload: it
   * carries no results, which is not a failure, and is logged rather than failed.
   */
  private List<RunResult> readResults(
      Map<String, String> podLabels, List<RunResult> declaredResults) {
    String message = getTerminationMessage(podLabels);
    if (kubeService.isTaskRunResultTooLarge(podLabels)) {
      throw new TaskExecutionException(StatusReason.RESULTS_TOO_LARGE, RESULTS_TOO_LARGE_MESSAGE);
    }
    Optional<List<RunResult>> parsed = TerminationMessageParser.parse(message, declaredResults);
    if (parsed.isPresent()) {
      return parsed.get();
    }
    if (message.length() >= TERMINATION_MESSAGE_MAX_BYTES) {
      throw new TaskExecutionException(StatusReason.RESULTS_TOO_LARGE, RESULTS_TOO_LARGE_MESSAGE);
    }
    LOGGER.warn(
        "Termination message is not a Result Parameter payload ({} bytes); no Results recorded.",
        message.length());
    return List.of();
  }

  private String getTerminationMessage(Map<String, String> podLabels) {
    List<Pod> pods = client.pods().withLabels(podLabels).list().getItems();
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

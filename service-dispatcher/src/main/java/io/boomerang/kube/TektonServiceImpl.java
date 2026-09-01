package io.boomerang.kube;

import com.fasterxml.jackson.core.type.TypeReference;
import io.boomerang.dispatcher.LeaseRegistry;
import io.boomerang.dispatcher.WorkspaceService;
import io.boomerang.common.enums.StorageType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Duration;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.v1.Param;
import io.fabric8.tekton.v1.ParamSpec;
import io.fabric8.tekton.v1.ParamValue;
import io.fabric8.tekton.v1.Step;
import io.fabric8.tekton.v1.TaskRun;
import io.fabric8.tekton.v1.TaskRunBuilder;
import io.fabric8.tekton.v1.TaskRunResult;
import io.fabric8.tekton.v1.WorkspaceBinding;
import io.fabric8.tekton.v1.WorkspaceDeclaration;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "tekton", matchIfMissing = true)
public class TektonServiceImpl implements TektonService, TaskExecutor {

  private static final Logger LOGGER = LogManager.getLogger(TektonServiceImpl.class);

  @Autowired protected KubeHelperService helperKubeService;

  @Autowired protected KubeServiceImpl kubeService;

  @Autowired private WorkspaceService workspaceService;

  protected static final Integer ONE_DAY_IN_SECONDS = 86400; // 60*60*24

  @Value("${kube.timeout.waitUntil}")
  protected long waitUntilTimeout;

  @Value("${kube.timeout.watchGraceMinutes}")
  protected long watchGraceMinutes;

  @Value("${kube.timeout.reconcileSeconds}")
  protected long reconcileSeconds;

  @Override
  public void create(io.boomerang.common.model.TaskRun task, Long timeoutMinutes)
      throws InterruptedException, ParseException {
    createTaskRun(
        task.getWorkflowRef(),
        task.getWorkflowRunRef(),
        task.getId(),
        task.getName(),
        task.getLabels(),
        task.getSpec().getImage(),
        task.getSpec().getCommand(),
        task.getSpec().getScript(),
        task.getSpec().getArguments(),
        task.getParams(),
        task.getSpec().getEnvs(),
        task.getResults(),
        task.getSpec().getWorkingDir(),
        task.getWorkspaces(),
        waitUntilTimeout,
        timeoutMinutes,
        task.getSpec().getDebug());
  }

  @Override
  public List<RunResult> watch(io.boomerang.common.model.TaskRun task, Long timeoutMinutes)
      throws InterruptedException {
    return watchTaskRun(
        task.getWorkflowRef(),
        task.getWorkflowRunRef(),
        task.getId(),
        task.getLabels(),
        timeoutMinutes);
  }

  @Override
  public void cancel(io.boomerang.common.model.TaskRun task) {
    cancelTaskRun(task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());
  }

  @Override
  public void delete(io.boomerang.common.model.TaskRun task) {
    deleteTaskRun(task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());
  }

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

  @Value("${kube.resource.limit.ephemeral-storage}")
  private String kubeResourceLimitEphemeralStorage;

  @Value("${kube.resource.request.ephemeral-storage}")
  private String kubeResourceRequestEphemeralStorage;

  @Value("${kube.resource.limit.memory}")
  private String kubeResourceLimitMemory;

  @Value("${kube.resource.request.memory}")
  private String kubeResourceRequestMemory;

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

  TektonClient client = null;

  public TektonServiceImpl(TektonClient client, LeaseRegistry leaseRegistry) {
    this.client = client;
    this.leaseRegistry = leaseRegistry;
  }

  // Tests swap in the mock-server client after the context is up.
  public void setClient(TektonClient client) {
    this.client = client;
  }

  @Override
  public TaskRun createTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      String taskName,
      Map<String, String> customLabels,
      String image,
      List<String> command,
      String script,
      List<String> arguments,
      List<RunParam> params,
      List<TaskEnvVar> envVars,
      List<RunResult> results,
      String workingDir,
      List<TaskWorkspace> workspaces,
      long waitSeconds,
      Long timeout,
      Boolean debug)
      throws InterruptedException, ParseException {

    LOGGER.info("Initializing Task...");

    /*
     * Define environment variables made up of
     * TODO: securityContext.setProcMount("Unmasked");
     *  - Only works with Kube 1.12 and above
     * TODO: determine if we need to do no network as an option
     */
    //    SecurityContext securityContext = new SecurityContext();
    //    securityContext.setPrivileged(true);

    /*
     * Create a resource request and limit for ephemeral-storage Defaults to application.properties,
     * can be overridden by user property.
     * Create a resource request and limit for memory Defaults to application.properties, can be
     * overridden by user property. Maximum of 32Gi.
     */
    //    ResourceRequirements resources = new ResourceRequirements();
    //    Map<String, Quantity> resourceRequests = new HashMap<>();
    //    resourceRequests.put("ephemeral-storage", new
    // Quantity(kubeResourceRequestEphemeralStorage));
    //    resourceRequests.put("memory", new Quantity(kubeResourceRequestMemory));
    //    Map<String, Quantity> resourceLimits = new HashMap<>();
    //    resourceLimits.put("ephemeral-storage", new Quantity(kubeResourceLimitEphemeralStorage));
    //    String kubeResourceLimitMemoryQuantity =
    // taskProperties.get("worker.resource.memory.size");
    //    if (kubeResourceLimitMemoryQuantity != null &&
    // !(Integer.valueOf(kubeResourceLimitMemoryQuantity.replace("Gi", "")) > 32)) {
    //      LOGGER.info("Setting Resource Memory Limit to " + kubeResourceLimitMemoryQuantity +
    // "...");
    //      resourceLimits.put("memory", new Quantity(kubeResourceLimitMemoryQuantity));
    //    } else {
    //      LOGGER
    //          .info("Setting Resource Memory Limit to default of: " + kubeResourceLimitMemory + "
    // ...");
    //      resourceLimits.put("memory", new Quantity(kubeResourceLimitMemory));
    //    }
    //    resources.setLimits(resourceLimits);

    /*
     * Create Workspaces and PVCs
     * - /workspace for cross workflow persistence such as caches (optional if mounted prior)
     * - /workflow for workflow based sharing between tasks (optional if mounted prior)
     * - TODO: determine if optional=true works better than checking if the PVC exists
     * - TODO: migrate /data to workspaces
     */
    List<WorkspaceDeclaration> taskSpecWorkspaces = new ArrayList<>();
    List<WorkspaceBinding> taskWorkspaces = new ArrayList<>();
    if (workspaces != null && !workspaces.isEmpty()) {
      workspaces.forEach(
          ws -> {
            // Based on the Workspace Type we set the workspaceRef to be the WorkflowRef or the
            // WorkflowRunRef
            String workspaceRef =
                workspaceService.getWorkspaceRef(ws.getType(), workflowId, workflowActivityId);
            //        boolean pvcExists =
            //            kubeService.checkWorkspacePVCExists(workspaceRef, ws.getType(), false);
            //        if (pvcExists) {
            if (StorageType.fromLabel(ws.getType()).isPresent()) {
              WorkspaceDeclaration wsWorkspaceDeclaration = new WorkspaceDeclaration();
              wsWorkspaceDeclaration.setName(
                  helperKubeService.getPrefixVol() + "-ws-" + ws.getType());
              String mountPath =
                  ws.getMountPath() != null && !ws.getMountPath().isEmpty()
                      ? ws.getMountPath()
                      : "/workspace/" + ws.getType();
              wsWorkspaceDeclaration.setMountPath(mountPath);
              String description =
                  (StorageType.fromLabel(ws.getType()).orElse(null) == StorageType.workflow)
                      ? "Storage for a workflow across execution"
                      : "Storage for the specific workflow execution";
              wsWorkspaceDeclaration.setDescription(description);
              wsWorkspaceDeclaration.setOptional(ws.isOptional());
              taskSpecWorkspaces.add(wsWorkspaceDeclaration);

              PersistentVolumeClaimVolumeSource wsPVCVolumeSource =
                  new PersistentVolumeClaimVolumeSource();
              wsPVCVolumeSource.setClaimName(
                  kubeService.getPVCName(
                      helperKubeService.getWorkspaceLabels(
                          workflowId, workspaceRef, ws.getType(), null)));

              WorkspaceBinding wsWorkspaceBinding = new WorkspaceBinding();
              wsWorkspaceBinding.setName(helperKubeService.getPrefixVol() + "-ws-" + ws.getType());
              wsWorkspaceBinding.setPersistentVolumeClaim(wsPVCVolumeSource);
              taskWorkspaces.add(wsWorkspaceBinding);
            } else {
              LOGGER.warn(
                  "Skipping Workspace ("
                      + ws.getName()
                      + ") as we don't support custom workspaces yet.");
            }
          });
    }

    /*
     * The following code is integrated to the helm chart and CICD properties It allows for
     * containers that breach the standard ephemeral-storage size by off-loading to memory See:
     * https://kubernetes.io/docs/concepts/storage/volumes/#emptydir
     *
     * Create volumes and Volume Mounts
     * - /data for task storage (optional - needed if using in memory storage)
     */
    List<VolumeMount> volumeMounts = new ArrayList<>();
    List<Volume> volumes = new ArrayList<>();

    VolumeMount dataVolumeMount = new VolumeMount();
    dataVolumeMount.setName(helperKubeService.getPrefixVol() + "-data");
    dataVolumeMount.setMountPath("/data");
    volumeMounts.add(dataVolumeMount);

    Volume dataVolume = new Volume();
    dataVolume.setName(helperKubeService.getPrefixVol() + "-data");
    EmptyDirVolumeSource dataEmptyDirVolumeSource = new EmptyDirVolumeSource();

    Object value = null;
    Optional<RunParam> param =
        params.stream().filter(p -> "worker.storage.data.memory".equals(p.getName())).findFirst();
    if (param.isPresent()) {
      value = param.get().getValue();
    }
    if (kubeTaskStorageDataMemory && value != null && Boolean.valueOf((boolean) value)) {
      LOGGER.info("Setting data to in memory storage...");
      dataEmptyDirVolumeSource.setMedium("Memory");
    }
    dataVolume.setEmptyDir(dataEmptyDirVolumeSource);
    volumes.add(dataVolume);

    /*
     * Configure Node Selector and Tolerations if defined
     */
    List<Toleration> tolerations = new ArrayList<>();
    Map<String, String> nodeSelectors = new HashMap<>();
    if (kubeWorkerNodeSelector != null && !kubeWorkerNodeSelector.isEmpty()) {
      LOGGER.info(kubeWorkerNodeSelector.toString());
      kubeWorkerNodeSelector.forEach(
          (k, v) -> {
            LOGGER.info("Adding node selector: " + k + "=" + v);
            nodeSelectors.put(k, v);
          });
    }
    LOGGER.info("Finalized Node Selectors: " + nodeSelectors.toString());
    if (kubeWorkerTolerations != null
        && !kubeWorkerTolerations.isEmpty()
        && !"null".equalsIgnoreCase(kubeWorkerTolerations)) {
      LOGGER.info(kubeWorkerTolerations.toString());
      tolerations =
          Serialization.unmarshal(kubeWorkerTolerations, new TypeReference<List<Toleration>>() {});

      //      kubeWorkerTolerations.forEach(t -> {
      //        LOGGER.info("Adding toleration: " + t);
      //        tolerations.add(t);
      //      });
    }
    LOGGER.info("Finalized Tolerations: " + tolerations.toString());

    /*
     * Create Host Aliases if defined
     */
    List<HostAlias> hostAliases = new ArrayList<>();
    if (!kubeWorkerHostAliases.isEmpty()) {
      hostAliases =
          Serialization.unmarshal(kubeWorkerHostAliases, new TypeReference<List<HostAlias>>() {});
    }

    /*
     * Define Image Pull Secrets
     */
    LocalObjectReference imagePullSecret = new LocalObjectReference();
    imagePullSecret.setName(kubeImagePullSecret);
    List<LocalObjectReference> imagePullSecrets = new ArrayList<>();
    imagePullSecrets.add(imagePullSecret);

    List<EnvVar> tknEnvVars =
        helperKubeService.createTaskEnvVars(
            debug,
            params,
            envVars,
            helperKubeService.createEnvVar("RESULTS_PATH", "/tekton/results"));

    /*
     * Define Task Params and Task Spec Params
     */
    List<ParamSpec> taskSpecParams = new ArrayList<>();
    List<Param> taskParams = new ArrayList<>();
    params.forEach(
        p -> {
          ParamSpec taskSpecParam = new ParamSpec();
          taskSpecParam.setName(p.getName());
          taskSpecParam.setType("string");
          taskSpecParams.add(taskSpecParam);
          Param taskParam = new Param();
          taskParam.setName(p.getName());
          taskParam.setValue(new ParamValue(helperKubeService.paramValueAsString(p.getValue())));
          taskParams.add(taskParam);
        });

    /*
     * Create the main task container
     * Notes:
     *  - If script != null or empty then don't add command (Ref: https://github.com/tektoncd/pipeline/blob/main/docs/tasks.md#running-scripts-within-steps)
     */
    List<Step> taskSteps = new ArrayList<>();
    Step taskStep = new Step();
    taskStep.setName("task");
    taskStep.setImage(image);
    if (script != null && !script.isEmpty()) {
      taskStep.setScript(script);
    } else if (command != null && !command.isEmpty()) {
      taskStep.setCommand(command);
    }
    taskStep.setImagePullPolicy(kubeImagePullPolicy);
    taskStep.setArgs(arguments);
    taskStep.setEnv(tknEnvVars);
    taskStep.setVolumeMounts(volumeMounts);
    taskStep.setWorkingDir(workingDir);
    //    taskStep.setSecurityContext(securityContext);
    //    taskContainer.setResources(resources);
    taskSteps.add(taskStep);

    /*
     * Create the additional PodTemplate based controls
     * TODO: figure out if volumes go here or on the TaskRunSpec
     */
    //    Template taskPodTemplate = new Template();
    //    taskPodTemplate.setNodeSelector(nodeSelectors);
    //    taskPodTemplate.setTolerations(tolerations);
    //    taskPodTemplate.setImagePullSecrets(imagePullSecrets);
    //    taskPodTemplate.setHostAliases(hostAliases);
    ////    taskPodTemplate.setVolumes(volumes);
    //
    //    LOGGER.info(taskPodTemplate);

    /*
     * Define TaskResults and copy from internal model
     */
    List<io.fabric8.tekton.v1.TaskResult> tknTaskResults = new ArrayList<>();
    if (results != null) {
      results.forEach(
          result -> {
            io.fabric8.tekton.v1.TaskResult tknTaskResult = new io.fabric8.tekton.v1.TaskResult();
            tknTaskResult.setName(result.getName());
            tknTaskResult.setDescription(result.getDescription());
            tknTaskResults.add(tknTaskResult);
          });
    }

    Duration taskTimeout = Duration.parse(timeout + "mins");

    /*
     * Build out TaskRun definition.
     * - Optionally loads Node Selector and Tolerations
     * TODO: determine how to make Task Workspace work. Currently if using the local-path (bound to a particular node)
     * the Task doesn't find a node to allow it to run. It cant handle the standard Pod method of determine if all its
     * volumes can be satisfied
     * Notes:
     * - Parameters passed into the TaskRun MUST be parameters on the task
     */
    TaskRun taskRun =
        new TaskRunBuilder()
            .withNewMetadata()
            .withGenerateName(helperKubeService.getPrefixTask() + "-" + taskActivityId + "-")
            .withLabels(
                helperKubeService.getTaskLabels(
                    workflowId, workflowActivityId, taskActivityId, customLabels))
            .withAnnotations(
                helperKubeService.getAnnotations(
                    "task", workflowId, workflowActivityId, taskActivityId))
            .endMetadata()
            .withNewSpec()
            //      .withPodTemplate(taskPodTemplate)
            .withNewPodTemplate()
            // Same deployment-wide isolation setting the Kubernetes Jobs executor honours
            // (dispatcher.tasks.runtimeClassName); null leaves the field off the pod template.
            .withRuntimeClassName(
                kubeWorkerRuntimeClassName != null && !kubeWorkerRuntimeClassName.isBlank()
                    ? kubeWorkerRuntimeClassName
                    : null)
            .addToNodeSelector(nodeSelectors)
            .addAllToTolerations(tolerations)
            .addAllToImagePullSecrets(imagePullSecrets)
            .addAllToHostAliases(hostAliases)
            .endPodTemplate()
            .withParams(taskParams)
            .withWorkspaces(taskWorkspaces)
            .withTimeout(taskTimeout)
            //      .withServiceAccountName(workerProperties.getServiceaccount())
            .withNewTaskSpec()
            .withWorkspaces(taskSpecWorkspaces)
            .withParams(taskSpecParams)
            .withResults(tknTaskResults)
            .withVolumes(volumes)
            .withSteps(taskSteps)
            .endTaskSpec()
            .endSpec()
            .build();

    LOGGER.info(taskRun);

    TaskRun result = client.v1().taskRuns().resource(taskRun).create();

    //    client.v1().taskRuns().withLabels(helperKubeService.getTaskLabels(workflowId,
    // workflowActivityId, taskId, taskActivityId, customLabels)).waitUntilReady(waitSeconds,
    // TimeUnit.SECONDS);

    return result;
  }

  @Override
  public List<RunResult> watchTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels,
      Long timeout)
      throws InterruptedException {
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(workflowId, workflowActivityId, taskActivityId, customLabels);

    final CountDownLatch latch = new CountDownLatch(1);
    Condition condition;
    List<TaskRunResult> tknResults;

    TaskWatcher taskWatcher = new TaskWatcher(latch);
    Watch watch = client.v1().taskRuns().withLabels(taskLabels).watch(taskWatcher);

    try {
      leaseRegistry.beat(taskActivityId);
      // A backstop only, for the case where Tekton's own timeout interrupt never reaches this
      // watch. The engine owns the deadline and reaps at the task budget plus a few seconds, so it
      // always acts first; this grace exists to release the thread and report, not to wait for
      // provisioning. Raise kube.timeout.watchGraceMinutes where image pulls are slow.
      Instant deadline = Instant.now().plus(java.time.Duration.ofMinutes(timeout + watchGraceMinutes));
      while (!latch.await(reconcileSeconds, TimeUnit.SECONDS)) {
        leaseRegistry.beat(taskActivityId);
        if (Instant.now().isAfter(deadline)) {
          throw new TaskExecutionException(
              "DeadlineExceeded", "TaskRunTimeout - Task timed out while waiting for completion.");
        }
        // The watch is the fast path; this poll is the reconcile that catches whatever the watch
        // missed - a dropped connection, or a state transition that happened before the watch was
        // established.
        List<TaskRun> taskRuns = client.v1().taskRuns().withLabels(taskLabels).list().getItems();
        if (taskRuns.isEmpty()) {
          taskWatcher.markDeleted();
        } else {
          taskWatcher.evaluate(taskRuns.get(0));
        }
        if (taskWatcher.isWatchLost()) {
          watch.close();
          watch = client.v1().taskRuns().withLabels(taskLabels).watch(taskWatcher);
          taskWatcher.resetWatchLost();
        }
      }

      condition = taskWatcher.getCondition();
      tknResults = taskWatcher.getResults();

      if (condition != null && "True".equals(condition.getStatus())) {
        LOGGER.info("Task completed successfully");
        return toRunResults(tknResults);
      }

      String reason = condition != null ? condition.getReason() : "Unknown";
      String message =
          condition != null ? condition.getMessage() : "TaskRun did not report a terminal condition.";
      LOGGER.info("Task execution error. " + reason + " - " + message);
      // The Step may have written its Result Parameters to the termination message before the
      // container exited non-zero; carry them so a failed Task's output still reaches the Engine.
      throw failureFor(reason, message, taskLabels, toRunResults(tknResults));
    } catch (Exception e) {
      LOGGER.error(e.toString());
      throw e;
    } finally {
      watch.close();
      leaseRegistry.remove(taskActivityId);
    }
  }

  // Package-private so TektonServiceImplTest can pin the conversion directly.
  static List<RunResult> toRunResults(List<TaskRunResult> tknResults) {
    List<RunResult> results = new ArrayList<>();
    tknResults.forEach(
        tr -> {
          // Tekton v1 result values are ParamValue wrappers; tasks emit string results.
          results.add(
              new RunResult(
                  tr.getName(), (tr.getValue() != null) ? tr.getValue().getStringVal() : null));
        });
    return results;
  }

  private TaskExecutionException failureFor(
      String reason, String message, Map<String, String> taskLabels, List<RunResult> results) {
    if ("DeadlineExceeded".equals(reason) || "TaskRunTimeout".equals(reason)) {
      return new TaskExecutionException("DeadlineExceeded", results, reason + " - " + message);
    }
    if ("JobDeleted".equals(reason)) {
      return new TaskExecutionException("JobDeleted", results, reason + " - " + message);
    }
    if (kubeService.isTaskRunResultTooLarge(taskLabels)) {
      return new TaskExecutionException("ResultsTooLarge", results, "TaskRunResultTooLarge - Task has exceeded the maximum allowed 4096 byte size for Result Parameters.");
    }
    String podReason = helperKubeService.getPodFailureReason(client.adapt(KubernetesClient.class), taskLabels);
    if ("OOMKilled".equals(podReason)) {
      return new TaskExecutionException("OOMKilled", results, reason + " - " + message);
    }
    if ("ImagePull".equals(podReason)) {
      return new TaskExecutionException("ImagePull", results, reason + " - " + message);
    }
    return new TaskExecutionException("JobFailed", results, reason + " - " + message);
  }

  @Override
  public void deleteTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {

    LOGGER.debug("Deleting Task...");

    client
        .v1()
        .taskRuns()
        .withLabels(
            helperKubeService.getTaskLabels(
                workflowId, workflowActivityId, taskActivityId, customLabels))
        .withPropagationPolicy(DeletionPropagation.BACKGROUND)
        .delete();
  }

  /*
   * Cancel a TaskRun
   *
   * The implementation needs to replace old conditions with the single status condition to be added.
   * Without this, you will receive back a "Not all Steps in the Task have finished executing" message
   *
   * Reference(s):
   * - https://github.com/abayer/tektoncd-pipeline/blob/0.8.0-jx-support-backwards-incompats/pkg/reconciler/taskrun/cancel.go
   */
  @Override
  public void cancelTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {
    Map<String, String> labels =
        helperKubeService.getTaskLabels(
            workflowId, workflowActivityId, taskActivityId, customLabels);

    LOGGER.info("Cancelling Task with labels: " + labels.toString());

    List<TaskRun> taskRuns = client.v1().taskRuns().withLabels(labels).list().getItems();

    if (taskRuns != null && !taskRuns.isEmpty()) {
      TaskRun taskRun = taskRuns.get(0);

      List<Condition> taskRunConditions = new ArrayList<>();
      Condition taskRunCancelCondition = new Condition();
      taskRunCancelCondition.setType("Succeeded");
      taskRunCancelCondition.setStatus("False");
      taskRunCancelCondition.setReason("TaskRunCancelled");
      taskRunCancelCondition.setMessage("The TaskRun was cancelled successfully.");
      taskRunConditions.add(taskRunCancelCondition);

      taskRun.getStatus().setConditions(taskRunConditions);

      client.v1().taskRuns().resource(taskRun).updateStatus();
    } else if (taskRuns != null && taskRuns.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.TASK_EXECUTION_ERROR,
          "CANCEL_FAILURE - No tasks found matching the lables: " + labels.toString());
    } else {
      throw new BoomerangException(
          BoomerangError.TASK_EXECUTION_ERROR,
          "CANCEL_FAILURE - Unknown error attempting to cancel task.");
    }
  }
}

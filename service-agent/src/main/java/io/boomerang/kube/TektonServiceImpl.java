package io.boomerang.kube;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.boomerang.agent.WorkspaceService;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.fabric8.knative.internal.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.ConfigMapProjection;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Duration;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.ProjectedVolumeSource;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeProjection;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.tekton.client.DefaultTektonClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.pipeline.v1beta1.ArrayOrString;
import io.fabric8.tekton.pipeline.v1beta1.Param;
import io.fabric8.tekton.pipeline.v1beta1.ParamSpec;
import io.fabric8.tekton.pipeline.v1beta1.Step;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunBuilder;
import io.fabric8.tekton.pipeline.v1beta1.TaskRunResult;
import io.fabric8.tekton.pipeline.v1beta1.WorkspaceBinding;
import io.fabric8.tekton.pipeline.v1beta1.WorkspaceDeclaration;
import java.lang.reflect.Type;
import java.text.ParseException;
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
import org.springframework.stereotype.Component;

@Component
public class TektonServiceImpl implements TektonService {

  private static final Logger LOGGER = LogManager.getLogger(TektonServiceImpl.class);

  @Autowired protected KubeHelperService helperKubeService;

  @Autowired protected KubeServiceImpl kubeService;

  @Autowired private WorkspaceService workspaceService;

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

  @Value("${agent.tasks.serviceaccount}")
  private String kubeWorkerServiceAccount;

  @Value("${agent.tasks.hostaliases}")
  private String kubeWorkerHostAliases;

  @Value("#{${agent.tasks.nodeselector}}")
  private Map<String, String> kubeWorkerNodeSelector;

  @Value("${agent.tasks.tolerations}")
  private String kubeWorkerTolerations;

  TektonClient client = null;

  public TektonServiceImpl() {
    this.client = new DefaultTektonClient();
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
            if ("workflow".equalsIgnoreCase(ws.getType())
                || "workflowrun".equalsIgnoreCase(ws.getType())) {
              WorkspaceDeclaration wsWorkspaceDeclaration = new WorkspaceDeclaration();
              wsWorkspaceDeclaration.setName(
                  helperKubeService.getPrefixVol() + "-ws-" + ws.getType());
              String mountPath =
                  ws.getMountPath() != null && !ws.getMountPath().isEmpty()
                      ? ws.getMountPath()
                      : "/workspace/" + ws.getType();
              wsWorkspaceDeclaration.setMountPath(mountPath);
              String description =
                  "workflow".equals(ws.getType())
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
     * - /props for mounting config_maps @deprecated
     * - /params for mounting taskrun params as files
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
     * Creation of projected volume to mount task params
     */
    VolumeMount propsVolumeMount = new VolumeMount();
    propsVolumeMount.setName(helperKubeService.getPrefixVol() + "-params");
    propsVolumeMount.setMountPath("/params");
    volumeMounts.add(propsVolumeMount);

    Volume propsVolume = new Volume();
    propsVolume.setName(helperKubeService.getPrefixVol() + "-params");
    ProjectedVolumeSource projectedVolPropsSource = new ProjectedVolumeSource();
    List<VolumeProjection> projectPropsVolumeList = new ArrayList<>();
    VolumeProjection taskCMVolumeProjection = new VolumeProjection();
    ConfigMapProjection projectedTaskConfigMap = new ConfigMapProjection();
    projectedTaskConfigMap.setName(
        kubeService.getConfigMapName(
            helperKubeService.getTaskLabels(
                workflowId, workflowActivityId, taskActivityId, customLabels)));
    taskCMVolumeProjection.setConfigMap(projectedTaskConfigMap);
    projectPropsVolumeList.add(taskCMVolumeProjection);
    projectedVolPropsSource.setSources(projectPropsVolumeList);
    propsVolume.setProjected(projectedVolPropsSource);
    volumes.add(propsVolume);

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
      Type listTolerationsType = new TypeToken<List<Toleration>>() {}.getType();
      tolerations = new Gson().fromJson(kubeWorkerTolerations, listTolerationsType);

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
      Type listHostAliasType = new TypeToken<List<HostAlias>>() {}.getType();
      hostAliases = new Gson().fromJson(kubeWorkerHostAliases, listHostAliasType);
    }

    /*
     * Define Image Pull Secrets
     */
    LocalObjectReference imagePullSecret = new LocalObjectReference();
    imagePullSecret.setName(kubeImagePullSecret);
    List<LocalObjectReference> imagePullSecrets = new ArrayList<>();
    imagePullSecrets.add(imagePullSecret);

    /*
     * Define environment variables made up of
     * - Proxy (if enabled)
     * - Boomerang Flow
     * - Debug and CI
     * - Task defined
     */
    List<EnvVar> tknEnvVars = new ArrayList<>();
    tknEnvVars.addAll(helperKubeService.createProxyEnvVars());
    //    @deprecated - no need to create default params. Can be provided if needed.
    //    tknEnvVars.addAll(helperKubeService.createEnvVars(workflowId, workflowActivityId,
    // taskName, taskActivityId));
    tknEnvVars.add(helperKubeService.createEnvVar("DEBUG", debug.toString()));
    tknEnvVars.add(helperKubeService.createEnvVar("CI", "true"));
    if (envVars != null) {
      envVars.forEach(
          var -> {
            tknEnvVars.add(helperKubeService.createEnvVar(var.getName(), var.getValue()));
          });
    }

    /*
     * Define Task Params and Task Spec Params
     * Additionally default an environment variable for the Task Param prefixed with PARAM_
     * TODO: change this to handle objects and Params easier.
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
          ArrayOrString valueString = new ArrayOrString();
          valueString.setStringVal((String) p.getValue());
          taskParam.setValue(valueString);
          taskParams.add(taskParam);
          //      TODO Determine if we need this.
          //      envVars.add(helperKubeService.createEnvVar("PARAM_" + key.toUpperCase(), value));
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
    List<io.fabric8.tekton.pipeline.v1beta1.TaskResult> tknTaskResults = new ArrayList<>();
    if (results != null) {
      results.forEach(
          result -> {
            tknTaskResults.add(
                new io.fabric8.tekton.pipeline.v1beta1.TaskResult(
                    result.getDescription(), result.getName()));
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

    TaskRun result = client.v1beta1().taskRuns().create(taskRun);

    //    client.v1beta1().taskRuns().withLabels(helperKubeService.getTaskLabels(workflowId,
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
    final CountDownLatch latch = new CountDownLatch(1);
    Condition condition = null;
    List<TaskRunResult> tknResults = new ArrayList<TaskRunResult>();

    TaskWatcher taskWatcher = new TaskWatcher(latch);

    try (Watch ignore =
        client
            .v1beta1()
            .taskRuns()
            .withLabels(
                helperKubeService.getTaskLabels(
                    workflowId, workflowActivityId, taskActivityId, customLabels))
            .watch(taskWatcher)) {

      // TODO is there a way to wait 3 minutes and check if the task
      // has moved from initial state. If its still in initial state then check PVC
      // PVC might have Event / Condition "ProvisioningFailed" with a reason.

      // Timeout is 10 minutes more than the TaskRun to account for delays in provisioning etc.
      // Note:
      // - The TaskRun Timeout will trigger an interrupt which enters this block as well
      boolean taskComplete = latch.await(timeout + 10, TimeUnit.MINUTES);
      if (!taskComplete) {
        throw new BoomerangException(
            BoomerangError.TASK_EXECUTION_ERROR,
            "TaskRunTimeout - Task timed out while waiting for completion.");
      }

      condition = taskWatcher.getCondition();
      tknResults = taskWatcher.getResults();

      if (condition != null && "True".equals(condition.getStatus())) {
        LOGGER.info("Task completed successfully");
      } else {
        LOGGER.info(
            "Task execution error. " + condition.getReason() + " - " + condition.getMessage());
        if (kubeService.isTaskRunResultTooLarge(
            helperKubeService.getTaskLabels(
                workflowId, workflowActivityId, taskActivityId, customLabels))) {
          throw new BoomerangException(
              BoomerangError.TASK_EXECUTION_ERROR,
              "TaskRunResultTooLarge - Task has exceeded the maximum allowed 4096 byte size for Result Parameters.");
        } else {
          throw new BoomerangException(
              BoomerangError.TASK_EXECUTION_ERROR,
              condition.getReason() + " - " + condition.getMessage());
        }
      }

    } catch (Exception e) {
      LOGGER.error(e.toString());
      throw e;
    }

    List<RunResult> results = new ArrayList<>();
    tknResults.forEach(
        tr -> {
          results.add(new RunResult(tr.getName(), tr.getValue()));
        });

    return results;
  }

  @Override
  public void deleteTaskRun(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {

    LOGGER.debug("Deleting Task...");

    client
        .v1beta1()
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

    List<TaskRun> taskRuns = client.v1beta1().taskRuns().withLabels(labels).list().getItems();

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

      client.v1beta1().taskRuns().replaceStatus(taskRun);
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

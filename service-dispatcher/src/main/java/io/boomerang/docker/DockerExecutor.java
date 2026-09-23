package io.boomerang.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Volume;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.dispatcher.LeaseRegistry;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.executor.TaskResourceResolver;
import io.boomerang.executor.TerminationMessageParser;
import io.boomerang.kube.KubeHelperService;
import io.fabric8.kubernetes.api.model.EnvVar;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs Tasks as containers on one Docker host, so Flow runs end to end with no Kubernetes. The
 * container receives the same environment as the Kubernetes runtimes ({@link
 * KubeHelperService#createTaskEnvVars}) and writes its Result Parameters to {@code RESULTS_PATH},
 * here a single file on the container's own filesystem that is copied back out once it exits.
 *
 * <p>Docker has no deadline of its own, so this executor enforces the Task's timeout itself: it
 * stops the container and reports {@code DeadlineExceeded}. There is no node selector, toleration,
 * host alias or runtime class on a Docker host - a different isolation tier is a different
 * dispatcher deployment. Sizing is shared with them: {@link TaskResourceResolver} turns the same
 * {@code kube.resource.limit.memory} and {@code kube.resource.limit.cpu} into the byte and
 * nano-CPU counts Docker takes, and ephemeral-storage has no meaning here.
 */
@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "docker")
public class DockerExecutor implements TaskExecutor {

  private static final Logger LOGGER = LogManager.getLogger(DockerExecutor.class);

  /**
   * One file on the container's writable layer. It cannot live under /dev (a tmpfs that is gone
   * once the container stops) or on a mounted volume, because it is read back with a copy-out from
   * the exited container.
   */
  static final String RESULTS_PATH = "/results.json";

  static final String SCRIPT_PATH = "/scripts/script";

  private static final int STOP_GRACE_SECONDS = 10;

  @Value("${kube.image.pullPolicy}")
  private String imagePullPolicy;

  @Value("${kube.task.storage.data.memory}")
  private Boolean taskStorageDataMemory;

  @Value("${dispatcher.docker.pollSeconds}")
  private long pollSeconds;

  private final DockerClient client;

  private final KubeHelperService helperKubeService;

  private final DockerWorkspaceStore workspaceStore;

  private final LeaseRegistry leaseRegistry;

  private final TaskImageResolver imageResolver;

  private final TaskResourceResolver resourceResolver;

  public DockerExecutor(
      DockerClient client,
      KubeHelperService helperKubeService,
      DockerWorkspaceStore workspaceStore,
      LeaseRegistry leaseRegistry,
      TaskImageResolver imageResolver,
      TaskResourceResolver resourceResolver) {
    this.client = client;
    this.helperKubeService = helperKubeService;
    this.workspaceStore = workspaceStore;
    this.leaseRegistry = leaseRegistry;
    this.imageResolver = imageResolver;
    this.resourceResolver = resourceResolver;
  }

  @Override
  public void create(TaskRun task, Long timeoutMinutes) throws InterruptedException {
    LOGGER.info("Initializing Task...");

    Map<String, String> taskLabels = taskLabels(task);
    List<Container> existing = containers(taskLabels);
    if (!existing.isEmpty()) {
      LOGGER.info("Adopting existing container {} for TaskRun {}", existing.get(0).getId(), task.getId());
      return;
    }

    TaskRunSpec spec = task.getSpec();
    // Image, command and script come from the resolver, not the spec: an `ai` task carries none of
    // them and the dispatcher supplies the worker image for it, exactly as the Kubernetes
    // executors do (TaskImageResolver).
    String image = imageResolver.image(task);
    pullImage(image);

    String script = imageResolver.script(task);
    List<String> entrypoint =
        (script != null && !script.isBlank()) ? List.of(SCRIPT_PATH) : imageResolver.command(task);

    CreateContainerCmd command =
        client
            .createContainerCmd(image)
            .withName(helperKubeService.getPrefixTask() + "-" + task.getId())
            .withLabels(taskLabels)
            .withEnv(environment(task))
            .withHostConfig(hostConfig(task));
    if (spec.getWorkingDir() != null && !spec.getWorkingDir().isBlank()) {
      command.withWorkingDir(spec.getWorkingDir());
    }
    if (entrypoint != null && !entrypoint.isEmpty()) {
      command.withEntrypoint(entrypoint);
    }
    if (spec.getArguments() != null && !spec.getArguments().isEmpty()) {
      command.withCmd(spec.getArguments());
    }

    String containerId;
    try {
      containerId = command.exec().getId();
    } catch (NotFoundException e) {
      // The only thing create can fail to find is the image itself.
      throw new TaskExecutionException("ImagePull", "IMAGE_NOT_FOUND - " + image);
    }
    if (script != null && !script.isBlank()) {
      copyScript(containerId, script);
    }
    client.startContainerCmd(containerId).exec();
    LOGGER.info("Started container {} for TaskRun {}", containerId, task.getId());
  }

  /**
   * The env the Kubernetes runtimes build, as Docker's {@code NAME=value} strings. The results
   * channel is the one difference between runtimes.
   */
  private List<String> environment(TaskRun task) {
    List<EnvVar> envVars =
        helperKubeService.createTaskEnvVars(
            task.getSpec().getDebug(),
            task.getParams(),
            task.getSpec().getEnvs(),
            helperKubeService.createEnvVar("RESULTS_PATH", RESULTS_PATH));
    return envVars.stream().map(var -> var.getName() + "=" + Optional.ofNullable(var.getValue()).orElse("")).toList();
  }

  private HostConfig hostConfig(TaskRun task) {
    HostConfig hostConfig = HostConfig.newHostConfig().withBinds(workspaceBinds(task));
    if (Boolean.TRUE.equals(taskStorageDataMemory) && isDataInMemory(task.getParams())) {
      LOGGER.info("Setting data to in memory storage...");
      hostConfig.withTmpFs(Map.of("/data", "rw"));
    }
    // The same kube.resource.limit.* values the Kubernetes executors apply, as the byte and
    // nano-CPU counts Docker takes. Unset stays unset - Docker then imposes no limit.
    Long memoryBytes = resourceResolver.memoryLimitBytes();
    if (memoryBytes != null) {
      hostConfig.withMemory(memoryBytes);
    }
    Long cpuNanos = resourceResolver.cpuLimitNanos();
    if (cpuNanos != null) {
      hostConfig.withNanoCPUs(cpuNanos);
    }
    return hostConfig;
  }

  /**
   * A task mounts only the workspaces it declares. /data needs no mount: a container's own writable
   * layer is already the per-task scratch space a Kubernetes emptyDir provides.
   */
  private List<Bind> workspaceBinds(TaskRun task) {
    List<Bind> binds = new ArrayList<>();
    Optional.ofNullable(task.getWorkspaces()).orElse(List.of()).forEach(workspace -> {
      String type = workspace.getType();
      if (!"workflow".equalsIgnoreCase(type) && !"workflowrun".equalsIgnoreCase(type)) {
        LOGGER.warn("Skipping Workspace (" + workspace.getName() + ") as we don't support custom workspaces yet.");
        return;
      }
      String workspaceRef =
          "workflow".equalsIgnoreCase(type) ? task.getWorkflowRef() : task.getWorkflowRunRef();
      binds.add(
          new Bind(workspaceStore.volumeNameFor(workspaceRef, type), new Volume(mountPath(workspace, type))));
    });
    return binds;
  }

  private String mountPath(TaskWorkspace workspace, String type) {
    return (workspace.getMountPath() != null && !workspace.getMountPath().isEmpty())
        ? workspace.getMountPath()
        : "/workspace/" + type;
  }

  private boolean isDataInMemory(List<RunParam> params) {
    return Optional.ofNullable(params).orElse(List.<RunParam>of()).stream()
        .filter(param -> "worker.storage.data.memory".equals(param.getName()))
        .map(RunParam::getValue)
        .anyMatch(value -> Boolean.parseBoolean(String.valueOf(value)));
  }

  private void pullImage(String image) throws InterruptedException {
    if ("Never".equalsIgnoreCase(imagePullPolicy)) {
      return;
    }
    if ("IfNotPresent".equalsIgnoreCase(imagePullPolicy) && imagePresent(image)) {
      return;
    }
    LOGGER.info("Pulling image {}", image);
    int tagAt = image.lastIndexOf(':');
    int slashAt = image.lastIndexOf('/');
    String repository = (tagAt > slashAt) ? image.substring(0, tagAt) : image;
    String tag = (tagAt > slashAt) ? image.substring(tagAt + 1) : "latest";
    try {
      client
          .pullImageCmd(repository)
          .withTag(tag)
          .exec(new ResultCallback.Adapter<PullResponseItem>())
          .awaitCompletion();
    } catch (DockerException e) {
      throw new TaskExecutionException("ImagePull", "IMAGE_PULL_FAILED - " + e.getMessage());
    }
  }

  private boolean imagePresent(String image) {
    try {
      client.inspectImageCmd(image).exec();
      return true;
    } catch (NotFoundException e) {
      return false;
    }
  }

  /**
   * A script task's body reaches the container the way the Jobs executor's ConfigMap does: an
   * executable file at /scripts/script, which MUST start with a shebang. Written into the created
   * container before it is started, since there is no ConfigMap to mount.
   */
  private void copyScript(String containerId, String script) {
    byte[] body = script.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    try (TarArchiveOutputStream archive = new TarArchiveOutputStream(tar)) {
      TarArchiveEntry directory = new TarArchiveEntry("scripts/");
      directory.setMode(0755);
      archive.putArchiveEntry(directory);
      archive.closeArchiveEntry();

      TarArchiveEntry entry = new TarArchiveEntry("scripts/script");
      entry.setMode(0755);
      entry.setSize(body.length);
      archive.putArchiveEntry(entry);
      archive.write(body);
      archive.closeArchiveEntry();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    client
        .copyArchiveToContainerCmd(containerId)
        .withTarInputStream(new ByteArrayInputStream(tar.toByteArray()))
        .withRemotePath("/")
        .exec();
  }

  @Override
  public List<RunResult> watch(TaskRun task, Long timeoutMinutes) throws InterruptedException {
    Map<String, String> taskLabels = taskLabels(task);
    List<Container> containers = containers(taskLabels);
    if (containers.isEmpty()) {
      throw new TaskExecutionException("JobDeleted", "ContainerDeleted - The container was deleted before completion.");
    }
    String containerId = containers.get(0).getId();

    // Docker enforces no deadline of its own, so this is the deadline, not a backstop. The engine
    // still reaps on its own budget; whichever notices first ends the Task the same way.
    Instant deadline = Instant.now().plus(Duration.ofMinutes(timeoutMinutes));
    try {
      while (true) {
        leaseRegistry.beat(task.getId());
        InspectContainerResponse.ContainerState state = state(containerId);
        if (state == null) {
          throw new TaskExecutionException(
              "JobDeleted", "ContainerDeleted - The container was deleted before completion.");
        }
        if (isTerminal(state)) {
          return endOf(task, containerId, state);
        }
        if (!Instant.now().isBefore(deadline)) {
          stopQuietly(containerId);
          throw new TaskExecutionException(
              "DeadlineExceeded",
              readResults(containerId, task.getResults()),
              "ContainerTimeout - Container timed out while waiting for completion.");
        }
        Thread.sleep(Duration.ofSeconds(pollSeconds).toMillis());
      }
    } finally {
      leaseRegistry.remove(task.getId());
    }
  }

  private List<RunResult> endOf(
      TaskRun task, String containerId, InspectContainerResponse.ContainerState state) {
    // A non-zero exit does not erase the Result Parameters the Task already wrote.
    List<RunResult> results = readResults(containerId, task.getResults());
    long exitCode = Optional.ofNullable(state.getExitCodeLong()).orElse(-1L);
    if (exitCode == 0L) {
      LOGGER.info("Container completed successfully");
      return results;
    }
    String message =
        "ContainerFailed - Container exited with code "
            + exitCode
            + (state.getError() != null && !state.getError().isBlank() ? " - " + state.getError() : "");
    LOGGER.info("Task execution error. " + message);
    if (Boolean.TRUE.equals(state.getOOMKilled())) {
      throw new TaskExecutionException("OOMKilled", results, message);
    }
    throw new TaskExecutionException("JobFailed", results, message);
  }

  private boolean isTerminal(InspectContainerResponse.ContainerState state) {
    return !Boolean.TRUE.equals(state.getRunning())
        && ("exited".equals(state.getStatus()) || "dead".equals(state.getStatus()));
  }

  private InspectContainerResponse.ContainerState state(String containerId) {
    try {
      return client.inspectContainerCmd(containerId).exec().getState();
    } catch (NotFoundException e) {
      return null;
    }
  }

  /**
   * Copy the results file back out of the exited container. An absent file means the Task wrote no
   * Results; the 4096-byte cap is the engine's, applied when the Task ends.
   *
   * <p>Unlike the Kubernetes termination message there is no ceiling here - the file is read whole
   * off the container filesystem - so an unparseable payload is never a truncation symptom. It is
   * a Task writing something that is not a results payload: no results, logged, not a failure. An
   * oversize but well-formed payload is the engine's to reject at {@code end}.
   */
  private List<RunResult> readResults(String containerId, List<RunResult> declaredResults) {
    try (InputStream archive = client.copyArchiveFromContainerCmd(containerId, RESULTS_PATH).exec();
        TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
      if (tar.getNextEntry() == null) {
        return List.of();
      }
      String payload = new String(tar.readAllBytes(), StandardCharsets.UTF_8);
      Optional<List<RunResult>> parsed = TerminationMessageParser.parse(payload, declaredResults);
      if (parsed.isEmpty()) {
        LOGGER.warn(
            "Results file is not a Result Parameter payload ({} bytes); no Results recorded.",
            payload.length());
      }
      return parsed.orElseGet(List::of);
    } catch (NotFoundException e) {
      return List.of();
    } catch (IOException | DockerException e) {
      LOGGER.warn("Unable to read Results from container {}: {}", containerId, e.getMessage());
      return List.of();
    }
  }

  @Override
  public void cancel(TaskRun task) {
    Map<String, String> taskLabels = taskLabels(task);
    LOGGER.info("Cancelling container with labels: " + taskLabels);

    List<Container> containers = containers(taskLabels);
    if (containers.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.TASK_EXECUTION_ERROR,
          "CANCEL_FAILURE - No containers found matching the labels: " + taskLabels);
    }
    containers.forEach(
        container -> {
          stopQuietly(container.getId());
          removeQuietly(container.getId());
        });
  }

  @Override
  public void delete(TaskRun task) {
    LOGGER.debug("Deleting container...");
    containers(taskLabels(task)).forEach(container -> removeQuietly(container.getId()));
  }

  private Map<String, String> taskLabels(TaskRun task) {
    return helperKubeService.getTaskLabels(
        task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), task.getLabels());
  }

  private List<Container> containers(Map<String, String> taskLabels) {
    return client.listContainersCmd().withShowAll(true).withLabelFilter(taskLabels).exec();
  }

  private void stopQuietly(String containerId) {
    try {
      client.stopContainerCmd(containerId).withTimeout(STOP_GRACE_SECONDS).exec();
    } catch (DockerException e) {
      LOGGER.debug("Container {} was not running: {}", containerId, e.getMessage());
    }
  }

  private void removeQuietly(String containerId) {
    try {
      client.removeContainerCmd(containerId).withForce(true).exec();
    } catch (DockerException e) {
      LOGGER.debug("Container {} was already removed: {}", containerId, e.getMessage());
    }
  }
}

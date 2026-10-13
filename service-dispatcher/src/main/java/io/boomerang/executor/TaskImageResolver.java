package io.boomerang.executor;

import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides what a Task's container actually runs. Every {@link TaskExecutor} asks this instead of
 * reading {@code spec.image} / {@code spec.command} / {@code spec.script} directly, so the answer
 * is the same on Tekton, Kubernetes Jobs and any later runtime.
 *
 * <p>For every authored type ({@code template}, {@code custom}, {@code script}) the answer is the
 * spec, unchanged. The {@code ai} type is the exception: its author never builds a container, so
 * the TaskRun carries no image or command at all and the dispatcher supplies both - the worker
 * image {@code flow.dispatcher.ai.image} and its {@code prompt} command. That image is an ordinary
 * task image released from the boomerang-io/tasks repository ({@code tasks/ai} ->
 * {@code boomerangio/task-ai}), versioned independently of the product tag. Any image, command or
 * script that somehow reached the spec of an {@code ai} task is ignored, so a definition cannot
 * redirect the AI worker at another container.
 *
 * <p>Nothing else changes for {@code ai}: params still arrive as {@code PARAM_<NAME>} environment
 * variables and results are still read from {@code RESULTS_PATH}
 * ({@link io.boomerang.kube.KubeHelperService#createTaskEnvVars}).
 *
 * <p>The {@code uploadartifact} and {@code downloadartifact} types work the same way: the default
 * worker image {@code flow.dispatcher.artifact.image} runs its own entrypoint with the arguments
 * {@code artifact upload} or {@code artifact download}. The engine fills their link params when it
 * hands the task out, so they reach the worker as ordinary {@code PARAM_<NAME>} variables.
 */
@Component
public class TaskImageResolver {

  /** The worker image's entrypoint for a single chat completion. */
  static final List<String> AI_COMMAND = List.of("prompt");

  static final List<String> ARTIFACT_UPLOAD_ARGUMENTS = List.of("artifact", "upload");
  static final List<String> ARTIFACT_DOWNLOAD_ARGUMENTS = List.of("artifact", "download");

  @Value("${flow.dispatcher.ai.image}")
  private String aiImage;

  @Value("${flow.dispatcher.artifact.image}")
  private String artifactImage;

  /** The container image to run; never null for an {@code ai} or artifact task. */
  public String image(TaskRun task) {
    if (isAi(task)) {
      return aiImage;
    }
    return isArtifact(task) ? artifactImage : spec(task).getImage();
  }

  /** The container command, or null to leave the image's own entrypoint in place. */
  public List<String> command(TaskRun task) {
    if (isAi(task)) {
      return AI_COMMAND;
    }
    return isArtifact(task) ? null : spec(task).getCommand();
  }

  /** The container arguments: the worker's artifact command for an artifact task. */
  public List<String> arguments(TaskRun task) {
    if (TaskType.uploadartifact.equals(task.getType())) {
      return ARTIFACT_UPLOAD_ARGUMENTS;
    }
    if (TaskType.downloadartifact.equals(task.getType())) {
      return ARTIFACT_DOWNLOAD_ARGUMENTS;
    }
    return spec(task).getArguments();
  }

  /**
   * The script body to mount and run instead of a command, or null when there is none. An {@code
   * ai} or artifact task never runs a script - the command is the worker image's own.
   */
  public String script(TaskRun task) {
    return (isAi(task) || isArtifact(task)) ? null : spec(task).getScript();
  }

  private boolean isArtifact(TaskRun task) {
    return TaskType.uploadartifact.equals(task.getType())
        || TaskType.downloadartifact.equals(task.getType());
  }

  private boolean isAi(TaskRun task) {
    return TaskType.ai.equals(task.getType());
  }

  private TaskRunSpec spec(TaskRun task) {
    return task.getSpec() != null ? task.getSpec() : new TaskRunSpec();
  }
}

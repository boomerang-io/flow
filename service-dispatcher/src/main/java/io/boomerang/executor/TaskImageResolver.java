package io.boomerang.executor;

import io.boomerang.dispatcher.sdk.model.TaskEnvVar;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Decides what a Task's container actually runs. Every {@link TaskExecutor} asks this instead of
 * reading {@code spec.image} / {@code spec.command} / {@code spec.script} directly, so the answer
 * is the same on Tekton, Kubernetes Jobs and any later runtime.
 *
 * <p>For every authored type ({@code template}, {@code custom}, {@code script}) and for {@code ai}
 * the answer is the spec, unchanged: an {@code ai} catalogue revision declares its image and
 * command like any other, with the one difference that the image must be under an allowed prefix
 * ({@link #allowedAiImage}) and that an {@code ai} task never runs a script. The images are
 * ordinary task images released from the boomerang-io/tasks repository ({@code tasks/ai} ->
 * {@code boomerangio/task-ai}), versioned independently of the product tag.
 *
 * <p>Nothing else changes for {@code ai}: params arrive as {@code PARAM_<NAME>} environment
 * variables and results are read from {@code RESULTS_PATH}
 * ({@link io.boomerang.kube.KubeHelperService#createTaskEnvVars}).
 *
 * <p>The {@code uploadartifact} and {@code downloadartifact} types always run the default worker
 * image {@code flow.dispatcher.artifact.image} with its own entrypoint and the arguments
 * {@code artifact upload} or {@code artifact download}. The engine fills their link params when it
 * hands the task out, so they reach the worker as ordinary {@code PARAM_<NAME>} variables.
 */
@Component
public class TaskImageResolver {

  static final List<String> ARTIFACT_UPLOAD_ARGUMENTS = List.of("artifact", "upload");
  static final List<String> ARTIFACT_DOWNLOAD_ARGUMENTS = List.of("artifact", "download");

  @Value("${flow.dispatcher.artifact.image}")
  private String artifactImage;

  @Value("${flow.dispatcher.ai.endpoint:}")
  private String aiEndpoint;

  @Value("${flow.dispatcher.ai.token:}")
  private String aiToken;

  @Value("${flow.dispatcher.ai.image-prefixes:boomerangio/task-ai}")
  private List<String> aiImagePrefixes;

  /**
   * Whether an {@code ai} revision may declare this image: it starts with one of
   * {@code flow.dispatcher.ai.image-prefixes}, compared on the string as declared, so a prefix of
   * {@code boomerangio/task-ai} admits every tag and digest of that image and nothing else. The
   * dispatcher refuses an {@code ai} task outside the prefixes before it runs, which keeps a
   * dispatcher registered for {@code ai} the egress zone for the Boomerang worker alone unless an
   * operator adds another prefix.
   */
  public boolean allowedAiImage(String image) {
    return StringUtils.hasText(image)
        && aiImagePrefixes != null
        && aiImagePrefixes.stream()
            .map(String::trim)
            .anyMatch(prefix -> !prefix.isEmpty() && image.startsWith(prefix));
  }

  /**
   * The task-defined environment, which for an {@code ai} task starts with the dispatcher's
   * credential defaults: {@code OPENAI_BASE_URL} from {@code flow.dispatcher.ai.endpoint} and
   * {@code OPENAI_API_KEY} from {@code flow.dispatcher.ai.token}, each only when configured. The
   * task's own {@code endpoint} and {@code token} params still arrive as {@code PARAM_ENDPOINT} and
   * {@code PARAM_TOKEN} and win inside the image; a task-defined variable of the same name wins
   * over the default.
   */
  public List<TaskEnvVar> environment(TaskRun task) {
    List<TaskEnvVar> environment = new ArrayList<>();
    if (isAi(task) && StringUtils.hasText(aiEndpoint)) {
      environment.add(new TaskEnvVar("OPENAI_BASE_URL", aiEndpoint));
    }
    if (isAi(task) && StringUtils.hasText(aiToken)) {
      environment.add(new TaskEnvVar("OPENAI_API_KEY", aiToken));
    }
    if (spec(task).getEnvs() != null) {
      environment.addAll(spec(task).getEnvs());
    }
    return environment;
  }

  /** The container image to run; never null for an artifact task. */
  public String image(TaskRun task) {
    return isArtifact(task) ? artifactImage : spec(task).getImage();
  }

  /** The container command, or null to leave the image's own entrypoint in place. */
  public List<String> command(TaskRun task) {
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

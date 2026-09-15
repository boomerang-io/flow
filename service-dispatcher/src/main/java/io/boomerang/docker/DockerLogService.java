package io.boomerang.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import io.boomerang.dispatcher.TaskLogStore;
import io.boomerang.kube.KubeHelperService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Serves a Task's log from the Docker daemon. A still-running container is followed, and the
 * follow ends of its own accord when the container exits; a finished one is read once.
 */
@Component
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "docker")
public class DockerLogService implements TaskLogStore {

  private static final Logger LOGGER = LogManager.getLogger(DockerLogService.class);

  private static final String NO_CONTAINER_YET_MESSAGE = "No container found yet for the requested Task.";

  private final DockerClient client;

  private final KubeHelperService helperKubeService;

  public DockerLogService(DockerClient client, KubeHelperService helperKubeService) {
    this.client = client;
    this.helperKubeService = helperKubeService;
  }

  @Override
  public String get(String workflowRef, String workflowRunRef, String taskRunRef) {
    Container container = container(workflowRef, workflowRunRef, taskRunRef);
    if (container == null) {
      return NO_CONTAINER_YET_MESSAGE;
    }
    StringBuilder log = new StringBuilder();
    collect(container.getId(), false, frame -> log.append(new String(frame.getPayload(), StandardCharsets.UTF_8)));
    return log.toString();
  }

  @Override
  public StreamingResponseBody stream(
      HttpServletResponse response, String workflowRef, String workflowRunRef, String taskRunRef) {
    Container container = container(workflowRef, workflowRunRef, taskRunRef);
    if (container == null) {
      // Nothing to watch yet - end the response instead of hanging on a container that may never
      // appear (or arrives too late for this request to still care).
      return outputStream -> {
        outputStream.write(NO_CONTAINER_YET_MESSAGE.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
      };
    }
    boolean follow = "running".equals(container.getState());
    return outputStream -> {
      LOGGER.info("Log stream started for container " + container.getId() + "...");
      try {
        collect(container.getId(), follow, frame -> write(outputStream, frame));
      } finally {
        outputStream.flush();
        LOGGER.info("Log stream closed for container " + container.getId() + "...");
      }
    };
  }

  private void write(OutputStream outputStream, Frame frame) {
    try {
      outputStream.write(frame.getPayload());
    } catch (IOException e) {
      // The client disconnected; ending the callback ends the stream.
      throw new UncheckedIOException(e);
    }
  }

  private void collect(String containerId, boolean follow, java.util.function.Consumer<Frame> onFrame) {
    ResultCallback.Adapter<Frame> callback =
        new ResultCallback.Adapter<>() {
          @Override
          public void onNext(Frame frame) {
            onFrame.accept(frame);
          }
        };
    try {
      client
          .logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(follow)
          .withTailAll()
          .exec(callback)
          .awaitCompletion();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      LOGGER.info("Log stream ended for container " + containerId + ": " + e.getMessage());
    }
  }

  private Container container(String workflowRef, String workflowRunRef, String taskRunRef) {
    Map<String, String> taskLabels =
        helperKubeService.getTaskLabels(workflowRef, workflowRunRef, taskRunRef, null);
    List<Container> containers =
        client.listContainersCmd().withShowAll(true).withLabelFilter(taskLabels).exec();
    return containers.isEmpty() ? null : containers.get(0);
  }
}

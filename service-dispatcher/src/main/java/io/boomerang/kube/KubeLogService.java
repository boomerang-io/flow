package io.boomerang.kube;

import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class KubeLogService {

  private static final Logger LOGGER = LogManager.getLogger(KubeLogService.class);

  // How often the side thread checks whether a running Pod has gone terminal, so it can close
  // the LogWatch and let the stream end instead of hanging forever.
  private static final Duration POD_TERMINATION_POLL_INTERVAL = Duration.ofSeconds(2);

  private static final String NO_POD_YET_MESSAGE = "No Pod found yet for the requested Task.";

  // A Pod still Pending (scheduling, pulling the image, creating the container) has no log yet:
  // Kubernetes answers a log read with its own "waiting to start" status. Say so once, wait for
  // the container to start, then follow its log in the same response.
  static final String WAITING_FOR_START_MESSAGE = "Waiting for the task's container to start.\n";
  static final String DID_NOT_START_MESSAGE =
      "The task's container has not started yet. Open the log again to keep following it.\n";
  private Duration podStartWait = Duration.ofMinutes(5);

  private final KubeHelperService helperKubeService;

  KubernetesClient client = null;

  public KubeLogService(KubeHelperService helperKubeService, KubernetesClient client) {
    this.helperKubeService = helperKubeService;
    this.client = client;
  }

  // Tests swap in the mock-server client after the context is up.
  public void setClient(KubernetesClient client) {
    this.client = client;
  }

  // Tests shorten how long a Pending Pod is waited for.
  void setPodStartWait(Duration podStartWait) {
    this.podStartWait = podStartWait;
  }

  public String getPodLog(
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {
    Map<String, String> labelSelector =
        helperKubeService.getTaskLabels(
            workflowId, workflowActivityId, taskActivityId, customLabels);

    try {
      List<Pod> pods = client.pods().withLabels(labelSelector).list().getItems();

      if (pods != null && !pods.isEmpty()) {
        Pod pod = pods.get(0);
        return client
            .pods()
            .inNamespace(pod.getMetadata().getNamespace())
            .withName(pod.getMetadata().getName())
            .withPrettyOutput()
            .getLog();
      } else {
        throw new KubeRuntimeException("No logs found for Task");
      }

    } catch (Exception e) {
      LOGGER.error("getPodLog Exception: ", e);
      throw new KubeRuntimeException("Error getPodLog", e);
    }
  }

  public StreamingResponseBody streamPodLog(
      HttpServletResponse response,
      String workflowId,
      String workflowActivityId,
      String taskActivityId,
      Map<String, String> customLabels) {

    LOGGER.info("Stream logs from Kubernetes");

    Map<String, String> labelSelector =
        helperKubeService.getTaskLabels(
            workflowId, workflowActivityId, taskActivityId, customLabels);
    List<Pod> pods = client.pods().withLabels(labelSelector).list().getItems();
    if (pods.isEmpty()) {
      // Nothing to watch yet - end the response instead of hanging on a Pod that may never
      // appear (or arrives too late for this request to still care).
      return noPodYetLog();
    }

    Pod pod = pods.get(0);
    String namespace = pod.getMetadata().getNamespace();
    String podName = pod.getMetadata().getName();

    if (isTerminal(pod)) {
      // A terminated Pod's log has nothing left to watch for - watchLog() would open a stream
      // that never receives another event and never closes. Read it once instead.
      return finishedPodLog(namespace, podName);
    }
    if (isPending(pod)) {
      return startingPodLog(namespace, podName);
    }
    return watchedPodLog(namespace, podName);
  }

  private StreamingResponseBody watchedPodLog(String namespace, String podName) {
    try {
      LogWatch logWatch = client.pods().inNamespace(namespace).withName(podName).watchLog();
      InputStream inputStream = logWatch.getOutput();
      // The watch has nothing that tells it "the Pod finished" - only new log lines. Poll the
      // Pod's phase on the side and close the watch once it goes terminal, so the stream ends
      // instead of hanging until the client gives up.
      Thread terminationPoller = new Thread(() -> pollUntilPodTerminal(namespace, podName, logWatch));
      terminationPoller.setDaemon(true);
      terminationPoller.setName("pod-log-termination-poll-" + podName);
      terminationPoller.start();
      return getPodLog(
          inputStream,
          podName,
          () -> {
            terminationPoller.interrupt();
            closeQuietly(logWatch);
          });
    } catch (Exception e) {
      LOGGER.error("streamPodLog Exception: ", e);
      throw new KubeRuntimeException("Error streamPodLog", e);
    }
  }

  // Say the container is starting, wait for the Pod to leave Pending, then answer as the Pod's
  // state then calls for: follow a running Pod, read a finished one once.
  private StreamingResponseBody startingPodLog(String namespace, String podName) {
    return outputStream -> {
      outputStream.write(WAITING_FOR_START_MESSAGE.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      Pod current = awaitStart(namespace, podName);
      if (current == null) {
        outputStream.write(NO_POD_YET_MESSAGE.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
      } else if (isPending(current)) {
        outputStream.write(DID_NOT_START_MESSAGE.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
      } else if (isTerminal(current)) {
        finishedPodLog(namespace, podName).writeTo(outputStream);
      } else {
        watchedPodLog(namespace, podName).writeTo(outputStream);
      }
    };
  }

  // The Pod once it has left Pending, the still-Pending Pod when the wait runs out, or null when it
  // is gone.
  private Pod awaitStart(String namespace, String podName) {
    long deadline = System.nanoTime() + podStartWait.toNanos();
    Pod current = client.pods().inNamespace(namespace).withName(podName).get();
    try {
      while (current != null && isPending(current) && System.nanoTime() < deadline) {
        Thread.sleep(POD_TERMINATION_POLL_INTERVAL.toMillis());
        current = client.pods().inNamespace(namespace).withName(podName).get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return current;
  }

  private StreamingResponseBody noPodYetLog() {
    return outputStream -> {
      outputStream.write(NO_POD_YET_MESSAGE.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
    };
  }

  private StreamingResponseBody finishedPodLog(String namespace, String podName) {
    return outputStream -> {
      LOGGER.info("Pod " + podName + " is already terminal - reading its log once, no watch.");
      String log =
          client.pods().inNamespace(namespace).withName(podName).withPrettyOutput().getLog();
      if (log != null) {
        outputStream.write(log.getBytes(StandardCharsets.UTF_8));
      }
      outputStream.flush();
    };
  }

  private void pollUntilPodTerminal(String namespace, String podName, LogWatch logWatch) {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Thread.sleep(POD_TERMINATION_POLL_INTERVAL.toMillis());
        Pod current = client.pods().inNamespace(namespace).withName(podName).get();
        if (current == null || isTerminal(current)) {
          logWatch.close();
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean isTerminal(Pod pod) {
    String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
    return "Succeeded".equals(phase) || "Failed".equals(phase);
  }

  private boolean isPending(Pod pod) {
    return pod.getStatus() != null && "Pending".equals(pod.getStatus().getPhase());
  }

  private void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      LOGGER.debug("Error closing Kubernetes resource: ", e);
    }
  }

  // Every read is written and flushed at once, so a line reaches the reader when the container
  // writes it, not when the response buffer fills or the Pod ends. The loop runs until
  // end-of-stream (-1); a legal zero-length read does not stop it.
  protected StreamingResponseBody getPodLog(InputStream inputStream, String podName) {
    return getPodLog(inputStream, podName, null);
  }

  // onStreamEnd, when given, both (a) turns a mid-stream IOException from a self-initiated watch
  // close into a normal end rather than a propagated failure, and (b) runs cleanup - stopping the
  // termination poller and closing the LogWatch - once the stream is done, however it ended
  // (Pod went terminal, or the client disconnected and the write itself failed).
  protected StreamingResponseBody getPodLog(
      InputStream inputStream, String podName, Runnable onStreamEnd) {
    return outputStream -> {
      LOGGER.info("Log stream started for pod " + podName + "...");
      try {
        long bytesStreamed = copyAsWritten(inputStream, outputStream);
        LOGGER.info(
            "Log stream completed for pod "
                + podName
                + ", total bytes streamed="
                + bytesStreamed
                + "...");
      } catch (IOException e) {
        if (onStreamEnd == null) {
          throw e;
        }
        LOGGER.info("Log stream ended for pod " + podName + ": " + e.getMessage());
      } finally {
        outputStream.flush();
        inputStream.close();
        if (onStreamEnd != null) {
          onStreamEnd.run();
        }
        LOGGER.info("Log stream closed for pod " + podName + "...");
      }
    };
  }

  static long copyAsWritten(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[8192];
    long copied = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
      out.flush();
      copied += read;
    }
    return copied;
  }
}

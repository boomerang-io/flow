package io.boomerang.kube;

import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
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

  private void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception e) {
      LOGGER.debug("Error closing Kubernetes resource: ", e);
    }
  }

  // Note: the loop this replaced never flushed per-chunk either (only once, in its finally block,
  // after the whole read loop finished) — so a live log tail was already only as timely as the
  // JDK's own I/O buffering, not the byte-at-a-time reads here. transferTo preserves that: it
  // writes straight through to outputStream, and this method's own flush() below still runs once,
  // after all bytes are copied. It also drops the old `> 0` read-loop condition, which stopped
  // early on any legal zero-length read; transferTo loops correctly until end-of-stream (-1).
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
        long bytesStreamed = inputStream.transferTo(outputStream);
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
}

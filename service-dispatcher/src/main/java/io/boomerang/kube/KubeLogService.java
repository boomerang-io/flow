package io.boomerang.kube;

import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class KubeLogService {

  private static final Logger LOGGER = LogManager.getLogger(KubeLogService.class);

  private final KubeHelperService helperKubeService;

  KubernetesClient client = null;

  public KubeLogService(KubeHelperService helperKubeService, KubernetesClient client) {
    this.helperKubeService = helperKubeService;
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
    StreamingResponseBody responseBody = null;
    List<Pod> pods = client.pods().withLabels(labelSelector).list().getItems();
    if (!pods.isEmpty()) {
      Pod pod = client.pods().withLabels(labelSelector).list().getItems().get(0);

      try {
        InputStream inputStream =
            client
                .pods()
                .inNamespace(pod.getMetadata().getNamespace())
                .withName(pod.getMetadata().getName())
                .watchLog()
                .getOutput();
        responseBody = getPodLog(inputStream, pod.getMetadata().getName());
      } catch (Exception e) {

        LOGGER.error("streamPodLog Exception: ", e);
        throw new KubeRuntimeException("Error streamPodLog", e);
      }
    }
    return responseBody;
  }

  // Note: the loop this replaced never flushed per-chunk either (only once, in its finally block,
  // after the whole read loop finished) — so a live log tail was already only as timely as the
  // JDK's own I/O buffering, not the byte-at-a-time reads here. transferTo preserves that: it
  // writes straight through to outputStream, and this method's own flush() below still runs once,
  // after all bytes are copied. It also drops the old `> 0` read-loop condition, which stopped
  // early on any legal zero-length read; transferTo loops correctly until end-of-stream (-1).
  protected StreamingResponseBody getPodLog(InputStream inputStream, String podName) {
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
      } finally {
        outputStream.flush();
        inputStream.close();
        LOGGER.info("Log stream closed for pod " + podName + "...");
      }
    };
  }
}

package io.boomerang.dispatcher;

import io.boomerang.executor.TaskExecutor;
import io.boomerang.kube.KubeLogService;
import io.boomerang.kube.exception.KubeRuntimeException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LogService {

  private static final Logger LOGGER = LogManager.getLogger(LogService.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // The most entries Loki returns for one query_range call.
  static final int LOKI_PAGE_LIMIT = 5000;

  @Value("${dispatcher.logging.type}")
  protected String loggingType;

  @Autowired private MessageSource messageSource;

  @Autowired private KubeLogService logKubeService;

  // Knows the pod of a task it is running; a task run elsewhere is read by its labels.
  @Autowired private TaskExecutor executor;

  @Value("${dispatcher.logging.host}")
  protected String lokiHost;

  @Value("${dispatcher.logging.port}")
  protected String lokiPort;

  @Autowired
  @Qualifier("streamingRestTemplate")
  private RestTemplate lokiRestTemplate;

  public StreamingResponseBody streamLogForTask(
      HttpServletResponse response, String workflowRef, String workflowRunRef, String taskRunRef) {
    try {
      if ("default".equals(loggingType)) {
        return logKubeService.streamPodLog(
            response,
            workflowRef,
            workflowRunRef,
            taskRunRef,
            null,
            executor.runtimePodSelector(taskRunRef).orElse(null));
      } else if ("loki".equals(loggingType)) {
        return streamLogsFromLoki(workflowRef, taskRunRef);
      } else if ("elastic".equals(loggingType)) {
        return getDefaultErrorMessage(getMessageDeprecated());
      } else {
        return getDefaultErrorMessage(getMessageUnableToAccessLogs());
      }
    } catch (KubeRuntimeException e) {
      return getDefaultErrorMessage(getMessageServerError());
    }
  }

  /*
   * Pages Loki's query_range forward from the epoch, 5000 entries a page (Loki's maximum), through
   * the streaming template so RestConfig's TLS and timeout settings apply. A page that comes back
   * full continues from its last entry's timestamp; Loki's start is inclusive, so the next page's
   * first entry repeats it and is skipped.
   */
  private StreamingResponseBody streamLogsFromLoki(String workflowRef, String taskRunRef) {
    LOGGER.info("Streaming logs from loki for TaskRun (" + taskRunRef + ")");
    return outputStream -> {
      PrintWriter printWriter =
          new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
      try {
        String start = "0";
        boolean firstPage = true;
        while (start != null) {
          JsonNode values = lokiPage(workflowRef, taskRunRef, start);
          if (values == null) {
            printWriter.println(getMessageUnableToAccessLogs());
            break;
          }
          for (int index = firstPage ? 0 : 1; index < values.size(); index++) {
            printWriter.print(values.get(index).get(1).asString());
          }
          start =
              values.size() < LOKI_PAGE_LIMIT
                  ? null
                  : values.get(values.size() - 1).get(0).asString();
          firstPage = false;
        }
      } catch (Exception e) {
        LOGGER.error("Error while streaming logs from Loki: ", e);
      }
      printWriter.flush();
      printWriter.close();
    };
  }

  // The first stream's [timestamp, line] pairs, or null when Loki has no stream for the task.
  private JsonNode lokiPage(String workflowRef, String taskRunRef, String start) {
    URI uri =
        UriComponentsBuilder.fromUriString("http://" + lokiHost + ":" + lokiPort)
            .path("/loki/api/v1/query_range")
            .queryParam("limit", LOKI_PAGE_LIMIT)
            .queryParam("direction", "forward")
            .queryParam("start", start)
            .queryParam("query", "{query}")
            .encode()
            .buildAndExpand(lokiSelector(workflowRef, taskRunRef))
            .toUri();
    LOGGER.debug("Loki query: " + uri);
    String body = lokiRestTemplate.getForObject(uri, String.class);
    if (body == null) {
      return null;
    }
    JsonNode result = OBJECT_MAPPER.readTree(body).path("data").path("result");
    if (!result.isArray() || result.isEmpty()) {
      return null;
    }
    JsonNode values = result.get(0).path("values");
    return values.isArray() ? values : null;
  }

  /*
   * The Loki stream selector for a task's container: the pod labels the dispatcher sets, named as a
   * log shipper's labelmap writes them ("boomerang.io/taskrun-ref" as "boomerang_io_taskrun_ref"),
   * and the task container of either executor, Tekton's "step-task" or the Jobs executor's "task".
   */
  static String lokiSelector(String workflowRef, String taskRunRef) {
    return "{boomerang_io_taskrun_ref=\""
        + taskRunRef
        + "\",boomerang_io_workflow_ref=\""
        + workflowRef
        + "\",container=~\"task|step-task\"}";
  }

  protected StreamingResponseBody getDefaultErrorMessage(String message) {
    LOGGER.info("Returning back default message.");

    return outputStream -> {
      outputStream.write(message.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      outputStream.close();
    };
  }

  private String getMessageDeprecated() {
    MessageSourceAccessor accessor = new MessageSourceAccessor(messageSource);
    return accessor.getMessage("DEPRECATED_LOGS_OPTION");
  }

  private String getMessageUnableToAccessLogs() {
    MessageSourceAccessor accessor = new MessageSourceAccessor(messageSource);
    return accessor.getMessage("UNABLE_RETRIEVE_LOGS");
  }

  private String getMessageServerError() {
    MessageSourceAccessor accessor = new MessageSourceAccessor(messageSource);
    return accessor.getMessage("INTERNAL_SERVER_ERROR_LOGS");
  }
}

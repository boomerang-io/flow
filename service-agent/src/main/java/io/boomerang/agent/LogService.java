package io.boomerang.agent;

import io.boomerang.kube.KubeLogService;
import io.boomerang.kube.exception.KubeRuntimeException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class LogService {

  private static final Logger LOGGER = LogManager.getLogger(LogService.class);

  @Value("${agent.logging.type}")
  protected String loggingType;

  @Autowired private MessageSource messageSource;

  @Autowired private KubeLogService logKubeService;

  @Value("${agent.logging.host}")
  protected String lokiHost;

  @Value("${agent.logging.port}")
  protected String lokiPort;

  public String getLogForTask(String workflowRef, String workflowRunRef, String taskRunRef) {
    return logKubeService.getPodLog(workflowRef, workflowRunRef, taskRunRef, null);
  }

  public StreamingResponseBody streamLogForTask(
      HttpServletResponse response, String workflowRef, String workflowRunRef, String taskRunRef) {
    //    StreamingResponseBody srb = null;
    try {
      //      if (logKubeService.isKubePodAvailable(workflowId, workflowActivityId, taskId,
      // taskActivityId)
      //          && "default".equals(loggingType)) {
      if ("default".equals(loggingType)) {
        return logKubeService.streamPodLog(response, workflowRef, workflowRunRef, taskRunRef, null);
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

  // TODO: reduce complexity, refactor method
  private StreamingResponseBody streamLogsFromLoki(String workflowRef, String taskRunRef) {

    LOGGER.info("Streaming logs from loki for TaskRun (" + taskRunRef + ")");
    return outputStream -> {
      PrintWriter printWriter = new PrintWriter(outputStream);

      final String filter = createLokiFilter(workflowRef, taskRunRef);
      LOGGER.info("Loki filter: " + filter);

      final String encodedQuery = URLEncoder.encode(filter, StandardCharsets.UTF_8);
      final Integer limit = 5000; // max chunk size supported by Loki
      final String direction = "forward"; // default backward
      final String lokiEndpoint = "http://" + lokiHost + ":" + lokiPort;
      final String uri =
          lokiEndpoint
              + "/loki/api/v1/query_range?&limit="
              + Integer.toString(limit)
              + "&direction="
              + direction
              + "&query="
              + encodedQuery;
      // If no `end` argument is defined, it will be automatically set to `now()` by server

      LOGGER.info("Loki endpoint: " + uri);

      String start = "&start=0"; // Thursday, January 1, 1970 12:00:00 AM
      Boolean moreLogsAvailable = Boolean.TRUE; // TODO: create a method instead
      CloseableHttpClient httpClient = HttpClients.createDefault();

      try {
        while (moreLogsAvailable.equals(Boolean.TRUE)) {
          HttpGet request = new HttpGet(uri + start);
          JSONObject currentLogBatch;

          CloseableHttpResponse response = httpClient.execute(request);
          try {
            if (response.getEntity() != null) {

              // TODO check if result is in JSON format
              currentLogBatch = new JSONObject(EntityUtils.toString(response.getEntity()));

              JSONArray queryResults = currentLogBatch.getJSONObject("data").getJSONArray("result");
              JSONArray logArray;
              String logEntry;

              if (queryResults.length() == 0) {
                printWriter.println(getMessageUnableToAccessLogs());
                printWriter.flush();
                printWriter.close();
                return;
              }

              if (queryResults.length() > 0) {
                logArray = queryResults.getJSONObject(0).optJSONArray("values");

                int index = 1;
                if (start.equals("&start=0")) index = 0; // no prior log line to overlap

                for (; index < logArray.length(); index++) { // print line by line
                  logEntry = logArray.getJSONArray(index).get(1).toString();
                  printWriter.print(logEntry); // TODO: can I generate multiline payloads?
                }

                if (logArray.length() < limit) { // check if the current iteration is the last one
                  moreLogsAvailable = Boolean.FALSE;
                } else {
                  JSONArray lastEntry = logArray.getJSONArray(logArray.length() - 1);
                  start = "&start=" + lastEntry.get(0).toString();
                }
              } else {
                moreLogsAvailable = Boolean.FALSE;
              }
            } else {
              printWriter.println(getMessageUnableToAccessLogs());
              printWriter.flush();
              printWriter.close();
              return;
            }
          } finally {
            response.close();
          }
        }
      } catch (Exception e) {
        LOGGER.error("Error while streaming logs from Loki: ", e);
      } finally {
        httpClient.close();
      }
      printWriter.flush();
      printWriter.close();
    };
  }

  private String createLokiFilter(String workflowRef, String taskRunRef) {
    return "{bmrg_task_activity=\""
        + taskRunRef
        + "\",bmrg_workflow=\""
        + workflowRef
        + "\",bmrg_container=\"step-task\"}";
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

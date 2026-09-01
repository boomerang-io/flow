package io.boomerang.engine;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;
import io.boomerang.common.error.BoomerangException;

@Service
@Primary
public class LogClient {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.agent.logstream.url}")
  private String logStreamURL;

  // Log streaming: long idle read so quiet streams are not cut; control templates stay at 60s.
  @Autowired
  @Qualifier("streamingRestTemplate")
  public RestTemplate restTemplate;

  public StreamingResponseBody streamLog(
      String workflowId, String workflowRunId, String taskRunId) {
    LOGGER.info("URL: " + logStreamURL);

    URI encodedURI = buildLogStreamUri(workflowId, workflowRunId, taskRunId);

    return outputStream -> {
      RequestCallback requestCallback =
          request ->
              request
                  .getHeaders()
                  .setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

      PrintWriter printWriter = new PrintWriter(outputStream);
      List<String> removeList = Collections.emptyList();
      ResponseExtractor<Void> responseExtractor =
          getResponseExtractorForRemovalList(removeList, outputStream, printWriter);
      LOGGER.info("Starting log download: {}", encodedURI);
      try {
        restTemplate.execute(encodedURI, HttpMethod.GET, requestCallback, responseExtractor);
      } catch (Exception ex) {
        LOGGER.error(ex.toString());
        throw new BoomerangException(
            ex,
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            ex.getClass().getSimpleName(),
            "Exception in communicating with internal services.",
            HttpStatus.INTERNAL_SERVER_ERROR);
      }
      LOGGER.info("Finished TaskRun[{}] log stream.", taskRunId);
    };
  }

  URI buildLogStreamUri(String workflowId, String workflowRunId, String taskRunId) {
    return UriComponentsBuilder.fromUriString(logStreamURL)
        .queryParam("workflowRef", workflowId)
        .queryParam("workflowRunRef", workflowRunId)
        .queryParam("taskRunRef", taskRunId)
        .build()
        .encode()
        .toUri();
  }

  private ResponseExtractor<Void> getResponseExtractorForRemovalList(
      List<String> maskWordList, OutputStream outputStream, PrintWriter printWriter) {
    if (maskWordList.isEmpty()) {
      LOGGER.info("Remove word list empty, moving on.");
      return restTemplateResponse -> {
        InputStream is = restTemplateResponse.getBody();
        is.transferTo(outputStream);
        return null;
      };
      //    } else {
      //      LOGGER.info("Streaming response from controller and processing");
      //      return restTemplateResponse -> {
      //        try {
      //          InputStream is = restTemplateResponse.getBody();
      //          Reader reader = new InputStreamReader(is);
      //          BufferedReader bufferedReader = new BufferedReader(reader);
      //          String input = null;
      //          while ((input = bufferedReader.readLine()) != null) {
      //
      //            printWriter.println(satanzieInput(input, maskWordList));
      //            if (!input.isBlank()) {
      //              printWriter.flush();
      //            }
      //          }
      //        } catch (Exception e) {
      //          LOGGER.error("Error streaming logs, displaying exception and moving on.");
      //          LOGGER.error(ExceptionUtils.getStackTrace(e));
      //        } finally {
      //          printWriter.close();
      //        }
      //        return null;
      //      };
    }
    return null;
  }
}

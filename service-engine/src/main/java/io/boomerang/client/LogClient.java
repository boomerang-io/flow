package io.boomerang.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import io.boomerang.error.BoomerangException;

@Service
@Primary
public class LogClient {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.agent.logstream.url}")
  private String logStreamURL;

  @Autowired
  @Qualifier("insecureRestTemplate")
  public RestTemplate restTemplate;

  public StreamingResponseBody streamLog(
      String workflowId, String workflowRunId, String taskRunId) {
    LOGGER.info("URL: " + logStreamURL);

    Map<String, String> requestParams = new HashMap<>();
    requestParams.put("workflowRef", workflowId);
    requestParams.put("workflowRunRef", workflowRunId);
    requestParams.put("taskRunRef", taskRunId);

    String encodedURL =
        requestParams.keySet().stream()
            .map(key -> key + "=" + requestParams.get(key))
            .collect(Collectors.joining("&", logStreamURL + "?", ""));

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
      LOGGER.info("Starting log download: {}", encodedURL);
      try {
        restTemplate.execute(encodedURL, HttpMethod.GET, requestCallback, responseExtractor);
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

  private ResponseExtractor<Void> getResponseExtractorForRemovalList(
      List<String> maskWordList, OutputStream outputStream, PrintWriter printWriter) {
    if (maskWordList.isEmpty()) {
      LOGGER.info("Remove word list empty, moving on.");
      return restTemplateResponse -> {
        InputStream is = restTemplateResponse.getBody();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
          outputStream.write(data, 0, nRead);
        }
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

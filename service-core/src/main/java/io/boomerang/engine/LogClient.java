package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunClaim;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads a task's live log from the dispatcher holding its claim, at the URL the dispatcher said at
 * registration ({@code logsUrl}) and with the bearer it said to present ({@code logsToken}). This is
 * the only call the engine makes to a dispatcher. A task whose dispatcher registered no URL, or
 * that has no claim, is answered with a message rather than an error: its stored log, when a
 * dispatcher uploaded one, was served before this client is reached ({@link
 * TaskRunService#streamLog}).
 */
@Service
public class LogClient {

  private static final Logger LOGGER = LogManager.getLogger();

  static final String NO_SOURCE_MESSAGE =
      "No log is available for this task: no stored log was uploaded, and its dispatcher"
          + " registered no live log route.";

  static final String IDLE_MESSAGE =
      "\nThe task has written nothing for a while, so this view stopped following its log. Open the"
          + " log again to see anything written since.\n";

  // Log streaming: long idle read so quiet streams are not cut; control templates stay at 60s.
  private final RestTemplate restTemplate;
  private final DispatcherRepository dispatcherRepository;

  public LogClient(
      @Qualifier("streamingRestTemplate") RestTemplate restTemplate,
      DispatcherRepository dispatcherRepository) {
    this.restTemplate = restTemplate;
    this.dispatcherRepository = dispatcherRepository;
  }

  /** Where the task's live log can be read, if anywhere. */
  Optional<LogSource> resolve(TaskRunEntity task) {
    return Optional.ofNullable(task.getClaim())
        .map(RunClaim::getBy)
        .flatMap(dispatcherRepository::findById)
        .filter(dispatcher -> StringUtils.hasText(dispatcher.getLogsUrl()))
        .map(
            dispatcher ->
                new LogSource(
                    buildLogStreamUri(
                        dispatcher.getLogsUrl(),
                        task.getWorkflowRef(),
                        task.getWorkflowRunRef(),
                        task.getId()),
                    StringUtils.hasText(dispatcher.getLogsToken())
                        ? dispatcher.getLogsToken()
                        : null));
  }

  public StreamingResponseBody streamLog(TaskRunEntity task) {
    Optional<LogSource> resolved = resolve(task);
    if (resolved.isEmpty()) {
      LOGGER.info("TaskRun[{}] has no log source.", task.getId());
      return outputStream -> {
        outputStream.write(NO_SOURCE_MESSAGE.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
      };
    }
    LogSource source = resolved.get();
    return outputStream -> {
      RequestCallback requestCallback =
          request -> {
            request
                .getHeaders()
                .setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
            if (source.token() != null) {
              request.getHeaders().setBearerAuth(source.token());
            }
          };
      ResponseExtractor<Void> responseExtractor =
          response -> {
            copyAsWritten(response.getBody(), outputStream);
            return null;
          };
      LOGGER.info("Starting TaskRun[{}] log stream from {}", task.getId(), source.uri());
      try {
        restTemplate.execute(source.uri(), HttpMethod.GET, requestCallback, responseExtractor);
      } catch (Exception ex) {
        if (isIdleTimeout(ex)) {
          // A task silent for longer than the streaming read allows: end the reader's stream with a
          // line saying so, instead of dropping the connection mid-body.
          LOGGER.info("TaskRun[{}] log stream went idle; ending it for the reader.", task.getId());
          outputStream.write(IDLE_MESSAGE.getBytes(StandardCharsets.UTF_8));
          outputStream.flush();
          return;
        }
        LOGGER.error(ex.toString());
        throw new BoomerangException(
            ex,
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            ex.getClass().getSimpleName(),
            "Exception in communicating with internal services.",
            HttpStatus.INTERNAL_SERVER_ERROR);
      }
      LOGGER.info("Finished TaskRun[{}] log stream.", task.getId());
    };
  }

  // The streaming template's read timed out: the dispatcher sent nothing for its idle limit.
  static boolean isIdleTimeout(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Copy a live log to the reader as it arrives: every read is written and flushed at once, so a
   * line reaches the reader when the dispatcher sends it rather than when the response buffer
   * fills or the task ends.
   */
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

  static URI buildLogStreamUri(
      String baseUrl, String workflowId, String workflowRunId, String taskRunId) {
    return UriComponentsBuilder.fromUriString(baseUrl)
        .queryParam("workflowRef", workflowId)
        .queryParam("workflowRunRef", workflowRunId)
        .queryParam("taskRunRef", taskRunId)
        .build()
        .encode()
        .toUri();
  }

  /** A dispatcher's log route for one task, and the bearer to present, or null for none. */
  record LogSource(URI uri, String token) {}
}

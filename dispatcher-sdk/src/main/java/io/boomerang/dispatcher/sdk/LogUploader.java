package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.TaskRun;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Sends a task's finished log to the engine, best effort: a failure is logged and never fails the
 * task. Once the engine answers that it stores no logs (503 with {@code ARTIFACTS_NOT_CONFIGURED}),
 * nothing more is sent for the life of this dispatcher - every task would get the same answer.
 */
final class LogUploader {

  private static final Log LOGGER = LogFactory.getLog(LogUploader.class);

  static final String NOT_CONFIGURED = "ARTIFACTS_NOT_CONFIGURED";

  private final DispatcherClient client;
  private final Supplier<String> dispatcherId;
  private final AtomicBoolean unsupported = new AtomicBoolean();

  LogUploader(DispatcherClient client, Supplier<String> dispatcherId) {
    this.client = client;
    this.dispatcherId = dispatcherId;
  }

  /** Whether the engine has said it stores no logs. */
  boolean isUnsupported() {
    return unsupported.get();
  }

  /** Send the log; return whether the engine stored it. */
  boolean upload(TaskRun task, byte[] log) {
    if (unsupported.get() || log == null || log.length == 0) {
      return false;
    }
    try {
      client.uploadLog(dispatcherId.get(), task.getId(), log);
      LOGGER.info("TaskRun (" + task.getId() + ") log stored (" + log.length + " bytes).");
      return true;
    } catch (HttpStatusCodeException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
          && e.getResponseBodyAsString(StandardCharsets.UTF_8).contains(NOT_CONFIGURED)) {
        if (unsupported.compareAndSet(false, true)) {
          LOGGER.info("The engine stores no task logs (no artifact store); sending no more.");
        }
      } else {
        LOGGER.warn("TaskRun (" + task.getId() + ") log not stored: " + e.getMessage());
      }
      return false;
    } catch (RuntimeException e) {
      LOGGER.warn("TaskRun (" + task.getId() + ") log not stored: " + e.getMessage());
      return false;
    }
  }
}

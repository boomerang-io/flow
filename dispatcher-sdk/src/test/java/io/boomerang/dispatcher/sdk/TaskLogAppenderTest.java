package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.boomerang.dispatcher.sdk.logback.TaskLogAppender;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Lines logged under the MDC tag reach the capture; untagged lines do not. */
class TaskLogAppenderTest {

  private final TaskLogCapture capture = new TaskLogCapture();
  private final TaskLogAppender appender = TaskLogAppender.attach(capture);
  private final org.slf4j.Logger log = LoggerFactory.getLogger("task");

  @AfterEach
  void detach() {
    MDC.remove(TaskLogCapture.MDC_KEY);
    ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(Logger.ROOT_LOGGER_NAME)
        .detachAppender(appender);
  }

  private String captured(String taskRunId) {
    return new String(capture.snapshot(taskRunId), StandardCharsets.UTF_8);
  }

  @Test
  void aTaggedLineIsTheTasksAndAnUntaggedOneIsNobodys() {
    capture.open("t-1");
    log.info("tagged {}", 1);
    MDC.remove(TaskLogCapture.MDC_KEY);
    log.info("untagged");

    assertThat(captured("t-1")).isEqualTo("tagged 1\n");
  }

  /*
   * The host's every log event passes through the appender: an event with no task in its MDC
   * is dropped before its line is formatted, and threads never wait on one another to log.
   */
  @Test
  void untaggedEventsFromManyThreadsAreDroppedUnformatted() throws Exception {
    java.util.concurrent.atomic.AtomicInteger formatted =
        new java.util.concurrent.atomic.AtomicInteger();
    appender.setLayout(
        new ch.qos.logback.core.LayoutBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
          @Override
          public String doLayout(ch.qos.logback.classic.spi.ILoggingEvent event) {
            formatted.incrementAndGet();
            return event.getFormattedMessage() + "\n";
          }
        });
    capture.open("t-1");
    Thread[] others = new Thread[8];
    for (int i = 0; i < others.length; i++) {
      others[i] = Thread.ofVirtual().unstarted(() -> log.info("noise"));
    }
    for (Thread other : others) {
      other.start();
    }
    for (Thread other : others) {
      other.join();
    }
    assertThat(formatted.get()).isZero();

    log.info("tagged");

    assertThat(formatted.get()).isEqualTo(1);
    assertThat(captured("t-1")).isEqualTo("tagged\n");
    assertThat(appender)
        .isInstanceOf(ch.qos.logback.core.UnsynchronizedAppenderBase.class);
  }

  @Test
  void aThrowableFollowsItsMessage() {
    capture.open("t-1");
    log.warn("failed", new IllegalStateException("boom"));

    assertThat(captured("t-1"))
        .startsWith("failed\njava.lang.IllegalStateException: boom\n")
        .contains("\tat " + TaskLogAppenderTest.class.getName());
  }

  @Test
  void aLayoutFormatsTheLine() {
    ch.qos.logback.classic.PatternLayout layout = new ch.qos.logback.classic.PatternLayout();
    layout.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    layout.setPattern("%level %msg%n");
    layout.start();
    appender.setLayout(layout);
    capture.open("t-1");

    log.info("laid out");

    assertThat(captured("t-1")).isEqualTo("INFO laid out\n");
  }
}

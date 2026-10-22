package io.boomerang.dispatcher.sdk.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.boomerang.dispatcher.sdk.TaskLogCapture;
import org.slf4j.LoggerFactory;

/**
 * Feeds a {@link TaskLogCapture} from logback: every event logged while a task runs, on any
 * thread carrying the task-run id in its MDC, lands in that task's log. {@link #attach} adds one
 * to the root logger of the running logback; a host that configures logback itself adds it to
 * the loggers it wants captured instead. Without a {@link #setLayout layout} a line is the
 * event's message, followed by its stack trace when it carries a throwable.
 *
 * <p>Unsynchronized: the host's every log event passes through here, and the capture's buffers
 * are safe on their own, so no thread waits on another to log. An event with no task in its MDC
 * is dropped before its line is formatted.
 */
public final class TaskLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final TaskLogCapture capture;
  private Layout<ILoggingEvent> layout;

  public TaskLogAppender(TaskLogCapture capture) {
    this.capture = capture;
    setName("flow-task-log");
  }

  /** Add an appender for {@code capture} to the root logger of the logback SLF4J is bound to. */
  public static TaskLogAppender attach(TaskLogCapture capture) {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    TaskLogAppender appender = new TaskLogAppender(capture);
    appender.setContext(context);
    appender.start();
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    return appender;
  }

  /** Format each line with {@code layout} instead of the message alone. */
  public void setLayout(Layout<ILoggingEvent> layout) {
    this.layout = layout;
  }

  @Override
  protected void append(ILoggingEvent event) {
    String taskRunId = event.getMDCPropertyMap().get(TaskLogCapture.MDC_KEY);
    if (taskRunId == null || !capture.isCapturing(taskRunId)) {
      return;
    }
    capture.append(taskRunId, line(event));
  }

  private String line(ILoggingEvent event) {
    if (layout != null) {
      return layout.doLayout(event);
    }
    IThrowableProxy thrown = event.getThrowableProxy();
    return (thrown != null)
        ? event.getFormattedMessage() + "\n" + ThrowableProxyUtil.asString(thrown)
        : event.getFormattedMessage();
  }
}

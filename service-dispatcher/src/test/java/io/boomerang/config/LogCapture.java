package io.boomerang.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Captures every Log4j2 line, from any thread, while open: an in-memory appender on the root
 * logger, with the named loggers raised to the given level. Closing removes the appender and puts
 * the levels back.
 */
public final class LogCapture extends AbstractAppender implements AutoCloseable {

  private final List<String> lines = new CopyOnWriteArrayList<>();
  private final Map<String, Level> previous = new LinkedHashMap<>();

  private LogCapture() {
    super("log-capture", null, null, true, Property.EMPTY_ARRAY);
  }

  public static LogCapture start(Level level, String... loggers) {
    LogCapture capture = new LogCapture();
    for (String name : loggers) {
      capture.previous.put(name, LogManager.getLogger(name).getLevel());
      Configurator.setLevel(name, level);
    }
    capture.start();
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    LoggerConfig root = context.getConfiguration().getRootLogger();
    root.addAppender(capture, Level.ALL, null);
    context.updateLoggers();
    return capture;
  }

  @Override
  public void append(LogEvent event) {
    StringBuilder line = new StringBuilder(event.getMessage().getFormattedMessage());
    if (event.getThrown() != null) {
      line.append(' ').append(event.getThrown());
    }
    lines.add(line.toString());
  }

  public List<String> lines() {
    return List.copyOf(lines);
  }

  @Override
  public void close() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    context.getConfiguration().getRootLogger().removeAppender(getName());
    previous.forEach(Configurator::setLevel);
    context.updateLoggers();
    stop();
  }
}

package io.boomerang.dispatcher.sdk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.util.Assert;

/**
 * The lines a task's handler logs while it runs, kept per task run and uploaded as the task's log
 * by the runner (see {@link Dispatcher.Builder#captureTaskLogs}). The runner opens a buffer for a
 * task before its handler and puts the task-run id in the logging MDC under {@value #MDC_KEY}; the
 * host's log framework feeds the buffer through {@link #append} with the id each event carries in
 * its MDC ({@code io.boomerang.dispatcher.sdk.logback.TaskLogAppender} does it for logback).
 *
 * <p>Each buffer keeps its first {@code headBytes} and its last {@code tailBytes}, so a chatty task
 * cannot grow it without bound; what fell between is replaced by one line saying how many bytes
 * were dropped. Needs {@code org.slf4j:slf4j-api} on the class path, which every host that logs
 * has.
 */
public final class TaskLogCapture {

  /** The MDC key that carries the task-run id while a handler runs. */
  public static final String MDC_KEY = "taskRunId";

  /** The default cap for each of the head and the tail. */
  public static final int DEFAULT_CAP_BYTES = 64 * 1024;

  private final int headBytes;
  private final int tailBytes;
  private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();

  public TaskLogCapture() {
    this(DEFAULT_CAP_BYTES, DEFAULT_CAP_BYTES);
  }

  public TaskLogCapture(int headBytes, int tailBytes) {
    Assert.isTrue(headBytes > 0, "headBytes must be positive");
    Assert.isTrue(tailBytes > 0, "tailBytes must be positive");
    this.headBytes = headBytes;
    this.tailBytes = tailBytes;
  }

  /**
   * Add one line to the log of task run {@code taskRunId}, as the host's appender reads it from
   * the event's MDC. A line for a task not running here, or with no id, is dropped; a line without
   * a trailing newline gets one.
   */
  public void append(String taskRunId, String line) {
    Buffer buffer = (taskRunId != null && line != null) ? buffers.get(taskRunId) : null;
    if (buffer != null) {
      buffer.append((line.endsWith("\n") ? line : line + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  /** Whether a buffer is open for {@code taskRunId}. */
  public boolean isCapturing(String taskRunId) {
    return buffers.containsKey(taskRunId);
  }

  // Open the buffer and tag the calling thread; called by the runner on the handler's thread.
  void open(String taskRunId) {
    buffers.put(taskRunId, new Buffer());
    MDC.put(MDC_KEY, taskRunId);
  }

  /**
   * The log of a running task so far: head, the gap line when bytes were dropped, tail. Empty
   * when no task of that id runs here. A host that registers a live log route can serve it.
   */
  public byte[] snapshot(String taskRunId) {
    Buffer buffer = buffers.get(taskRunId);
    return (buffer != null) ? buffer.bytes() : new byte[0];
  }

  // Close the buffer, untag the thread and return the finished log.
  byte[] close(String taskRunId) {
    MDC.remove(MDC_KEY);
    Buffer buffer = buffers.remove(taskRunId);
    return (buffer != null) ? buffer.bytes() : new byte[0];
  }

  // One task's log: the head fills first; once a line does not fit it, every later line goes to
  // the tail, which drops its oldest lines to stay under its cap.
  private final class Buffer {

    private final ByteArrayOutputStream head = new ByteArrayOutputStream();
    private final Deque<byte[]> tail = new ArrayDeque<>();
    private int tailSize;
    private long dropped;
    private boolean headFull;

    synchronized void append(byte[] line) {
      if (!headFull && head.size() + line.length <= headBytes) {
        head.writeBytes(line);
        return;
      }
      headFull = true;
      tail.addLast(line);
      tailSize += line.length;
      while (tailSize > tailBytes) {
        byte[] oldest = tail.removeFirst();
        tailSize -= oldest.length;
        dropped += oldest.length;
      }
    }

    synchronized byte[] bytes() {
      ByteArrayOutputStream out = new ByteArrayOutputStream(head.size() + tailSize + 64);
      out.writeBytes(head.toByteArray());
      if (dropped > 0) {
        out.writeBytes(
            ("... " + dropped + " bytes dropped ...\n").getBytes(StandardCharsets.UTF_8));
      }
      tail.forEach(out::writeBytes);
      return out.toByteArray();
    }
  }
}
